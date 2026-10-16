package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.config.OrderRiskProperties;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.OrderItemMapper;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.OrderOperationLogMapper;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.mapper.ProductMapper;
import com.zhou.peakflow.mapper.RefundRecordMapper;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.pojo.RefundRecord;
import com.zhou.peakflow.service.AddressService;
import com.zhou.peakflow.service.ProductService;
import com.zhou.peakflow.service.SeckillOrderService;
import com.zhou.peakflow.utils.OrderNoGenerator;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认收货 <b>单元测试</b>：{@code OrderServiceImpl#confirmReceipt(userId, orderId)}（@Transactional）。
 *
 * <p>流程：getById（null → 3003）→ 归属校验（→ 3007）→ status != SHIPPED 则抛错 →
 * orderMapper.completeReceiptIfNoRefund(orderId, version)（一条 SQL 里塞了 status=2、version=?、
 * NOT EXISTS(未完结退款单) 三个条件）→ CAS 影响 0 行时区分两种原因给出不同提示
 * （有未完结退款单 → "请先处理完退款"；否则 → "订单状态已变更"）。</p>
 *
 * <p>为什么三个条件必须挤进同一条 SQL：拆成三步会出现竞态窗口（查的时候没有退款单、更新前用户刚好发起退款），
 * 结果"退款没处理完货却被确认收货"，退款单卡在矛盾状态。写进同一条 UPDATE 的 WHERE 后判定与修改原子化，
 * 影响行数为 0 恰好说明有某个条件不满足 —— 这也是 CAS 失败后要查退款单、把原因告诉用户的意义。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("确认收货单元测试 OrderServiceImpl#confirmReceipt")
class OrderServiceImplConfirmReceiptTest {

    // OrderServiceImpl 的全部构造依赖
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private ProductService productService;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private AddressService addressService;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private OrderNoGenerator orderNoGenerator;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private OrderOperationLogMapper orderOperationLogMapper;
    @Mock
    private PayRecordMapper payRecordMapper;
    @Mock
    private RefundRecordMapper refundRecordMapper;
    @Mock
    private OrderRiskProperties orderRiskProperties;
    @Mock
    private SeckillOrderService seckillOrderService;

    @InjectMocks
    private OrderServiceImpl orderService;

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER_ID = 9999L;
    private static final Long ORDER_ID = 2001L;
    private static final int VERSION = 3;

    /** 同 OrderServiceImplCancelTest：this.getById() 读的是 ServiceImpl.baseMapper，需手动注入 */
    @BeforeEach
    void injectMybatisPlusBaseMapper() {
        ReflectionTestUtils.setField(orderService, "baseMapper", orderMapper);
    }

    // 一、三道前置校验

    /**
     * <p><b>为什么测它：</b>订单不存在时必须第一步拦住，不能带着 null 去执行三合一 CAS。</p>
     * <p><b>关键断言：</b>抛 3003；<b>never</b> 调用 completeReceiptIfNoRefund。</p>
     */

    @Test
    @DisplayName("[非法输入] 订单不存在 → 3003，且【不执行 CAS 更新】")
    void confirmReceipt_orderNotFound_shouldThrow() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirmReceipt(USER_ID, ORDER_ID));

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
        verify(orderMapper, never()).completeReceiptIfNoRefund(anyLong(), anyInt());
    }

    /**
     * <p><b>为什么测它：</b>否则可以替别人确认收货，属于越权。</p>
     * <p><b>关键断言：</b>抛 3007；CAS 未被调用。</p>
     */

    @Test
    @DisplayName("[越权防护] 不是自己的订单 → 3007，且不执行 CAS 更新")
    void confirmReceipt_orderBelongsToAnotherUser_shouldThrow() {
        Order others = orderOf(OrderStatus.SHIPPED.getCode());
        others.setUserId(OTHER_USER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(others);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirmReceipt(USER_ID, ORDER_ID));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(orderMapper, never()).completeReceiptIfNoRefund(anyLong(), anyInt());
    }

    /**
     * <p><b>为什么测它：</b>只有「已发货」能确认收货。未发货就确认等于<b>没收到货却说收到了</b>；已完成再确认是重复操作。</p>
     * <p><b>关键断言：</b>六种非「已发货」状态都抛异常且提示含「已发货」；CAS 未被调用。</p>
     */

    @Test
    @DisplayName("[非法状态] 只有「已发货」能确认收货，其余状态一律拒绝")
    void confirmReceipt_statusNotShipped_shouldThrow() {
        int[] notShipped = {
                OrderStatus.WAIT_PAY.getCode(),
                OrderStatus.PAID.getCode(),
                OrderStatus.COMPLETED.getCode(),   // 已经确认过了
                OrderStatus.CANCELLED.getCode(),
                OrderStatus.CLOSED.getCode(),
                OrderStatus.REFUNDED.getCode()
        };
        for (int status : notShipped) {
            when(orderMapper.selectById(ORDER_ID)).thenReturn(orderOf(status));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.confirmReceipt(USER_ID, ORDER_ID),
                    "状态 " + status + " 不应允许确认收货");

            assertTrue(ex.getMessage().contains("已发货"), "实际：" + ex.getMessage());
        }
        verify(orderMapper, never()).completeReceiptIfNoRefund(anyLong(), anyInt());
    }

    // 二、正常确认（三条件 CAS 成功）

    /**
     * <p><b>为什么测它：</b>必须把订单 <b>version 传进 CAS</b> —— 否则乐观锁形同虚设，并发下会覆盖别人的修改。</p>
     * <p><b>关键断言：</b>不抛异常；completeReceiptIfNoRefund 收到的 version 与订单一致；成功路径<b>不该去查退款单</b>。</p>
     */

    @Test
    @DisplayName("[业务正确] 已发货订单 → 调用三合一 CAS，并把订单版本一并传入")
    void confirmReceipt_shippedOrder_shouldCallCasWithVersion() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(orderOf(OrderStatus.SHIPPED.getCode()));
        when(orderMapper.completeReceiptIfNoRefund(ORDER_ID, VERSION)).thenReturn(1);

        assertDoesNotThrow(() -> orderService.confirmReceipt(USER_ID, ORDER_ID),
                "三条件 CAS 命中时应正常返回（不抛异常）");

        // 断言把订单的 version 传进了 CAS —— 乐观锁必须带版本，否则形同虚设
        verify(orderMapper).completeReceiptIfNoRefund(ORDER_ID, VERSION);
        // 成功路径不该去查退款单（那是失败后的诊断动作）
        verify(refundRecordMapper, never()).selectOpenByOrderId(anyLong());
    }

    // 三、CAS 失败的两种原因（本类最值得看的部分）

    /**
     * <p><b>为什么测它：</b>CAS 影响 0 行有<b>两种原因</b>。若统统一句「操作失败」，用户不知道该怎么办。有未完结退款时应明确告诉他去处理退款。</p>
     * <p><b>关键断言：</b>抛异常且提示含「进行中的退款单」；<b>verify</b> 确实走了「查退款单」这个诊断分支。</p>
     */

    @Test
    @DisplayName("[CAS 失败·有退款] 存在进行中的退款单 → 提示「请先处理完退款」（而不是笼统的『操作失败』）")
    void confirmReceipt_casZeroRowsWithOpenRefund_shouldExplainRefund() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(orderOf(OrderStatus.SHIPPED.getCode()));
        when(orderMapper.completeReceiptIfNoRefund(ORDER_ID, VERSION)).thenReturn(0);
        // 诊断：确实有一笔未完结的退款单
        RefundRecord open = new RefundRecord();
        open.setId(1L);
        open.setRefundNo("REF-X");
        open.setStatus(RefundStatus.REFUNDING.getCode());
        when(refundRecordMapper.selectOpenByOrderId(ORDER_ID)).thenReturn(open);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirmReceipt(USER_ID, ORDER_ID));

        assertTrue(ex.getMessage().contains("进行中的退款单"),
                "CAS 失败后必须去查退款单，把真正的原因告诉用户。实际：" + ex.getMessage());
        // 路径断言：确实走了"查退款单"这个诊断分支
        verify(refundRecordMapper).selectOpenByOrderId(ORDER_ID);
    }

    /**
     * <p><b>为什么测它：</b>没有退款单时，剩下的唯一解释就是「状态/版本被并发改过」，提示应引导用户刷新重试。</p>
     * <p><b>关键断言：</b>抛异常且提示含「状态已变更」；诊断分支确实被走过。</p>
     */

    @Test
    @DisplayName("[CAS 失败·无退款] 没有退款单 → 提示「订单状态已变更」")
    void confirmReceipt_casZeroRowsWithoutRefund_shouldReportStatusChanged() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(orderOf(OrderStatus.SHIPPED.getCode()));
        when(orderMapper.completeReceiptIfNoRefund(ORDER_ID, VERSION)).thenReturn(0);
        when(refundRecordMapper.selectOpenByOrderId(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirmReceipt(USER_ID, ORDER_ID));

        assertTrue(ex.getMessage().contains("状态已变更"),
                "没有退款单，那就只剩「状态/版本被并发改过」这一种解释。实际：" + ex.getMessage());
        verify(refundRecordMapper).selectOpenByOrderId(ORDER_ID);
    }

    /**
     * <p><b>为什么测它：</b><b>对照用例</b>：直接断言两种失败的提示语<b>必须不同</b> —— 这是证明「诊断分支真的在起作用」而不是摆设的最强证据。</p>
     * <p><b>关键断言：</b>两次异常消息不相等。</p>
     */

    @Test
    @DisplayName("[对照] 两个 CAS 失败用例的提示语【必须不同】—— 证明诊断分支真的在起作用")
    void confirmReceipt_twoFailureReasons_shouldProduceDifferentMessages() {
        // 有退款单
        when(orderMapper.selectById(ORDER_ID)).thenReturn(orderOf(OrderStatus.SHIPPED.getCode()));
        when(orderMapper.completeReceiptIfNoRefund(ORDER_ID, VERSION)).thenReturn(0);
        RefundRecord open = new RefundRecord();
        open.setRefundNo("REF-Y");
        open.setStatus(RefundStatus.APPLYING.getCode());
        when(refundRecordMapper.selectOpenByOrderId(ORDER_ID)).thenReturn(open);
        String msgWithRefund = assertThrows(BusinessException.class,
                () -> orderService.confirmReceipt(USER_ID, ORDER_ID)).getMessage();

        // 无退款单
        when(refundRecordMapper.selectOpenByOrderId(ORDER_ID)).thenReturn(null);
        String msgWithoutRefund = assertThrows(BusinessException.class,
                () -> orderService.confirmReceipt(USER_ID, ORDER_ID)).getMessage();

        assertTrue(!msgWithRefund.equals(msgWithoutRefund),
                "两种失败原因必须给出不同提示，否则用户无从判断该做什么");
    }

    // 助手

    private Order orderOf(int status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("ORD2026101400001");
        order.setUserId(USER_ID);
        order.setStatus(status);
        order.setVersion(VERSION);
        order.setPayAmount(new BigDecimal("199.00"));
        return order;
    }
}
