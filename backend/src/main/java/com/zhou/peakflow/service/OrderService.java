package com.zhou.peakflow.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhou.peakflow.dto.order.CreateOrderDto;
import com.zhou.peakflow.dto.order.OrderQueryDto;
import com.zhou.peakflow.dto.order.UpdateOrderDto;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.vo.OrderVo;
import com.zhou.peakflow.vo.PageVo;

import java.math.BigDecimal;

/**
 * 订单服务：状态流转全部基于乐观锁 + 状态机校验
 */
public interface OrderService extends IService<Order> {

    // 校验通过后扣库存并落库（商品与地址做快照，后续改价改地址不影响历史单）
    Order createOrder(Long userId, CreateOrderDto createOrderDto);

    // 标记订单已付款（由第三方支付回调驱动，不接受前端直接调用）
    void markOrderPaid(Long orderId);

    // 取消订单（仅待付款可取消）
    void cancelOrder(Long userId, Long orderId);

    // 确认收货（仅已发货可确认，且订单不能有未完结退款单）
    void confirmReceipt(Long userId, Long orderId);

    // 退款入账：订单置为已退款并按退款类型决定是否回补库存（由第三方退款回调驱动）
    void markOrderRefunded(Long orderId, BigDecimal refundAmount, Integer refundType);

    // 退货入库：商家签收退货后回补库存（库存变化由实物动作驱动，而非退款成功）
    void restoreStockForReturn(Long orderId);

    OrderVo getOrderDetail(Long userId, Long orderId);

    PageVo<OrderVo> getUserOrders(Long userId, OrderQueryDto queryDto);

    // 管理员更新订单（发货 / 维护物流信息）
    void adminUpdateOrder(UpdateOrderDto updateOrderDto, Long operatorId, String operatorName);

    // 管理员订单列表查询
    PageVo<OrderVo> adminListOrders(OrderQueryDto queryDto);

    // 超时自动关单（供延迟消息消费者与兜底扫描任务调用）
    //
    // 返回值含义：
    //   true  —— 本笔已终结，不需要再关（关单成功 / 已支付 / 已取消 / 订单不存在）
    //   false —— 本次"没有关成"，原因是该订单还有未过期的支付单在占用（用户可能正在付款），
    //            需要由兜底扫描任务稍后再关一次
    boolean timeoutCancelOrder(Long orderId);

    void sendOrderTimeoutDelayMessage(Long orderId);

    // ---- 兜底扫描（定时任务调用） ----

    /**
     * 兜底关闭「已到达关单时刻」的待付款订单，返回实际终结的笔数。
     */
    int sweepTimeoutPendingOrders(int limit);

    /**
     * 兜底把「已发货且超过 {@code AUTO_CONFIRM_RECEIPT_DAYS} 天」的订单自动确认收货，
     */
    int sweepAutoConfirmReceipt(int limit);
}
