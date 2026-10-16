package com.zhou.peakflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.config.RefundProperties;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.enums.RefundType;
import com.zhou.peakflow.dto.pay.RefundNotifyDto;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.mapper.RefundRecordMapper;
import com.zhou.peakflow.pojo.RefundRecord;
import com.zhou.peakflow.service.OrderService;
import com.zhou.peakflow.utils.PaySignUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款回调 <b>单元测试</b>：{@code RefundServiceImpl#handleRefundNotify(dto, rawBody)}
 * （唯一能"确认退款"的入口，第三方回调无需登录）。
 *
 * <p>判定链：验签 → 查单 → 幂等第一道（本地已 SUCCESS、或失败回调且本地已 FAILED → success）→
 * 状态守卫 allowed=(status==REFUNDING || 成功回调 && status==FAILED) → 金额校验 → 成功回调走
 * handleRefundSuccess（CAS status IN (1,3)，成功后订单与支付流水置已退款）/ 失败回调走 handleRefundFailed。</p>
 *
 * <p>最容易埋雷的是状态守卫，两个方向相反、必须同时覆盖：本地"待审核"收到成功回调必须拒绝（钱不能先出去）；
 * 本地"退款失败"收到成功回调必须允许修正为成功（"失败"可能只是本地调第三方网络异常误判，钱其实退了），
 * 否则会变成"钱退了但系统认为没退"的资金不一致 —— 这就是那个事故级缺陷。</p>
 *
 * <p>返回值只有 "success"/"failure"，而 "failure" 有验签/查单/守卫/金额/CAS 多个来源，
 * 故每个拒绝类用例都额外断言"走到了哪一步"（见 assertSignGatePassed 与各用例的 verify）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("退款回调单元测试 RefundServiceImpl#handleRefundNotify")
class RefundServiceImplNotifyTest {

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

    @InjectMocks
    private RefundServiceImpl refundService;

    // 常量

    private static final String SECRET = "unit-test-secret-key";
    private static final String OUT_REFUND_NO = "REFORD2026101400001-1700000000000";
    private static final String OUT_TRADE_NO = "PAYORD2026101400001-1700000000000";
    private static final String REFUND_TRADE_NO = "MOCK-REFUND-20261014-0001";
    private static final BigDecimal REFUND_AMOUNT = new BigDecimal("199.00");
    private static final Long ORDER_ID = 2001L;
    private static final long TIMESTAMP = 1760400000000L;
    /** 第三方回调的 status 语义：1 = 退款成功 */
    private static final int NOTIFY_SUCCESS = 1;
    /** 第三方回调的 status 语义：0 = 退款失败 */
    private static final int NOTIFY_FAIL = 0;
    private static final String RAW_BODY = "{\"outRefundNo\":\"" + OUT_REFUND_NO + "\"}";

    @BeforeEach
    void stubSecretKey() {
        when(mockPayProperties.getSecretKey()).thenReturn(SECRET);
    }

    // 一、验签 + 查单

    /**
     * <p><b>为什么测它：</b>退款回调同样是无认证的外部入口，验签是唯一能挡住伪造报文的闸门。</p>
     * <p><b>关键断言：</b>返回 failure；<b>never</b> 查单、<b>never</b> 改状态。</p>
     */

    @Test
    @DisplayName("[验证失败] 签名被篡改 → 返回 failure，且【绝不查库、绝不改状态】")
    void handleRefundNotify_tamperedSign_shouldRejectBeforeAnyDbAccess() throws Exception {
        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_SUCCESS, false);

        assertEquals("failure", resp, "验签失败必须返回 failure，让第三方重推");
        verify(refundRecordMapper, never()).selectByRefundNo(anyString());
        verify(refundRecordMapper, never()).markRefundSuccess(anyString(), any(), any());
        verify(refundRecordMapper, never()).markRefundFailed(anyString(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b>本地没有这笔退款单时不能凭空改状态。</p>
     * <p><b>关键断言：</b>返回 failure；并 <b>verify</b> 查单被调用过（反证验签已通过）。</p>
     */

    @Test
    @DisplayName("[非法输入] 退款单不存在 → 返回 failure")
    void handleRefundNotify_recordNotFound_shouldReject() throws Exception {
        when(refundRecordMapper.selectByRefundNo(OUT_REFUND_NO)).thenReturn(null);

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_SUCCESS, true);

        assertEquals("failure", resp);
        // 路径断言：走到了查单，说明验签一定通过了
        assertSignGatePassed();
    }

    // 二、幂等第一道（终态直接应答成功）

    /**
     * <p><b>为什么测它：</b>第三方重复通知是常态，已成功的单子必须直接应答 success 且<b>不产生任何写操作</b>。</p>
     * <p><b>关键断言：</b>返回 success；<b>never</b> markRefundSuccess、<b>never</b> 改订单。</p>
     */

    @Test
    @DisplayName("[幂等] 本地已退款成功 + 收到成功回调 → success，且【不再执行任何写操作】")
    void handleRefundNotify_alreadySuccess_shouldReturnSuccessWithoutWrite() throws Exception {
        givenRefundRecord(RefundStatus.SUCCESS.getCode());

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_SUCCESS, true);

        assertEquals("success", resp, "第三方重复通知是常态，直接应答成功让它停止重推");
        assertSignGatePassed();
        verify(refundRecordMapper, never()).markRefundSuccess(anyString(), any(), any());
        verify(orderService, never()).markOrderRefunded(anyLong(), any(), anyInt());
    }

    /**
     * <p><b>为什么测它：</b><b>终态不可回退</b>：本地已确认退款成功，一条迟到的「失败」通知不能把状态改回去（否则资金不一致）。</p>
     * <p><b>关键断言：</b>返回 success；<b>never</b> markRefundFailed。</p>
     */

    @Test
    @DisplayName("[幂等] 本地已退款成功 + 收到【失败】回调 → 仍是 success（终态不因回调方向而回退）")
    void handleRefundNotify_alreadySuccessButFailNotify_shouldStillReturnSuccess() throws Exception {
        givenRefundRecord(RefundStatus.SUCCESS.getCode());

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_FAIL, true);

        assertEquals("success", resp,
                "本地已经确认退款成功，不能因为一条迟到的『失败』通知就把状态回退 —— 那会造成资金不一致");
        verify(refundRecordMapper, never()).markRefundFailed(anyString(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b>本地已是失败终态，重复的失败通知无需再写。</p>
     * <p><b>关键断言：</b>返回 success；<b>never</b> markRefundFailed。</p>
     */

    @Test
    @DisplayName("[幂等] 本地已退款失败 + 收到失败回调 → success，不重复写")
    void handleRefundNotify_alreadyFailedWithFailNotify_shouldReturnSuccess() throws Exception {
        givenRefundRecord(RefundStatus.FAILED.getCode());

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_FAIL, true);

        assertEquals("success", resp);
        verify(refundRecordMapper, never()).markRefundFailed(anyString(), any(), any());
    }

    // 三、状态守卫（本接口最容易埋雷的地方）

    /**
     * <p><b>为什么测它：</b><b>状态守卫（收）</b>：本地「待审核」时收到「退款成功」必须拒绝 —— 商家未审、货未退，<b>钱绝不能先出去</b>。</p>
     * <p><b>关键断言：</b>返回 failure；<b>never</b> markRefundSuccess。</p>
     */

    @Test
    @DisplayName("[状态守卫] 本地「待审核(0)」收到「退款成功」回调 → failure（商家未审、货未退，钱绝不能先出去）")
    void handleRefundNotify_applyingReceivesSuccess_shouldReject() throws Exception {
        givenRefundRecord(RefundStatus.APPLYING.getCode());

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_SUCCESS, true);

        assertEquals("failure", resp,
                "待审核状态不该出现成功回调；一旦出现必须拒绝并让人看见，绝不能顺着回调改状态");
        assertSignGatePassed();
        verify(refundRecordMapper, never()).markRefundSuccess(anyString(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b><b>状态守卫（放）—— 这就是注释里说的那个事故级缺陷</b>：「退款失败」可能只是本地调第三方时网络异常误判，钱其实退了。必须允许修正，否则变成「钱退了但系统认为没退」。</p>
     * <p><b>关键断言：</b>返回 success；<b>verify markRefundSuccess 被调用</b>（把「自愈」落地）。</p>
     */

    @Test
    @DisplayName("[状态守卫·自愈] 本地「退款失败(3)」收到「退款成功」回调 → 允许修正为成功（防资金不一致）")
    void handleRefundNotify_failedReceivesSuccess_shouldSelfHeal() throws Exception {
        givenRefundRecord(RefundStatus.FAILED.getCode());
        when(refundRecordMapper.markRefundSuccess(anyString(), any(), any())).thenReturn(1);
        when(payRecordMapper.markRefunded(OUT_TRADE_NO)).thenReturn(1);

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_SUCCESS, true);

        assertEquals("success", resp,
                "『退款失败』可能只是本地调第三方时网络异常误判，钱其实已经退了 —— "
                        + "此时必须允许修正，否则就变成『钱退了但系统认为没退』");
        // 路径断言：确实走到了写操作（这正是"自愈"的落地）
        verify(refundRecordMapper).markRefundSuccess(eq(OUT_REFUND_NO), eq(REFUND_TRADE_NO), anyString());
    }

    /**
     * <p><b>为什么测它：</b>参数化覆盖其余中间/终态（待用户退货 7、已驳回 4），确保状态守卫的白名单没有漏洞。</p>
     * <p><b>关键断言：</b>两种状态都返回 failure；<b>never</b> markRefundSuccess。</p>
     */

    @Test
    @DisplayName("[状态守卫] 其它中间/终态（待用户退货 7 / 已驳回 4）收到成功回调 → failure")
    void handleRefundNotify_otherStatusReceivesSuccess_shouldReject() throws Exception {
        for (int status : new int[]{RefundStatus.WAIT_RETURN.getCode(), RefundStatus.REJECTED.getCode()}) {
            givenRefundRecord(status);

            assertEquals("failure", notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_SUCCESS, true),
                    "状态 " + status + " 不该被成功回调改写");
        }
        verify(refundRecordMapper, never()).markRefundSuccess(anyString(), any(), any());
    }

    // 四、金额校验（防改价攻击）

    /**
     * <p><b>为什么测它：</b>退款金额是最直接的资损面，回调金额与本地不一致必须拒绝。</p>
     * <p><b>关键断言：</b>返回 failure；并 <b>verify</b> 查单被调用过（证明失败在金额校验这一步）；<b>never</b> 入账。</p>
     */

    @Test
    @DisplayName("[攻击·改价] 回调退款金额与本地不一致 → failure，不写状态")
    void handleRefundNotify_amountMismatch_shouldReject() throws Exception {
        givenRefundRecord(RefundStatus.REFUNDING.getCode());

        String resp = notify(OUT_REFUND_NO, new BigDecimal("99999.00"), NOTIFY_SUCCESS, true);

        assertEquals("failure", resp, "金额不符必须拒绝，否则可被用来虚增退款金额");
        assertSignGatePassed();
        verify(refundRecordMapper, never()).markRefundSuccess(anyString(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b>边界：金额缺失不能当成「0 元退款」放过。</p>
     * <p><b>关键断言：</b>返回 failure；<b>never</b> 入账。</p>
     */

    @Test
    @DisplayName("[攻击·改价] 回调金额为 null → failure")
    void handleRefundNotify_nullAmount_shouldReject() throws Exception {
        givenRefundRecord(RefundStatus.REFUNDING.getCode());

        String resp = notify(OUT_REFUND_NO, null, NOTIFY_SUCCESS, true);

        assertEquals("failure", resp);
        verify(refundRecordMapper, never()).markRefundSuccess(anyString(), any(), any());
    }

    // 五、成功分支（真正入账）

    /**
     * <p><b>为什么测它：</b>退款成功的<b>完整副作用链</b>：退款单入账 → <b>订单置已退款</b> → 支付流水置已退款。三者缺一都会造成数据不一致。</p>
     * <p><b>关键断言：</b>markRefundSuccess 入参带 outRefundNo/tradeNo/报文；markOrderRefunded 带订单号与金额；payRecordMapper.markRefunded 被调。</p>
     */

    @Test
    @DisplayName("[业务正确] 退款中 + 成功回调 → CAS 入账 + 订单置已退款 + 支付流水置已退款")
    void handleRefundNotify_success_shouldMarkRefundedAndSyncDownstream() throws Exception {
        givenRefundRecord(RefundStatus.REFUNDING.getCode());
        when(refundRecordMapper.markRefundSuccess(anyString(), any(), any())).thenReturn(1);
        when(payRecordMapper.markRefunded(OUT_TRADE_NO)).thenReturn(1);

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_SUCCESS, true);

        assertEquals("success", resp);

        // 1) 退款单 CAS 入账（WHERE status IN (1,3)）
        verify(refundRecordMapper).markRefundSuccess(
                eq(OUT_REFUND_NO), eq(REFUND_TRADE_NO), anyString());

        // 2) 只有钱真退回去了，订单状态才允许变 —— 这是"资金先行"的体现
        verify(orderService).markOrderRefunded(eq(ORDER_ID), eq(REFUND_AMOUNT), any());

        // 3) 支付流水同步为「已退款」
        verify(payRecordMapper).markRefunded(OUT_TRADE_NO);
    }

    /**
     * <p><b>为什么测它：</b><b>并发场景</b>：CAS 0 行且重查已成功 = 被另一个回调抢先入账，应答 success 让第三方停止重推。</p>
     * <p><b>关键断言：</b>返回 success；<b>never</b> 重复改订单。</p>
     */

    @Test
    @DisplayName("[并发] CAS 影响 0 行 + 重查已是成功 → success（被抢先），且不重复改订单")
    void handleRefundNotify_casZeroRowsAlreadySuccess_shouldReturnSuccess() throws Exception {
        givenRefundRecord(RefundStatus.REFUNDING.getCode());
        when(refundRecordMapper.markRefundSuccess(anyString(), any(), any())).thenReturn(0);
        // 重查：另一个并发回调已经把它改成成功了
        when(refundRecordMapper.selectByRefundNo(OUT_REFUND_NO))
                .thenReturn(refundOf(RefundStatus.REFUNDING.getCode()))
                .thenReturn(refundOf(RefundStatus.SUCCESS.getCode()));

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_SUCCESS, true);

        assertEquals("success", resp, "被并发抢先入账 —— 对第三方来说这笔已成功");
        verify(orderService, never()).markOrderRefunded(anyLong(), any(), anyInt());
    }

    /**
     * <p><b>为什么测它：</b>入账失败且状态异常时必须返回 failure 继续重推，并留下人工处理窗口。</p>
     * <p><b>关键断言：</b>返回 failure。</p>
     */

    @Test
    @DisplayName("[异常场景] CAS 影响 0 行 + 重查不是成功 → failure（需人工介入）")
    void handleRefundNotify_casZeroRowsNotSuccess_shouldReturnFailure() throws Exception {
        givenRefundRecord(RefundStatus.REFUNDING.getCode());
        when(refundRecordMapper.markRefundSuccess(anyString(), any(), any())).thenReturn(0);
        when(refundRecordMapper.selectByRefundNo(OUT_REFUND_NO))
                .thenReturn(refundOf(RefundStatus.REFUNDING.getCode()))
                .thenReturn(refundOf(RefundStatus.CLOSED.getCode()));

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_SUCCESS, true);

        assertEquals("failure", resp, "入账失败且状态异常，返回 failure 让第三方继续重推并留出人工处理窗口");
    }

    // 六、失败分支

    /**
     * <p><b>为什么测它：</b>失败回调要落库留痕（含失败原因），供管理员重试；但<b>绝不能动订单与支付流水</b>。</p>
     * <p><b>关键断言：</b>markRefundFailed 入参含单号与原因；<b>never</b> markOrderRefunded。</p>
     */

    @Test
    @DisplayName("[业务正确] 退款中 + 失败回调 → 置为退款失败并留痕，返回 success")
    void handleRefundNotify_failNotify_shouldMarkFailed() throws Exception {
        givenRefundRecord(RefundStatus.REFUNDING.getCode());
        when(refundRecordMapper.markRefundFailed(anyString(), anyString(), any())).thenReturn(1);

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_FAIL, true);

        assertEquals("success", resp, "我方已受理这次失败结果，让第三方停止重推");
        verify(refundRecordMapper).markRefundFailed(
                eq(OUT_REFUND_NO), eq("渠道退回失败"), anyString());
        // 失败不该动订单与支付流水
        verify(orderService, never()).markOrderRefunded(anyLong(), any(), anyInt());
    }

    /**
     * <p><b>为什么测它：</b>重复的失败通知：CAS 0 行且已是终态 ⇒ 属于重推，应答 success。</p>
     * <p><b>关键断言：</b>返回 success。</p>
     */

    @Test
    @DisplayName("[幂等] 失败回调但 CAS 影响 0 行且已处终态 → success（重复通知）")
    void handleRefundNotify_failNotifyCasZeroRows_shouldReturnSuccess() throws Exception {
        givenRefundRecord(RefundStatus.REFUNDING.getCode());
        when(refundRecordMapper.markRefundFailed(anyString(), anyString(), any())).thenReturn(0);
        when(refundRecordMapper.selectByRefundNo(OUT_REFUND_NO))
                .thenReturn(refundOf(RefundStatus.REFUNDING.getCode()))
                .thenReturn(refundOf(RefundStatus.FAILED.getCode()));

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_FAIL, true);

        assertEquals("success", resp, "已是终态，属于重复通知");
    }

    /**
     * <p><b>为什么测它：</b>边界：第三方未给失败原因时要有默认文案，不能让字段留空。</p>
     * <p><b>关键断言：</b>返回 success；markRefundFailed 收到默认文案「第三方退款失败」。</p>
     */

    @Test
    @DisplayName("[业务正确] 失败回调未带原因 → 落默认文案「第三方退款失败」")
    void handleRefundNotify_failNotifyWithoutReason_shouldUseDefaultText() throws Exception {
        givenRefundRecord(RefundStatus.REFUNDING.getCode());
        when(refundRecordMapper.markRefundFailed(anyString(), anyString(), any())).thenReturn(1);

        String resp = notify(OUT_REFUND_NO, REFUND_AMOUNT, NOTIFY_FAIL, null, true);
        assertEquals("success", resp, "失败回调（未带原因）也应被受理");

        verify(refundRecordMapper).markRefundFailed(
                eq(OUT_REFUND_NO), eq("第三方退款失败"), anyString());
    }

    // 助手

    /** 路径断言：查单被调用过 ⇒ 反证验签已通过（验签不过根本走不到这一步） */
    private void assertSignGatePassed() {
        verify(refundRecordMapper).selectByRefundNo(OUT_REFUND_NO);
    }

    private String notify(String outRefundNo, BigDecimal amount, int status, boolean validSign) throws Exception {
        return notify(outRefundNo, amount, status, "渠道退回失败", validSign);
    }

    /** 发起退款回调（状态码 status：1=成功 0=失败） */
    private String notify(String outRefundNo, BigDecimal amount, int status,
                          String reason, boolean validSign) {
        Map<String, Object> signParams = new LinkedHashMap<>();
        signParams.put("out_refund_no", outRefundNo);
        signParams.put("refund_trade_no", REFUND_TRADE_NO);
        signParams.put("out_trade_no", OUT_TRADE_NO);
        signParams.put("refund_amount", amount == null ? null : amount.toPlainString());
        signParams.put("status", status);
        signParams.put("reason", reason);
        signParams.put("timestamp", TIMESTAMP);

        RefundNotifyDto dto = new RefundNotifyDto();
        dto.setOutRefundNo(outRefundNo);
        dto.setRefundTradeNo(REFUND_TRADE_NO);
        dto.setOutTradeNo(OUT_TRADE_NO);
        dto.setRefundAmount(amount);
        dto.setStatus(status);
        dto.setReason(reason);
        dto.setTimestamp(TIMESTAMP);
        dto.setSign(validSign ? PaySignUtil.sign(signParams, SECRET) : "TAMPERED-SIGN");

        return refundService.handleRefundNotify(dto, RAW_BODY);
    }

    private RefundRecord refundOf(int status) {
        RefundRecord record = new RefundRecord();
        record.setId(1L);
        record.setRefundNo(OUT_REFUND_NO);
        record.setOrderId(ORDER_ID);
        record.setOutTradeNo(OUT_TRADE_NO);
        record.setRefundAmount(REFUND_AMOUNT);
        record.setRefundType(RefundType.REFUND_ONLY.getCode());
        record.setStatus(status);
        return record;
    }

    /** stub：库里已存在一张指定状态的退款单 */
    private void givenRefundRecord(int status) {
        RefundRecord record = refundOf(status);
        assertTrue(record.getStatus() != null, "造数据不能为空");
        when(refundRecordMapper.selectByRefundNo(OUT_REFUND_NO)).thenReturn(record);
    }
}
