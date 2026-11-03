package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.pojo.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单商品项 Mapper（批量查询用于订单列表组装，避免 N+1）
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    // 批量插入订单商品项，SQL 见 resources/mapper/OrderItemMapper.xml
    int batchInsert(@Param("list") List<OrderItem> items);

    // 按订单 ID 查询商品项
    @Select("SELECT * FROM order_item WHERE order_id = #{orderId} and deleted = 0")
    List<OrderItem> selectByOrderId(@Param("orderId") Long orderId);

    // 按订单 ID 列表批量查询，SQL 见 resources/mapper/OrderItemMapper.xml
    List<OrderItem> selectByOrderIds(@Param("orderIds") List<Long> orderIds);

    /**
     * 统计某商品下「在途」的订单数（待付款 / 已付款 / 已发货）。
     *
     * 商品删除 / 下架的前置占用校验：只要还有一笔订单没走完，撤下商品就会让用户
     * 付了钱却无货可发。口径取自 {@link OrderStatus#IN_FLIGHT_STATUS_SQL}，
     * 与终态（已完成 / 已取消 / 已关闭 / 已退款）明确区分。
     */
    @Select("SELECT COUNT(*) FROM order_item oi " +
            "JOIN `order` o ON o.id = oi.order_id " +
            "WHERE oi.product_id = #{productId} " +
            "  AND oi.deleted = 0 AND o.deleted = 0 " +
            "  AND o.status IN (" + OrderStatus.IN_FLIGHT_STATUS_SQL + ")")
    long countInFlightOrdersByProductId(@Param("productId") Long productId);

    /**
     * 统计某商品下「已完成但仍在售后保障期内」的订单数。
     *
     * 时间锚点口径与退款申请链路（{@code RefundTxService#checkRefundable}）一致：
     * 取 {@code complete_time}，为空时回落到 {@code create_time}。
     *
     * 锚点两个都为空（数据异常）时计入统计结果，即按「仍在保障期内」处理。
     * 这是刻意选的 fail-safe 方向：删除商品不可逆，判不准就该拦住。
     * 注意退款链路的方向是相反的（判不准则拒绝退款），因为那边「拒绝」才是安全侧。
     *
     * @param windowStart 售后窗口起点（= 当前时间 - 窗口天数）。由调用方按 JVM 时钟算好传入，
     *                    避免与数据库的 NOW() 产生时区/时钟偏差
     */
    @Select("SELECT COUNT(*) FROM order_item oi " +
            "JOIN `order` o ON o.id = oi.order_id " +
            "WHERE oi.product_id = #{productId} " +
            "  AND oi.deleted = 0 AND o.deleted = 0 " +
            "  AND o.status = #{status} " +
            "  AND (COALESCE(o.complete_time, o.create_time) IS NULL " +
            "       OR COALESCE(o.complete_time, o.create_time) >= #{windowStart})")
    long countInAfterSaleWindowOrdersByProductId(@Param("productId") Long productId,
                                                 @Param("status") Integer status,
                                                 @Param("windowStart") LocalDateTime windowStart);

}
