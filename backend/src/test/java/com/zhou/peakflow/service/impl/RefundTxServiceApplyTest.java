package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.config.RefundProperties;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.enums.PayStatus;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.enums.RefundType;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.mapper.RefundRecordMapper;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.pojo.PayRecord;
import com.zhou.peakflow.pojo.RefundRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 申请退款 <b>业务层单元测试</b>：{@code RefundTxService#doApply(userId, orderId, reason, company, trackingNo)}。
 *
 * <p>流程：查订单与归属（3003 / 3007）→ checkRefundable（状态必须 ∈ {PAID, SHIPPED, COMPLETED}，
 * 仅"已完成"叠加窗口期，锚点 completeTime 回落 createTime）→ 服务端推导 refundType（已发货/已完成 →
 * 退货退款需物流，否则仅退款）→ 防重第 1 层 selectOpenByOrderId → 取支付流水金额（一律取流水 amount）
 * → autoApprove 决定 status → 防重第 2 层 insertRefundIfOrderRefundable（可退条件 + version 写进 WHERE）
 * → 防重第 3 层唯一索引 uk_open_order → 查回返回。</p>
 *
 * <p>三层防重是纵深防御：第 1 层应用层友好提示；第 2 层 SQL 的 WHERE（校验与写入合成原子操作）；
 * 第 3 层数据库唯一索引（不依赖 Redis / 应用层的最后闸门）。退款金额只信支付流水，绝不由客户端传参。
 * 窗口期的 fail-safe 方向：已完成订单两个时间锚点都为空时按"超期"拒绝。</p>
 *
 * <p>路径断言：REFUND_REPEAT(3033) 有第 1 层（never insert）与第 3 层（insert 被调用过）两个来源，
 * 只断言异常码无法区分，故用例分别断言"走到了哪一步"。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("申请退款业务层单元测试 RefundTxService#doApply")
class RefundTxServiceApplyTest {

    @Mock
    private RefundRecordMapper refundRecordMapper;
    @Mock
    private PayRecordMapper payRecordMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private RefundProperties refundProperties;
    @Mock
    private MockPayProperties mockPayProperties;

    @InjectMocks
    private RefundTxService refundTxService;

    // 常量

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER_ID = 9999L;
    private static final Long ORDER_ID = 2001L;
    private static final String ORDER_NO = "ORD2026101400001";
    private static final String OUT_TRADE_NO = "PAY" + ORDER_NO + "-1700000000000";
    private static final BigDecimal PAY_AMOUNT = new BigDecimal("199.00");
    private static final String CHANNEL = "MOCK_PAY";
    private static final String COMPANY = "顺丰速运";
    private static final String TRACKING_NO = "SF1234567890123";
    /** 与 RefundProperties 默认值一致 */
    private static final int WINDOW_DAYS = 15;

    @BeforeEach
    void stubCommonConfig() {
        // 这里刻意【不】统一 stub getWindowDays ：
        // 它只在「已完成订单」的窗口期校验里被读到，全局 stub 会让其余用例
        // 触发 Mockito 的 UnnecessaryStubbingException（那反而说明路径检查在生效）。
        // 需要它的用例自己声明。
    }

    // 一、订单存在性与归属（前两道闸门）

    /**
     * <p><b>为什么测它：</b>退款任何一步都需要订单信息（状态、版本、金额依据），订单不存在必须第一步拦住。</p>
     * <p><b>关键断言：</b>抛 3003；<b>never</b> 查支付流水、<b>never</b> 落库。</p>
     */

    @Test
    @DisplayName("[非法输入] 订单不存在 → BusinessException(3003)，且不查支付流水、不落库")
    void doApply_orderNotFound_shouldThrow() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
        verify(payRecordMapper, never()).selectPaidByOrderId(anyLong());
        verify(refundRecordMapper, never()).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b>否则能替别人的订单申请退款（越权 + 资损）。</p>
     * <p><b>关键断言：</b>抛 3007；<b>never</b> 落库。</p>
     */

    @Test
    @DisplayName("[越权防护] 订单不属于当前用户 → BusinessException(3007)，不落库")
    void doApply_orderBelongsToAnotherUser_shouldThrow() {
        Order othersOrder = orderOf(OrderStatus.PAID.getCode(), null);
        othersOrder.setUserId(OTHER_USER_ID);          // 关键：订单属于别人
        when(orderMapper.selectById(ORDER_ID)).thenReturn(othersOrder);

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode(),
                "不能替别人的订单申请退款");
        verify(refundRecordMapper, never()).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    // 二、订单状态可退性（checkRefundable）

    /**
     * <p><b>为什么测它：</b>退款状态白名单只有 {已付款, 已发货, 已完成}。待付款/已取消/已关闭/已退款都无款可退。</p>
     * <p><b>关键断言：</b>四种状态都抛 3034 且消息含「不支持退款」；<b>never</b> 落库。</p>
     */

    @Test
    @DisplayName("[非法状态] 待付款/已取消/已关闭/已退款 均不可退 → BusinessException(3034)")
    void doApply_statusNotRefundable_shouldThrow() {
        int[] notRefundable = {
                OrderStatus.WAIT_PAY.getCode(),
                OrderStatus.CANCELLED.getCode(),
                OrderStatus.CLOSED.getCode(),
                OrderStatus.REFUNDED.getCode()
        };
        for (int status : notRefundable) {
            when(orderMapper.selectById(ORDER_ID)).thenReturn(orderOf(status, null));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> doApply(USER_ID, null, null),
                    "状态 " + status + " 不应允许退款");

            assertEquals(ResultCode.REFUND_NOT_ALLOWED.getCode(), ex.getCode());
            assertTrue(ex.getMessage().contains("不支持退款"), "实际：" + ex.getMessage());
        }
        verify(refundRecordMapper, never()).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b>已完成订单要受<b>售后窗口期</b>约束（这里是 15 天），超期不再受理。</p>
     * <p><b>关键断言：</b>抛 3035；消息里带出具体天数（用户要知道门槛）。</p>
     */

    @Test
    @DisplayName("[窗口期] 已完成订单超过 15 天 → BusinessException(3035)，消息含天数")
    void doApply_completedOrderBeyondWindow_shouldThrow() {
        when(refundProperties.getWindowDays()).thenReturn(WINDOW_DAYS);
        Order order = orderOf(OrderStatus.COMPLETED.getCode(), null);
        order.setCompleteTime(LocalDateTime.now().minusDays(WINDOW_DAYS + 1));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.REFUND_WINDOW_EXPIRED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains(String.valueOf(WINDOW_DAYS)),
                "消息应告诉用户具体天数，实际：" + ex.getMessage());
    }

    /**
     * <p><b>为什么测它：</b>边界正向：窗口期内的已完成订单应当可以申请（且已完成属「退货退款」，必须给退货物流）。</p>
     * <p><b>关键断言：</b>不抛异常，正常返回退款单。</p>
     */

    @Test
    @DisplayName("[窗口期] 已完成订单在 15 天内 → 允许申请（边界正向）")
    void doApply_completedOrderWithinWindow_shouldPass() {
        when(refundProperties.getWindowDays()).thenReturn(WINDOW_DAYS);
        Order order = orderOf(OrderStatus.COMPLETED.getCode(), null);
        order.setCompleteTime(LocalDateTime.now().minusDays(WINDOW_DAYS - 1));
        givenRefundableOrder(order);
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));

        RefundRecord result = doApply(USER_ID, COMPANY, TRACKING_NO);

        assertNotNull(result, "窗口期内应允许退款");
    }

    /**
     * <p><b>为什么测它：</b>锚点回落：completeTime 为空时要用 createTime —— <b>不能因为少了完成时间就当作「在窗口内」放行</b>。</p>
     * <p><b>关键断言：</b>抛 3035（因为 createTime 已超期）。</p>
     */

    @Test
    @DisplayName("[窗口期] 已完成订单 completeTime 为空 → 回落到 createTime 判定")
    void doApply_completedOrderFallsBackToCreateTime() {
        when(refundProperties.getWindowDays()).thenReturn(WINDOW_DAYS);
        Order order = orderOf(OrderStatus.COMPLETED.getCode(), null);
        order.setCompleteTime(null);
        order.setCreateTime(LocalDateTime.now().minusDays(WINDOW_DAYS + 5));  // 已超期
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.REFUND_WINDOW_EXPIRED.getCode(), ex.getCode(),
                "completeTime 为空时应回落到 createTime —— 不能因为少了完成时间就放行");
    }

    /**
     * <p><b>为什么测它：</b><b>fail-safe 方向</b>：两个时间锚点都为空是数据异常，此时必须<b>按超期拒绝</b>，绝不能跳过校验。删商品守卫的 fail-safe 方向相反（判不准就拦住删除），因为两处的安全侧不同。</p>
     * <p><b>关键断言：</b>抛 3035 且消息含「时间信息异常」。</p>
     */

    @Test
    @DisplayName("[窗口期·fail-safe] 已完成订单两个时间锚点全为空 → 按超期拒绝（不能跳过校验）")
    void doApply_completedOrderWithoutAnyAnchor_shouldRejectAsFailSafe() {
        Order order = orderOf(OrderStatus.COMPLETED.getCode(), null);
        order.setCompleteTime(null);
        order.setCreateTime(null);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.REFUND_WINDOW_EXPIRED.getCode(), ex.getCode(),
                "数据异常时必须 fail-safe：判不准就拒绝退款，绝不能默认放行");
        assertTrue(ex.getMessage().contains("时间信息异常"), "实际：" + ex.getMessage());
    }

    /**
     * <p><b>为什么测它：</b>窗口期<b>只对「已完成」生效</b>：已付款/已发货随时可退、无天数限制（退款入口 checkRefundable 的规则）。</p>
     * <p><b>关键断言：</b>两种状态即使订单很老也能正常申请。</p>
     */

    @Test
    @DisplayName("[窗口期] 已付款/已发货 不受窗口期限制（窗口期只对「已完成」生效）")
    void doApply_windowOnlyAppliesToCompleted_shouldPassForPaidAndShipped() {
        // 已付款：即使订单很老也能退
        Order paid = orderOf(OrderStatus.PAID.getCode(), LocalDateTime.now().minusDays(999));
        givenRefundableOrder(paid);
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));
        assertNotNull(doApply(USER_ID, null, null), "已付款订单不受窗口期限制");

        // 已发货：同理（但要提供退货物流）
        Order shipped = orderOf(OrderStatus.SHIPPED.getCode(), LocalDateTime.now().minusDays(999));
        givenRefundableOrder(shipped);
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));
        assertNotNull(doApply(USER_ID, COMPANY, TRACKING_NO), "已发货订单不受窗口期限制");
    }

    // 三、退款类型推导 + 退货物流必填

    /**
     * <p><b>为什么测它：</b>退货退款必须提供退货物流 —— 商家要知道「退回来的货现在在哪」。</p>
     * <p><b>关键断言：</b>抛 3040；<b>never</b> 落库。</p>
     */

    @Test
    @DisplayName("[入参错误] 已发货订单（退货退款类型）未填退货物流 → BusinessException(3040)，不落库")
    void doApply_returnRefundMissingLogistics_shouldThrow() {
        givenRefundableOrder(orderOf(OrderStatus.SHIPPED.getCode(), null));

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.REFUND_RETURN_INFO_MISSING.getCode(), ex.getCode(),
                "商家必须知道『退回来的货在哪』，所以退货退款必须给物流信息");
        verify(refundRecordMapper, never()).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b>边界：只填公司不填单号 / 只填单号不填公司 / 纯空白，都算缺失。</p>
     * <p><b>关键断言：</b>三种情况都抛异常。</p>
     */

    @Test
    @DisplayName("[入参错误] 只有公司没有单号（或反之）也算缺失 → 3040")
    void doApply_partialLogistics_shouldThrow() {
        givenRefundableOrder(orderOf(OrderStatus.SHIPPED.getCode(), null));

        assertThrows(BusinessException.class, () -> doApply(USER_ID, COMPANY, null),
                "只填公司不填单号应视为缺失");
        assertThrows(BusinessException.class, () -> doApply(USER_ID, null, TRACKING_NO),
                "只填单号不填公司应视为缺失");
        assertThrows(BusinessException.class, () -> doApply(USER_ID, "   ", "  "),
                "纯空白应视为缺失");
    }

    /**
     * <p><b>为什么测它：</b><b>退款类型由服务端按订单状态推导</b>，客户端无权指定：已付款（货未出库）→ 仅退款，不需要退货物流。</p>
     * <p><b>关键断言：</b>正常返回；落库时 <b>refundType = REFUND_ONLY(1)</b>。</p>
     */

    @Test
    @DisplayName("[业务正确] 已付款订单判定为「仅退款」，不需要退货物流")
    void doApply_paidOrderIsRefundOnly_noLogisticsRequired() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));

        RefundRecord result = doApply(USER_ID, null, null);

        assertNotNull(result, "已付款（货未出库）属于仅退款，不需要退货物流");
        // 断言落库时的 refundType = 1（仅退款）
        ArgumentCaptor<Integer> typeCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(refundRecordMapper).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), typeCaptor.capture(), any(), any());
        assertEquals(RefundType.REFUND_ONLY.getCode(), typeCaptor.getValue(),
                "退款类型必须由服务端按订单状态推导 —— 已付款 → 仅退款");
    }

    /**
     * <p><b>为什么测它：</b>已发货 → 退货退款；且物流信息要与列宽 varchar(64) 对齐，超长需截断否则严格模式插入失败。</p>
     * <p><b>关键断言：</b>refundType = RETURN_AND_REFUND(2)；物流公司名长度被截断为 64。</p>
     */

    @Test
    @DisplayName("[业务正确] 已发货订单判定为「退货退款」，且物流信息入库前被截断到 64 字符")
    void doApply_shippedOrderIsReturnAndRefund_logisticsTruncated() {
        givenRefundableOrder(orderOf(OrderStatus.SHIPPED.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));
        String longCompany = "超长物流公司名称".repeat(10);   // > 64 字符

        doApply(USER_ID, longCompany, TRACKING_NO);

        ArgumentCaptor<Integer> typeCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> companyCaptor = ArgumentCaptor.forClass(String.class);
        verify(refundRecordMapper).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), typeCaptor.capture(), companyCaptor.capture(), any());
        assertEquals(RefundType.RETURN_AND_REFUND.getCode(), typeCaptor.getValue(),
                "已发货 → 退货退款");
        assertEquals(64, companyCaptor.getValue().length(),
                "物流公司名应截断到 64 字符（与列宽 varchar(64) 对齐，避免严格模式插入失败）");
    }

    // 四、防重第 1 层（应用层友好提示）

    /**
     * <p><b>为什么测它：</b><b>防重第 1 层</b>：已有未完结退款单时给用户<b>友好提示</b>（含当前状态），避免无谓的写操作。</p>
     * <p><b>关键断言：</b>抛 3033 且消息含状态描述；<b>never</b> 落库（路径断言：根本没走到写操作）。</p>
     */

    @Test
    @DisplayName("[防重·第1层] 已有「待审核」退款单 → BusinessException(3033)，消息含状态，且【不落库】")
    void doApply_existingOpenRefund_shouldThrowWithStatusDesc() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(refundRecordMapper.selectOpenByOrderId(ORDER_ID))
                .thenReturn(refundOf("REF-OLD-1", RefundStatus.APPLYING.getCode()));

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.REFUND_REPEAT.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains(RefundStatus.APPLYING.getDesc()),
                "消息应带上当前退款单状态，实际：" + ex.getMessage());
        // 路径断言：第 1 层就拦下了，根本不该走到写操作
        verify(refundRecordMapper, never()).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b>对「退款失败」的单子要给<b>原退款单号</b>并引导联系客服 —— 因为用户自己重试也没用，得等管理员处理。</p>
     * <p><b>关键断言：</b>抛 3033 且消息含原退单号；<b>never</b> 落库。</p>
     */

    @Test
    @DisplayName("[防重·第1层] 已有「退款失败」退款单 → 3033，且消息【带上原退款单号】引导用户找客服")
    void doApply_existingFailedRefund_shouldThrowWithRefundNo() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(refundRecordMapper.selectOpenByOrderId(ORDER_ID))
                .thenReturn(refundOf("REF-FAILED-9", RefundStatus.FAILED.getCode()));

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.REFUND_REPEAT.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("REF-FAILED-9"),
                "「退款失败」要给用户原单号，方便联系客服，实际：" + ex.getMessage());
        verify(refundRecordMapper, never()).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    // 五、支付成功流水与金额

    /**
     * <p><b>为什么测它：</b>没有支付成功流水就说明没收过钱，不该有退款（也拿不到权威金额）。</p>
     * <p><b>关键断言：</b>抛 3038；<b>never</b> 落库。</p>
     */

    @Test
    @DisplayName("[非法状态] 没有支付成功流水 → BusinessException(3038)，不落库")
    void doApply_noPaidRecord_shouldThrow() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.REFUND_PAY_NOT_FOUND.getCode(), ex.getCode(),
                "没有收过钱就不该有退款");
        verify(refundRecordMapper, never()).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b>边界：金额为 0 的退款没有意义，属数据异常。</p>
     * <p><b>关键断言：</b>抛 3032。</p>
     */

    @Test
    @DisplayName("[边界] 支付流水金额为 0 → BusinessException(3032)，不落库")
    void doApply_zeroAmount_shouldThrow() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(BigDecimal.ZERO));

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.REFUND_AMOUNT_ERROR.getCode(), ex.getCode());
    }

    /**
     * <p><b>为什么测它：</b><b>资金安全核心</b>：退款金额一律取支付流水的 amount，<b>绝不由客户端传参决定</b>（否则可申请退比实付更多的钱）。</p>
     * <p><b>关键断言：</b>落库金额 = 支付流水的 88.88。</p>
     */

    @Test
    @DisplayName("[资金安全] 退款金额取自支付流水的 amount，【不由客户端传参决定】")
    void doApply_refundAmountComesFromPayRecord() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        BigDecimal paid = new BigDecimal("88.88");
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(paid));

        doApply(USER_ID, null, null);

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(refundRecordMapper).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), amountCaptor.capture(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any());
        assertEquals(paid, amountCaptor.getValue(),
                "退款金额必须等于支付流水金额 —— 否则用户可以申请退比实付更多的钱");
    }

    // 六、落库与状态推导（含防重第 2/3 层）

    /**
     * <p><b>为什么测它：</b>小额 + 已付款命中<b>自动审核</b>，直接进入「退款中」，省去人工审核。</p>
     * <p><b>关键断言：</b>落库状态 = REFUNDING(1)。</p>
     */

    @Test
    @DisplayName("[业务正确] 小额 + 已付款 → 命中自动审核，落库状态为「退款中(1)」")
    void doApply_autoApproveHit_shouldInsertAsRefunding() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));
        // 开启自动审核，阈值 500（订单 199 未超）
        when(refundProperties.autoAuditEnabled()).thenReturn(true);
        when(refundProperties.getAutoAuditAmount()).thenReturn(new BigDecimal("500.00"));

        doApply(USER_ID, null, null);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(refundRecordMapper).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), statusCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertEquals(RefundStatus.REFUNDING.getCode(), statusCaptor.getValue(),
                "小额自动审核命中 → 直接进入退款中，省去人工审核");
    }

    /**
     * <p><b>为什么测它：</b><b>已发货涉及退货，必须人工把关</b>，即使金额很小也不能自动放行。</p>
     * <p><b>关键断言：</b>落库状态 = APPLYING(0)；<b>注意本用例刻意不 stub 金额阈值</b> —— 因为 autoApprove 在读阈值之前就已 return false，不 stub 恰好证明「拦截发生在读阈值之前」。</p>
     */

    @Test
    @DisplayName("[业务正确] 已发货订单即使金额很小也【不】自动放行 → 落库状态为「待审核(0)」")
    void doApply_shippedOrderNeverAutoApproved() {
        givenRefundableOrder(orderOf(OrderStatus.SHIPPED.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));
        // 只 stub「开启自动审核」——已发货订单在 autoApprove 的第二个判断
        // (status != PAID) 就被拦下了，根本走不到读金额阈值那一步。
        // 这里不 stub 阈值，正好反过来证明「拦截发生在读阈值之前」。
        when(refundProperties.autoAuditEnabled()).thenReturn(true);

        doApply(USER_ID, COMPANY, TRACKING_NO);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(refundRecordMapper).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), statusCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertEquals(RefundStatus.APPLYING.getCode(), statusCaptor.getValue(),
                "已发货涉及退货，必须人工把关，不能自动放行");
    }

    /**
     * <p><b>为什么测它：</b>金额超过自动审核阈值时必须走人工。</p>
     * <p><b>关键断言：</b>落库状态 = APPLYING(0)。</p>
     */

    @Test
    @DisplayName("[业务正确] 金额超过自动审核阈值 → 落库状态为「待审核(0)」")
    void doApply_amountAboveThreshold_shouldNotAutoApprove() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));
        when(refundProperties.autoAuditEnabled()).thenReturn(true);
        when(refundProperties.getAutoAuditAmount()).thenReturn(new BigDecimal("100.00"));  // < 199

        doApply(USER_ID, null, null);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(refundRecordMapper).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), statusCaptor.capture(), anyInt(), anyInt(), any(), any());
        assertEquals(RefundStatus.APPLYING.getCode(), statusCaptor.getValue());
    }

    /**
     * <p><b>为什么测它：</b><b>防重第 3 层</b>：并发下另一个请求已插入未完结退款单，数据库唯一索引 uk_open_order 会拦下 —— 要把这个底层异常翻译成用户能懂的话。</p>
     * <p><b>关键断言：</b>抛 3033 且消息含「请勿重复申请」；<b>verify 落库方法被调用过</b>（与第 1 层用例的 never 形成对照证据）。</p>
     */

    @Test
    @DisplayName("[防重·第3层] 落库时被唯一索引拦下（DuplicateKeyException）→ 转成 3033")
    void doApply_duplicateKeyFromUniqueIndex_shouldConvertToRepeat() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));
        when(refundRecordMapper.insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new DuplicateKeyException("Duplicate entry '123' for key 'uk_open_order'"));

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.REFUND_REPEAT.getCode(), ex.getCode(),
                "并发下另一个请求已插入未完结退款单 —— 唯一索引是最后一道闸门");
        assertTrue(ex.getMessage().contains("请勿重复申请"), "实际：" + ex.getMessage());
        // 路径断言：这次是【第 3 层】拦下的，与第 1 层用例的 never() 形成对照
        verify(refundRecordMapper).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    /**
     * <p><b>为什么测它：</b><b>防重第 2 层</b>：可退条件与 version 都写进 SQL 的 WHERE，影响 0 行说明订单在查询之后被改过。</p>
     * <p><b>关键断言：</b>抛 3031 且消息含「状态已变更」。</p>
     */

    @Test
    @DisplayName("[防重·第2层] 条件插入影响 0 行（订单状态被改过）→ BusinessException(3031)")
    void doApply_conditionalInsertAffectedZeroRows_shouldThrow() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));
        when(refundRecordMapper.insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any()))
                .thenReturn(0);   // 订单状态或 version 在查询之后被改掉

        BusinessException ex = assertThrows(BusinessException.class, () -> doApply(USER_ID, null, null));

        assertEquals(ResultCode.REFUND_STATUS_ERROR.getCode(), ex.getCode(),
                "SQL 的 WHERE 里带了订单可退条件 + version，影响 0 行说明订单已变更");
        assertTrue(ex.getMessage().contains("状态已变更"), "实际：" + ex.getMessage());
    }

    /**
     * <p><b>为什么测它：</b>退款单号格式固定（REF + 订单号 + - + 时间戳），且最终要按号查回并返回给调用方。</p>
     * <p><b>关键断言：</b>单号前缀正确；<b>verify selectByRefundNo 用该单号查回</b>；返回不为 null。</p>
     */

    @Test
    @DisplayName("[业务正确] 成功落库 → 退款单号规则正确，且最终查回并返回该记录")
    void doApply_success_shouldGenerateRefundNoAndReturnRecord() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));

        RefundRecord result = doApply(USER_ID, null, null);

        // refundNo 应形如 REF + 订单号 + - + 时间戳
        ArgumentCaptor<String> noCaptor = ArgumentCaptor.forClass(String.class);
        verify(refundRecordMapper).insertRefundIfOrderRefundable(
                noCaptor.capture(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any());
        assertTrue(noCaptor.getValue().startsWith("REF" + ORDER_NO + "-"),
                "退款单号应为 REF+订单号+-+时间戳，实际：" + noCaptor.getValue());

        // 最终用 refundNo 查回
        verify(refundRecordMapper).selectByRefundNo(noCaptor.getValue());
        assertNotNull(result, "应返回查回的退款单");
    }

    /**
     * <p><b>为什么测它：</b>边界：退款原因要规整 —— 空/空白落默认文案（避免库里出现语义空白），超长按配置截断。</p>
     * <p><b>关键断言：</b>空原因落「用户申请退款」；超长原因长度恰好 200。</p>
     */

    @Test
    @DisplayName("[业务正确] 退款原因规整：空/空白 → 默认文案；超长 → 截断到配置长度")
    void doApply_reasonNormalization() {
        givenRefundableOrder(orderOf(OrderStatus.PAID.getCode(), null));
        when(payRecordMapper.selectPaidByOrderId(ORDER_ID)).thenReturn(paidRecordOf(PAY_AMOUNT));
        when(refundProperties.getMaxReasonLength()).thenReturn(200);

        // 1) 空原因 → 默认文案（直接调被测方法传入 null reason，不能用 helper）
        refundTxService.doApply(USER_ID, ORDER_ID, null, null, null);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(refundRecordMapper).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                reasonCaptor.capture(), anyInt(), anyInt(), anyInt(), any(), any());
        assertEquals("用户申请退款", reasonCaptor.getValue(), "空原因应落默认文案，避免库里出现 NULL 语义空白");

        // 2) 超长原因 → 截断
        String longReason = "很长的退款原因".repeat(50);
        refundTxService.doApply(USER_ID, ORDER_ID, longReason, null, null);
        ArgumentCaptor<String> reasonCaptor2 = ArgumentCaptor.forClass(String.class);
        verify(refundRecordMapper, times(2)).insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                reasonCaptor2.capture(), anyInt(), anyInt(), anyInt(), any(), any());
        assertEquals(200, reasonCaptor2.getValue().length(), "超长原因应截断到 maxReasonLength");
    }

    // 助手

    /** 调用被测方法（减少重复噪声） */
    private RefundRecord doApply(Long userId, String company, String trackingNo) {
        return refundTxService.doApply(userId, ORDER_ID, "不想要了", company, trackingNo);
    }

    private Order orderOf(int status, LocalDateTime completeTime) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setPayAmount(PAY_AMOUNT);
        order.setStatus(status);
        order.setVersion(0);
        order.setCreateTime(LocalDateTime.now().minusDays(1));
        order.setCompleteTime(completeTime);
        return order;
    }

    private PayRecord paidRecordOf(BigDecimal amount) {
        PayRecord record = new PayRecord();
        record.setOutTradeNo(OUT_TRADE_NO);
        record.setOrderId(ORDER_ID);
        record.setAmount(amount);
        record.setStatus(PayStatus.SUCCESS.getCode());
        return record;
    }

    private RefundRecord refundOf(String refundNo, int status) {
        RefundRecord record = new RefundRecord();
        record.setId(1L);
        record.setRefundNo(refundNo);
        record.setOrderId(ORDER_ID);
        record.setStatus(status);
        return record;
    }

    /**
     * stub：一张可退订单（属于当前用户）+ 无历史未完结退款单 + 落库成功 + 查回成功。
     *
     * <p>注意这里只 stub「几乎所有路径都会用到」的部分；
     * 各用例特有的 stub（支付流水、自动审核配置等）由用例自己声明 ——
     * 这样 Mockito 严格模式才能检查出「stub 了却没走到」的路径错误。</p>
     */
    private void givenRefundableOrder(Order order) {
        // 说明：这里用 lenient() 是有意为之 —— doApply 的校验链很深，
        // 不同用例会走到不同的深度，共用脚手架的 stub 必然有一部分用不到。
        // 严格性由两部分保证：① 用例自身的 stub 仍是严格的（能查出路径走错）
        // ② 用例末尾的 verify(..., never()) 路径断言。
        lenient().when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        lenient().when(refundRecordMapper.selectOpenByOrderId(ORDER_ID)).thenReturn(null);
        lenient().when(mockPayProperties.getChannel()).thenReturn(CHANNEL);
        lenient().when(refundRecordMapper.insertRefundIfOrderRefundable(
                anyString(), anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), any()))
                .thenReturn(1);
        lenient().when(refundRecordMapper.selectByRefundNo(anyString()))
                .thenAnswer(inv -> refundOf(inv.getArgument(0), RefundStatus.APPLYING.getCode()));
    }

}
