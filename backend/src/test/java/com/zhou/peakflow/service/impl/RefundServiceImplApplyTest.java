package com.zhou.peakflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.config.RefundProperties;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.dto.refund.ApplyRefundDto;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.mapper.RefundRecordMapper;
import com.zhou.peakflow.pojo.RefundRecord;
import com.zhou.peakflow.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 申请退款 <b>外层单元测试</b>：{@code RefundServiceImpl#apply(userId, dto)}。
 *
 * <p>方法结构：参数兜底（dto / orderId 为空 → PARAM_MISSING）→ 分布式锁 lock:refund:apply:{orderId}
 * tryLock(3, 10, SECONDS)（拿不到抛"操作过于频繁"）→ refundTxService.doApply（事务内）→
 * catch InterruptedException 恢复中断标志 → finally unlock → 锁外：status 为 REFUNDING 时
 * requestThirdPartyRefund → 返回 record.id。</p>
 *
 * <p>为什么第三方调用必须放在锁外：第三方退款是可能慢/超时的 HTTP 调用，若包在锁内，
 * 同一订单在第三方响应返回前都无法再操作退款，且锁租约只有 10 秒，一慢锁就过期、后续请求又会重复发起退款。
 * 正确分工是"锁内只做需要互斥的快速操作（校验 + 落库），锁外做耗时的外部调用"。</p>
 *
 * <p>如何观测私有的 requestThirdPartyRefund：无法直接 verify，但它第一步读
 * {@code mockPayProperties.getRefundUrl()}，故用"该配置是否被读取"作路径断言。测试里把 refundUrl 置为 null
 * 走"未配置"分支，既不真的发 HTTP，又能通过 verify(markRequestFailed) 断言确实进了这个方法。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("申请退款外层单元测试 RefundServiceImpl#apply")
class RefundServiceImplApplyTest {

    @Mock
    private RefundRecordMapper refundRecordMapper;
    @Mock
    private PayRecordMapper payRecordMapper;
    @Mock
    private RefundTxService refundTxService;
    @Mock
    private OrderService orderService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private MockPayProperties mockPayProperties;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private RefundProperties refundProperties;
    @Mock
    private RLock lock;

    @InjectMocks
    private RefundServiceImpl refundService;

    // 常量

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 2001L;
    private static final String REFUND_NO = "REFORD2026101400001-1700000000000";
    private static final String EXPECTED_LOCK_KEY = "lock:refund:apply:" + ORDER_ID;
    private static final long WAIT_SECONDS = 3L;
    private static final long LEASE_SECONDS = 10L;

    /** 用例 4 会设置线程中断标志，用后必须清理 */
    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    // 一、参数兜底（加锁之前）

    /**
     * <p><b>为什么测它：</b>参数都不合法时若还去拿分布式锁，是无谓的 Redis 往返。</p>
     * <p><b>关键断言：</b>抛 1001；<b>never</b> getLock（连锁都不该去拿）。</p>
     */

    @Test
    @DisplayName("[入参错误] dto 为 null → BusinessException(1001)，且【不去拿锁】")
    void apply_nullDto_shouldThrowWithoutLocking() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.apply(USER_ID, null));

        assertEquals(ResultCode.PARAM_MISSING.getCode(), ex.getCode());
        // 路径断言：参数都不合法，连锁都不该去拿
        verify(redissonClient, never()).getLock(anyString());
    }

    /**
     * <p><b>为什么测它：</b>orderId 是锁 Key 的组成部分，为空时拿锁也没意义。</p>
     * <p><b>关键断言：</b>抛 1001 且消息含「订单ID」；<b>never</b> getLock。</p>
     */

    @Test
    @DisplayName("[入参错误] dto.orderId 为 null → BusinessException(1001)，且不去拿锁")
    void apply_nullOrderId_shouldThrowWithoutLocking() {
        ApplyRefundDto dto = new ApplyRefundDto();
        dto.setOrderId(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.apply(USER_ID, dto));

        assertEquals(ResultCode.PARAM_MISSING.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("订单ID"), "实际：" + ex.getMessage());
        verify(redissonClient, never()).getLock(anyString());
    }

    // 二、锁的三条分支

    /**
     * <p><b>为什么测它：</b>同一订单正在被处理时应 fail-fast 提示重试，不排队阻塞线程。</p>
     * <p><b>关键断言：</b>抛「操作过于频繁」；<b>never</b> 委托 doApply、<b>never</b> unlock。</p>
     */

    @Test
    @DisplayName("[并发防护] 拿锁超时 → 抛「操作过于频繁」，且【不委托业务、不解锁】")
    void apply_lockNotAcquired_shouldThrowAndNotDelegate() throws Exception {
        when(redissonClient.getLock(EXPECTED_LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.apply(USER_ID, dtoOf()));

        assertTrue(ex.getMessage().contains("操作过于频繁"), "实际：" + ex.getMessage());
        verify(refundTxService, never()).doApply(anyLong(), anyLong(), any(), any(), any());
        verify(lock, never()).unlock();
    }

    /**
     * <p><b>为什么测它：</b>捕获 InterruptedException 后必须恢复中断标志，否则上层无法感知中断。</p>
     * <p><b>关键断言：</b>抛「系统繁忙」；<b>断言中断标志为 true</b>；<b>never</b> 委托业务。</p>
     */

    @Test
    @DisplayName("[中断处理] tryLock 抛 InterruptedException → 恢复中断标志 + 抛「系统繁忙」")
    void apply_lockInterrupted_shouldRestoreFlagAndThrow() throws Exception {
        when(redissonClient.getLock(EXPECTED_LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("interrupted"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.apply(USER_ID, dtoOf()));

        assertTrue(ex.getMessage().contains("系统繁忙"), "实际：" + ex.getMessage());
        assertTrue(Thread.currentThread().isInterrupted(),
                "必须恢复中断标志，否则上层线程池无法感知中断信号");
        verify(refundTxService, never()).doApply(anyLong(), anyLong(), any(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b>锁层正常路径：锁粒度按订单、等待/租约正确、委托后释放锁。</p>
     * <p><b>关键断言：</b>锁 Key = `lock:refund:apply:{orderId}`；tryLock(3,10,SECONDS)；doApply 被调；unlock 被调。</p>
     */

    @Test
    @DisplayName("[业务正确] 拿锁成功 → 锁 Key 与等待/租约时间正确，委托 doApply 后释放锁")
    void apply_lockAcquired_shouldDelegateAndUnlock() throws Exception {
        givenLockAcquired();
        givenDoApplyReturns(RefundStatus.APPLYING.getCode());
        // 注意：APPLYING（待审核）不会走第三方退款，所以这里【不】stub refundUrl ——
        // 不 stub 反而能证明该路径确实没碰第三方配置。

        Long refundId = refundService.apply(USER_ID, dtoOf());

        assertEquals(1L, refundId, "应返回退款单 id");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redissonClient).getLock(keyCaptor.capture());
        assertEquals(EXPECTED_LOCK_KEY, keyCaptor.getValue(),
                "锁粒度按订单维度 —— 同一订单串行，不同订单互不阻塞");
        verify(lock).tryLock(eq(WAIT_SECONDS), eq(LEASE_SECONDS), eq(TimeUnit.SECONDS));
        verify(refundTxService).doApply(eq(USER_ID), eq(ORDER_ID), any(), any(), any());
        verify(lock).unlock();
    }

    /**
     * <p><b>为什么测它：</b>锁泄漏会让该订单永久无法再申请退款 —— 异常路径也必须走到 finally 的 unlock。</p>
     * <p><b>关键断言：</b>异常上抛；<b>verify unlock 被调用</b>；且<b>不该去发起第三方退款</b>。</p>
     */

    @Test
    @DisplayName("[锁不泄漏] 业务抛异常 → 异常上抛，但 finally 仍释放锁")
    void apply_businessThrows_shouldStillUnlock() throws Exception {
        givenLockAcquired();
        when(refundTxService.doApply(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new BusinessException(ResultCode.REFUND_REPEAT));

        assertThrows(BusinessException.class, () -> refundService.apply(USER_ID, dtoOf()));

        verify(lock).unlock();
        // 业务都失败了，更不该去发起第三方退款
        verify(mockPayProperties, never()).getRefundUrl();
    }

    /**
     * <p><b>为什么测它：</b>防御：租约到期后锁可能被别的线程接手，此时 unlock 会抛 IllegalMonitorStateException。</p>
     * <p><b>关键断言：</b>不抛异常；<b>never</b> unlock。</p>
     */

    @Test
    @DisplayName("[防御检查] 锁不由当前线程持有 → 不调用 unlock")
    void apply_lockHeldByAnotherThread_shouldNotUnlock() throws Exception {
        when(redissonClient.getLock(EXPECTED_LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);   // 租约到期被别的线程接手
        givenDoApplyReturns(RefundStatus.APPLYING.getCode());

        assertDoesNotThrow(() -> refundService.apply(USER_ID, dtoOf()),
                "正常路径不应抛异常");

        verify(lock, never()).unlock();
    }

    // 三、锁外发起第三方退款（本类最重要的分支）

    /**
     * <p><b>为什么测它：</b><b>本类最核心的设计验证</b>：仅退款类型落库即「退款中」，此时必须在<b>锁外</b>立即发起第三方退款。</p>
     * <p><b>关键断言：</b>读到 getRefundUrl 说明确实进入了 requestThirdPartyRefund；未配置地址时应 markRequestFailed 而不是静默丢弃。</p>
     */

    @Test
    @DisplayName("[业务正确] 落库状态是「退款中(1)」（仅退款类型）→ 【锁外】立即发起第三方退款")
    void apply_refundingStatus_shouldRequestThirdPartyAfterUnlock() throws Exception {
        givenLockAcquired();
        givenDoApplyReturns(RefundStatus.REFUNDING.getCode());
        when(mockPayProperties.getRefundUrl()).thenReturn(null);   // 模拟未配置 → 走失败分支

        assertDoesNotThrow(() -> refundService.apply(USER_ID, dtoOf()),
                "正常路径不应抛异常");

        // 路径断言：读到 refundUrl 说明确实进入了 requestThirdPartyRefund
        verify(mockPayProperties).getRefundUrl();
        // 未配置地址时应把退款单标记为请求失败（而不是静默丢弃）
        verify(refundTxService).markRequestFailed(eq(REFUND_NO), contains("未配置"));
    }

    /**
     * <p><b>为什么测它：</b><b>对照用例</b>：退货退款是「待审核」，必须先等商家收货（先入库、后放款），此刻绝不能动钱。</p>
     * <p><b>关键断言：</b><b>never</b> 读 getRefundUrl、<b>never</b> markRequestFailed —— 证明第三方调用确实没发生。</p>
     */

    @Test
    @DisplayName("[业务正确] 落库状态是「待审核(0)」（退货退款类型）→ 【不】发起第三方退款")
    void apply_applyingStatus_shouldNotRequestThirdParty() throws Exception {
        givenLockAcquired();
        givenDoApplyReturns(RefundStatus.APPLYING.getCode());

        assertDoesNotThrow(() -> refundService.apply(USER_ID, dtoOf()),
                "正常路径不应抛异常");

        // 对照：退货退款要先等商家收货（先入库、后放款），此时绝不能动钱
        verify(mockPayProperties, never()).getRefundUrl();
        verify(refundTxService, never()).markRequestFailed(anyString(), anyString());
    }

    // 助手

    private ApplyRefundDto dtoOf() {
        ApplyRefundDto dto = new ApplyRefundDto();
        dto.setOrderId(ORDER_ID);
        dto.setReason("不想要了");
        return dto;
    }

    private void givenLockAcquired() throws InterruptedException {
        when(redissonClient.getLock(EXPECTED_LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    /** stub：doApply 返回一张指定状态的退款单 */
    private void givenDoApplyReturns(int status) {
        RefundRecord record = new RefundRecord();
        record.setId(1L);
        record.setRefundNo(REFUND_NO);
        record.setOrderId(ORDER_ID);
        record.setUserId(USER_ID);
        record.setOutTradeNo("PAY" + ORDER_ID + "-1700000000000");
        record.setRefundAmount(new BigDecimal("199.00"));
        record.setStatus(status);
        when(refundTxService.doApply(anyLong(), anyLong(), any(), any(), any())).thenReturn(record);
    }
}
