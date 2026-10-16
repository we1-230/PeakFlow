package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.config.OrderRiskProperties;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.enums.SeckillFlag;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.utils.OrderNoGenerator;
import com.zhou.peakflow.mapper.OrderItemMapper;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.OrderOperationLogMapper;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.mapper.ProductMapper;
import com.zhou.peakflow.mapper.RefundRecordMapper;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.pojo.OrderItem;
import com.zhou.peakflow.service.AddressService;
import com.zhou.peakflow.service.ProductService;
import com.zhou.peakflow.service.SeckillOrderService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取消订单 <b>单元测试</b>：{@code OrderServiceImpl#cancelOrder(userId, orderId)}（@Transactional）。
 *
 * <p>流程：getById（null → 3003）→ 归属校验（→ 3007）→ canCancel 只允许待付款 →
 * cancelOrder(orderId, version) CAS（影响 0 行 → "订单状态已变更，请刷新重试"）→
 * rollbackStock：普通订单 productMapper.rollbackStock，秒杀订单 rollbackSeckillStockByQuantity +
 * seckillOrderService.rollbackRedisStock → closePayRecord。</p>
 *
 * <p>两个设计：① 秒杀订单回补必须 DB + Redis 双写 —— 只回补 DB 会造成库存漂移，可抢名额越用越少（少卖）；
 * ② 关闭支付单失败不影响取消结果（内部 try-catch 吞掉），它只是防止"订单已取消却还能付款"的补丁。</p>
 *
 * <p>易忽略点：库存回补发生在 CAS 成功之后，CAS 影响 0 行（状态被并发改了）时绝不能回补，
 * 否则凭空多出库存（超卖）。本类专门有用例守住这条。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("取消订单单元测试 OrderServiceImpl#cancelOrder")
class OrderServiceImplCancelTest {

    // OrderServiceImpl 的全部构造依赖（@RequiredArgsConstructor，13 个）
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

    /**
     * MyBatis-Plus 单元测试的经典坑（本类踩过）：
     * {@code cancelOrder} 第一行用的是 {@code this.getById(orderId)} —— 这是 IService 的默认方法，
     * 它内部读的是 {@code ServiceImpl.baseMapper} 字段，而 @InjectMocks 注入的是
     * <b>构造器参数</b> orderMapper。两者是不同字段，后者被注入不影响前者，
     * 于是 {@code getBaseMapper()} 返回 null，直接 NPE。
     * <p>解决办法：用反射把 baseMapper 也设成同一个 mock（不改业务代码，只补测试侧注入）。</p>
     */
    @BeforeEach
    void injectMybatisPlusBaseMapper() {
        ReflectionTestUtils.setField(orderService, "baseMapper", orderMapper);
    }

    // 常量

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER_ID = 9999L;
    private static final Long ORDER_ID = 2001L;
    private static final Long PRODUCT_A = 3001L;
    private static final Long PRODUCT_B = 3002L;
    private static final int VERSION = 0;

    // 一、三道校验

    /**
     * <p><b>为什么测它：</b>订单不存在时若还继续往下走，会拿着 null 去做 CAS 与回补，必须<b>在第一步就拦住</b>。</p>
     * <p><b>关键断言：</b>抛 3003；且库存回补三个入口（普通/秒杀/Redis）<b>一个都不能被碰</b>，支付单也不能被关。</p>
     */

    @Test
    @DisplayName("[非法输入] 订单不存在 → BusinessException(3003)，且【不回补库存、不关支付单】")
    void cancelOrder_orderNotFound_shouldThrow() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancelOrder(USER_ID, ORDER_ID));

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
        verifyNoStockRollback();
        verify(payRecordMapper, never()).closeByOrderId(anyLong());
    }

    /**
     * <p><b>为什么测它：</b>否则任何人都能拿别人的 orderId 把订单取消掉，属于<b>越权操作</b>。</p>
     * <p><b>关键断言：</b>抛 3007；CAS 更新与库存回补都没发生。</p>
     */

    @Test
    @DisplayName("[越权防护] 订单不属于当前用户 → BusinessException(3007)，不取消")
    void cancelOrder_orderBelongsToAnotherUser_shouldThrow() {
        Order others = orderOf(OrderStatus.WAIT_PAY.getCode(), SeckillFlag.NO.getCode());
        others.setUserId(OTHER_USER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(others);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancelOrder(USER_ID, ORDER_ID));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(orderMapper, never()).cancelOrder(anyLong(), anyInt());
        verifyNoStockRollback();
    }

    /**
     * <p><b>为什么测它：</b>只有「待付款」能取消。已付款/已发货/已完成若被取消，会造成<b>钱货两空</b>；已取消/已关闭/已退款重复取消也应幂等拒绝。</p>
     * <p><b>关键断言：</b>六种状态都抛异常；提示语里带上<b>当前状态的中文描述</b>（前端可直接展示）；CAS 与回补都没发生。</p>
     */

    @Test
    @DisplayName("[非法状态] 只有「待付款」能取消；已付款/已发货/已完成/已取消 都会被拒（消息带状态描述）")
    void cancelOrder_statusNotCancellable_shouldThrow() {
        int[] notCancellable = {
                OrderStatus.PAID.getCode(),
                OrderStatus.SHIPPED.getCode(),
                OrderStatus.COMPLETED.getCode(),
                OrderStatus.CANCELLED.getCode(),
                OrderStatus.CLOSED.getCode(),
                OrderStatus.REFUNDED.getCode()
        };
        for (int status : notCancellable) {
            when(orderMapper.selectById(ORDER_ID))
                    .thenReturn(orderOf(status, SeckillFlag.NO.getCode()));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.cancelOrder(USER_ID, ORDER_ID),
                    "状态 " + status + " 不应允许取消");

            assertTrue(ex.getMessage().contains("不允许取消"), "实际：" + ex.getMessage());
            assertTrue(ex.getMessage().contains(OrderStatus.fromCode(status).getDesc()),
                    "消息应带当前状态的中文描述，实际：" + ex.getMessage());
        }
        verify(orderMapper, never()).cancelOrder(anyLong(), anyInt());
        verifyNoStockRollback();
    }

    // 二、CAS 失败（乐观锁 / 状态已变）

    /**
     * <p><b>为什么测它：</b><b>本类最重要的一条</b>：库存回补发生在 CAS 成功<b>之后</b>。若 CAS 影响 0 行却仍然回补，就会<b>凭空多出库存 → 超卖</b>。</p>
     * <p><b>关键断言：</b>抛异常且提示带「状态已变更」；<b>never</b> 查订单明细、<b>never</b> 回补任何库存、<b>never</b> 关支付单。</p>
     */

    @Test
    @DisplayName("[并发防护] CAS 影响 0 行 → 抛异常，且【绝不回补库存】（否则凭空多出库存）")
    void cancelOrder_casZeroRows_shouldThrowAndNotRollbackStock() {
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(orderOf(OrderStatus.WAIT_PAY.getCode(), SeckillFlag.NO.getCode()));
        when(orderMapper.cancelOrder(ORDER_ID, VERSION)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancelOrder(USER_ID, ORDER_ID));

        assertTrue(ex.getMessage().contains("状态已变更"), "实际：" + ex.getMessage());
        // 核心路径断言：CAS 没成功 ⇒ 库存回补与关单都必须跳过
        verify(orderItemMapper, never()).selectByOrderId(anyLong());
        verifyNoStockRollback();
        verify(payRecordMapper, never()).closeByOrderId(anyLong());
    }

    // 三、正常取消（含库存回补与关单）

    /**
     * <p><b>为什么测它：</b>正常取消的完整副作用链：CAS 成功 + 回补普通库存 + 关闭待支付单，<b>三者缺一都是资损</b>。</p>
     * <p><b>关键断言：</b>CAS 带的 version 正确；回补数量与明细一致；支付单被关闭。</p>
     */

    @Test
    @DisplayName("[业务正确] 普通订单取消 → CAS 成功 + 回补普通库存 + 关闭待支付单")
    void cancelOrder_normalOrder_shouldRollbackStockAndClosePayRecord() {
        givenCancellableOrder(SeckillFlag.NO.getCode(), itemsOf(item(PRODUCT_A, 2)));

        assertDoesNotThrow(() -> orderService.cancelOrder(USER_ID, ORDER_ID),
                "正常取消不应抛异常（本用例要验证的是『取消成功后的回补与关单』）");

        // CAS 带上订单版本（乐观锁）
        verify(orderMapper).cancelOrder(ORDER_ID, VERSION);
        // 回补普通库存，数量与明细一致
        verify(productMapper).rollbackStock(PRODUCT_A, 2);
        // 同步关闭待支付单，防止"订单已取消却还能付款"
        verify(payRecordMapper).closeByOrderId(ORDER_ID);
    }

    /**
     * <p><b>为什么测它：</b>一个订单可能买多件商品，<b>只回补第一个商品是最容易犯的错</b>（多商品少回补 ⇒ 少卖）。</p>
     * <p><b>关键断言：</b>两个商品各被回补一次，且回补方法<b>恰好调用 2 次</b>。</p>
     */

    @Test
    @DisplayName("[业务正确] 多商品订单 → 【每个商品都要回补】，不能只回补第一个")
    void cancelOrder_multiItems_shouldRollbackEveryProduct() {
        givenCancellableOrder(SeckillFlag.NO.getCode(),
                itemsOf(item(PRODUCT_A, 2), item(PRODUCT_B, 3)));

        assertDoesNotThrow(() -> orderService.cancelOrder(USER_ID, ORDER_ID),
                "正常取消不应抛异常（本用例要验证的是『取消成功后的回补与关单』）");

        verify(productMapper).rollbackStock(PRODUCT_A, 2);
        verify(productMapper).rollbackStock(PRODUCT_B, 3);
        verify(productMapper, times(2)).rollbackStock(anyLong(), anyInt());
    }

    /**
     * <p><b>为什么测它：</b>秒杀库存在 Redis 做过预扣。只回补 DB 不回补 Redis ⇒ <b>库存漂移</b> ⇒ 可抢名额越用越少（少卖）。</p>
     * <p><b>关键断言：</b>同时回补 DB 秒杀库存与 <b>Redis 预扣</b>；且普通库存回补方法<b>不能被调用</b>（两个互斥分支）。</p>
     */

    @Test
    @DisplayName("[业务正确·秒杀] 秒杀订单取消 → 回补秒杀库存【并且同步回补 Redis 预扣】")
    void cancelOrder_seckillOrder_shouldAlsoRollbackRedisStock() {
        givenCancellableOrder(SeckillFlag.YES.getCode(), itemsOf(item(PRODUCT_A, 1)));

        assertDoesNotThrow(() -> orderService.cancelOrder(USER_ID, ORDER_ID),
                "正常取消不应抛异常（本用例要验证的是『取消成功后的回补与关单』）");

        // DB 侧回补
        verify(productMapper).rollbackSeckillStockByQuantity(PRODUCT_A, 1);
        // Redis 侧也必须回补，否则"DB 涨了、Redis 没涨"⇒ 可抢名额越用越少
        verify(seckillOrderService).rollbackRedisStock(PRODUCT_A, 1);
        // 普通库存回补方法不该被调用（两者是互斥分支）
        verify(productMapper, never()).rollbackStock(anyLong(), anyInt());
    }

    /**
     * <p><b>为什么测它：</b>数据异常（订单没有明细）时，回补逻辑要能安全跳过，<b>但不能因此让取消失败</b>。</p>
     * <p><b>关键断言：</b>不回补任何库存；取消本身仍然成功、支付单照样关闭。</p>
     */

    @Test
    @DisplayName("[边界] 订单没有明细（数据异常）→ 跳过回补，但不阻断取消流程")
    void cancelOrder_emptyItems_shouldSkipRollbackWithoutFailing() {
        givenCancellableOrder(SeckillFlag.NO.getCode(), Collections.emptyList());

        assertDoesNotThrow(() -> orderService.cancelOrder(USER_ID, ORDER_ID),
                "正常取消不应抛异常（本用例要验证的是『取消成功后的回补与关单』）");

        verifyNoStockRollback();
        // 取消本身仍然成功，支付单照样关闭
        verify(payRecordMapper).closeByOrderId(ORDER_ID);
    }

    // 四、补偿动作的容错（不能因为补偿失败而回滚主业务）

    /**
     * <p><b>为什么测它：</b>关支付单只是「防止订单已取消却还能付款」的<b>补偿动作</b>。补偿失败不该回滚主业务——否则用户永远取消不了订单。</p>
     * <p><b>关键断言：</b>不抛异常；CAS 与库存回补<b>都已正常完成</b>。</p>
     */

    @Test
    @DisplayName("[容错] 关闭支付单抛异常 → 被吞掉，取消仍然成功（补偿失败不回滚主业务）")
    void cancelOrder_closePayRecordThrows_shouldNotBreakCancel() {
        givenCancellableOrder(SeckillFlag.NO.getCode(), itemsOf(item(PRODUCT_A, 1)));
        when(payRecordMapper.closeByOrderId(ORDER_ID))
                .thenThrow(new RuntimeException("db connection lost"));

        // 不应抛异常
        assertDoesNotThrow(() -> orderService.cancelOrder(USER_ID, ORDER_ID),
                "正常取消不应抛异常（本用例要验证的是『取消成功后的回补与关单』）");

        // 主业务已完成，库存也回补了
        verify(orderMapper).cancelOrder(ORDER_ID, VERSION);
        verify(productMapper).rollbackStock(PRODUCT_A, 1);
    }

    /**
     * <p><b>为什么测它：</b>回补影响 0 行说明库存已被别人补过或数据异常，此时只应<b>记 error 日志告警</b>，不能让取消失败。</p>
     * <p><b>关键断言：</b>不抛异常；回补被调用过；支付单仍然被关闭。</p>
     */

    @Test
    @DisplayName("[容错] 库存回补影响 0 行 → 只记 error 日志，不抛异常（不阻断取消）")
    void cancelOrder_rollbackStockZeroRows_shouldNotThrow() {
        givenCancellableOrder(SeckillFlag.NO.getCode(), itemsOf(item(PRODUCT_A, 1)));
        when(productMapper.rollbackStock(PRODUCT_A, 1)).thenReturn(0);

        assertDoesNotThrow(() -> orderService.cancelOrder(USER_ID, ORDER_ID),
                "正常取消不应抛异常（本用例要验证的是『取消成功后的回补与关单』）");

        verify(productMapper).rollbackStock(PRODUCT_A, 1);
        verify(payRecordMapper).closeByOrderId(ORDER_ID);
    }

    // 助手

    private Order orderOf(int status, int isSeckill) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("ORD2026101400001");
        order.setUserId(USER_ID);
        order.setStatus(status);
        order.setVersion(VERSION);
        order.setIsSeckill(isSeckill);
        order.setPayAmount(new BigDecimal("199.00"));
        return order;
    }

    private OrderItem item(Long productId, int quantity) {
        OrderItem item = new OrderItem();
        item.setOrderId(ORDER_ID);
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    private List<OrderItem> itemsOf(OrderItem... items) {
        return List.of(items);
    }

    /** stub：一张「待付款 + 属于当前用户」的订单，CAS 成功，并带指定明细 */
    private void givenCancellableOrder(int isSeckill, List<OrderItem> items) {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(orderOf(OrderStatus.WAIT_PAY.getCode(), isSeckill));
        when(orderMapper.cancelOrder(ORDER_ID, VERSION)).thenReturn(1);
        when(orderItemMapper.selectByOrderId(ORDER_ID)).thenReturn(items);
        when(payRecordMapper.closeByOrderId(ORDER_ID)).thenReturn(1);
        // 注意：这里【不】stub rollbackStock / rollbackSeckillStockByQuantity ——
        // 它们是两个互斥分支（普通订单 vs 秒杀订单），统一 stub 会让严格模式报多余。
        // 不 stub 时默认返回 0，正好也顺带覆盖了「回补影响 0 行只记日志」的容错路径。
    }

    /** 断言：所有库存回补入口都没被碰过 */
    private void verifyNoStockRollback() {
        verify(productMapper, never()).rollbackStock(anyLong(), anyInt());
        verify(productMapper, never()).rollbackSeckillStockByQuantity(anyLong(), anyInt());
        verify(seckillOrderService, never()).rollbackRedisStock(anyLong(), anyInt());
    }
}
