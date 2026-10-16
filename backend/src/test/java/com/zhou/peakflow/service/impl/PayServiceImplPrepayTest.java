package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.service.OrderService;
import com.zhou.peakflow.vo.PrepayVo;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发起支付 <b>锁层单元测试</b>：{@code PayServiceImpl#prepay(userId, orderId)}（分布式锁层，不含事务）。
 *
 * <p>方法结构：getLock("lock:prepay:"+orderId) → tryLock(3, 10, SECONDS)（失败抛"操作过于频繁"）→
 * 委托 payTxService.doPrepay → catch InterruptedException 恢复中断标志并抛"系统繁忙" →
 * finally 在 isHeldByCurrentThread 时 unlock。</p>
 *
 * <p>为什么这层必须单独存在：doPrepay 的幂等是先查再插的 check-then-act，而 pay_record 上没有
 * "同一订单只有一张待支付单"的约束，所以并发安全完全靠这把锁，且锁必须在事务外层（锁在事务内时
 * 解锁可能早于提交，另一线程仍会建出两张单）。</p>
 *
 * <p>覆盖 5 条分支：加锁成功 → 委托 + 释放锁；加锁失败 → 抛"操作过于频繁"且不委托不解锁；
 * 加锁被中断 → 恢复中断标志；业务抛异常 → finally 仍释放锁（锁泄漏会让该订单永久无法再发起支付）；
 * 锁不由当前线程持有 → 不调 unlock。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("发起支付锁层单元测试 PayServiceImpl#prepay")
class PayServiceImplPrepayTest {

    @Mock
    private PayRecordMapper payRecordMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderService orderService;
    @Mock
    private MockPayProperties mockPayProperties;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private PayTxService payTxService;
    /** 分布式锁对象本身也要 mock（由 redissonClient.getLock 返回） */
    @Mock
    private RLock lock;

    @InjectMocks
    private PayServiceImpl payService;

    // 常量

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 2001L;
    private static final String EXPECTED_LOCK_KEY = "lock:prepay:" + ORDER_ID;
    /** 与生产代码一致的等待/租约时间 */
    private static final long WAIT_SECONDS = 3L;
    private static final long LEASE_SECONDS = 10L;

    /**
     * 用例 3 会主动设置线程中断标志（验证"恢复中断"），
     * 必须在每个用例后清掉，否则会污染后续测试（如 IO 操作会立刻抛 InterruptedException）。
     */
    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    // 一、正常路径

    /**
     * <p><b>为什么测它：</b>锁层的正常路径：锁 Key 按订单维度、等待/租约时间正确、委托业务、最终释放锁。</p>
     * <p><b>关键断言：</b>锁 Key = `lock:prepay:{orderId}`；tryLock(3,10,SECONDS)；doPrepay 被调；unlock 被调。</p>
     */

    @Test
    @DisplayName("[业务正确] 加锁成功 → 委托 doPrepay 并原样返回其结果，最后释放锁")
    void prepay_lockAcquired_shouldDelegateAndUnlock() throws Exception {
        PrepayVo expected = prepayVoOf("PAY" + ORDER_ID + "-1700000000000");
        givenLockAcquired();
        when(payTxService.doPrepay(USER_ID, ORDER_ID)).thenReturn(expected);

        PrepayVo actual = payService.prepay(USER_ID, ORDER_ID);

        assertEquals(expected, actual, "应把 doPrepay 的结果原样返回");

        // 锁 Key 必须按订单维度拼接 —— 不同订单各用一把锁，才不会被互相阻塞
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redissonClient).getLock(keyCaptor.capture());
        assertEquals(EXPECTED_LOCK_KEY, keyCaptor.getValue(), "锁 Key 应为 lock:prepay:{orderId}");

        // 等待 3 秒、租约 10 秒（租约防止持锁进程崩溃后死锁）
        verify(lock).tryLock(eq(WAIT_SECONDS), eq(LEASE_SECONDS), eq(TimeUnit.SECONDS));

        verify(payTxService).doPrepay(USER_ID, ORDER_ID);
        verify(lock).unlock();
    }

    // 二、加锁失败（并发被挡）

    /**
     * <p><b>为什么测它：</b>拿不到锁说明同一订单正在被处理，应立即提示重试（<b>fail-fast</b>），而不是排队阻塞线程。</p>
     * <p><b>关键断言：</b>抛「操作过于频繁」；<b>never</b> 委托业务、<b>never</b> unlock（锁不是自己拿的）。</p>
     */

    @Test
    @DisplayName("[并发防护] tryLock 超时返回 false → 抛「操作过于频繁」，且【不委托业务、不解锁】")
    void prepay_lockNotAcquired_shouldThrowAndNotDelegate() throws Exception {
        when(redissonClient.getLock(EXPECTED_LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payService.prepay(USER_ID, ORDER_ID));

        assertTrue(ex.getMessage().contains("操作过于频繁"),
                "应立即提示用户重试，而不是排队等待，实际：" + ex.getMessage());
        // 没拿到锁就没资格进业务；也没解锁（锁不是自己拿的）
        verify(payTxService, never()).doPrepay(anyLong(), anyLong());
        verify(lock, never()).unlock();
    }

    // 三、加锁被中断（线程被要求停止）

    /**
     * <p><b>为什么测它：</b>捕获 InterruptedException 后<b>必须恢复中断标志</b>，否则线程池/上层框架无法感知中断信号。</p>
     * <p><b>关键断言：</b>抛「系统繁忙」；<b>断言 Thread.currentThread().isInterrupted() 为 true</b>；<b>never</b> 委托业务。</p>
     */

    @Test
    @DisplayName("[中断处理] tryLock 抛 InterruptedException → 恢复中断标志 + 抛「系统繁忙」")
    void prepay_lockInterrupted_shouldRestoreInterruptFlagAndThrow() throws Exception {
        when(redissonClient.getLock(EXPECTED_LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("interrupted while waiting lock"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payService.prepay(USER_ID, ORDER_ID));

        assertTrue(ex.getMessage().contains("系统繁忙"), "实际：" + ex.getMessage());
        // 核心断言：必须把中断标志还回去，否则上层（线程池/框架）无法感知中断信号
        assertTrue(Thread.currentThread().isInterrupted(),
                "捕获 InterruptedException 后必须调用 Thread.currentThread().interrupt() 恢复标志");
        verify(payTxService, never()).doPrepay(anyLong(), anyLong());
    }

    // 四、业务异常时锁的释放（finally 生效）

    /**
     * <p><b>为什么测它：</b><b>锁泄漏</b>会让该订单永久无法再发起支付，所以异常路径也必须走到 finally 的 unlock。</p>
     * <p><b>关键断言：</b>异常向上抛；<b>verify unlock 被调用</b>。</p>
     */

    @Test
    @DisplayName("[锁不泄漏] 业务抛异常 → 异常向上传播，但锁仍被释放（否则该订单永久无法再支付）")
    void prepay_businessThrows_shouldStillReleaseLock() throws Exception {
        givenLockAcquired();
        when(payTxService.doPrepay(USER_ID, ORDER_ID))
                .thenThrow(new BusinessException("订单当前状态不允许支付：已付款"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payService.prepay(USER_ID, ORDER_ID));

        assertTrue(ex.getMessage().contains("不允许支付"));
        // 核心断言：异常路径也必须走到 finally 里的 unlock
        verify(lock).unlock();
    }

    // 五、锁不由当前线程持有（防御性检查）

    /**
     * <p><b>为什么测它：</b>防御：租约到期后锁可能已被别的线程接手，此时再 unlock 会抛 IllegalMonitorStateException。</p>
     * <p><b>关键断言：</b>不抛异常；<b>never</b> unlock；业务本身正常执行。</p>
     */

    @Test
    @DisplayName("[防御检查] isHeldByCurrentThread 为 false → 不调用 unlock（避免 IllegalMonitorStateException）")
    void prepay_lockHeldByAnotherThread_shouldNotUnlock() throws Exception {
        when(redissonClient.getLock(EXPECTED_LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        // 租约到期后锁被别的线程拿走 —— 此时若还去 unlock 会抛 IllegalMonitorStateException
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(payTxService.doPrepay(USER_ID, ORDER_ID)).thenReturn(prepayVoOf("PAY-X"));

        assertDoesNotThrow(() -> payService.prepay(USER_ID, ORDER_ID),
                "锁被别的线程持有时不应该抛异常，只是不解锁而已");

        verify(lock, never()).unlock();
        // 业务本身是正常执行的
        verify(payTxService).doPrepay(USER_ID, ORDER_ID);
    }

    /**
     * <p><b>为什么测它：</b>对照用例：锁确实由当前线程持有时，一定会 unlock。</p>
     * <p><b>关键断言：</b>不抛异常、未设置中断标志；<b>verify unlock 被调用</b>。</p>
     */

    @Test
    @DisplayName("[对照] 锁由当前线程持有时 → 一定会 unlock（与上一个用例形成对照）")
    void prepay_lockHeldByCurrentThread_shouldUnlock() throws Exception {
        givenLockAcquired();
        when(payTxService.doPrepay(USER_ID, ORDER_ID)).thenReturn(prepayVoOf("PAY-Y"));

        payService.prepay(USER_ID, ORDER_ID);

        assertFalse(Thread.currentThread().isInterrupted(), "正常路径不应设置中断标志");
        verify(lock).unlock();
    }

    // 助手

    /** stub：加锁成功，且锁由当前线程持有 */
    private void givenLockAcquired() throws InterruptedException {
        when(redissonClient.getLock(EXPECTED_LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    private PrepayVo prepayVoOf(String outTradeNo) {
        return PrepayVo.builder()
                .outTradeNo(outTradeNo)
                .orderNo("ORD" + ORDER_ID)
                .amount(new BigDecimal("199.00"))
                .payChannel("MOCK_PAY")
                .cashierUrl("http://127.0.0.1:8080/mock-pay/cashier?out_trade_no=" + outTradeNo)
                .build();
    }
}
