package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.pojo.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单表 Mapper：状态流转全部用乐观锁（WHERE 带 version + 旧状态），物流更新额外带旧值条件防并发覆盖
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    // ---- 状态流转（乐观锁 CAS） ----

    // 确认收货：状态 + 版本 + 无未完结退款，三条件塞进同一条 UPDATE 以消灭"先查再更新"的竞态窗口
    @Update("UPDATE `order` o " +
            "SET status = 3, complete_time = NOW(), version = version + 1, update_time = NOW() " +
            "WHERE o.id = #{orderId} " +
            "  AND o.status = 2 " +
            "  AND o.version = #{version} " +
            "  AND o.deleted = 0 " +
            "  AND NOT EXISTS (" +
            "      SELECT 1 FROM refund_record r " +
            "      WHERE r.order_id = o.id " +
            "        AND r.status IN (" + RefundStatus.OPEN_STATUS_SQL + ")" +
            "  )")
    int completeReceiptIfNoRefund(@Param("orderId") Long orderId,
                                  @Param("version") Integer version);

    // 通用状态流转（乐观锁），供超时关单等场景使用
    @Update("UPDATE `order` " +
            "SET status = #{newStatus}, " +
            "    version = version + 1, " +
            "    update_time = NOW() " +
            "WHERE id = #{orderId} " +
            "  AND status = #{oldStatus} " +
            "  AND version = #{version} " +
            "  AND deleted = 0")
    int updateOrderStatus(@Param("orderId") Long orderId,
                          @Param("oldStatus") Integer oldStatus,
                          @Param("newStatus") Integer newStatus,
                          @Param("version") Integer version);

    // 发货：同样三合一（状态 + 版本 + 无未完结退款），防止"钱正在退、货却发出去"
    @Update("UPDATE `order` o " +
            "SET status = #{newStatus}, shipping_time = NOW(), version = version + 1, update_time = NOW() " +
            "WHERE o.id = #{orderId} " +
            "  AND o.status = #{oldStatus} " +
            "  AND o.version = #{version} " +
            "  AND o.deleted = 0 " +
            "  AND NOT EXISTS (" +
            "      SELECT 1 FROM refund_record r " +
            "      WHERE r.order_id = o.id " +
            "        AND r.status IN (" + RefundStatus.OPEN_STATUS_SQL + ")" +
            "  )")
    int shipOrderIfNoRefund(@Param("orderId") Long orderId,
                            @Param("oldStatus") Integer oldStatus,
                            @Param("newStatus") Integer newStatus,
                            @Param("version") Integer version);

    // 支付成功入账：仅允许"待付款 → 已付款"
    @Update("UPDATE `order` SET status = 1, pay_time = NOW(), version = version + 1, update_time = NOW() " +
            "WHERE id = #{orderId} AND status = 0 AND version = #{version} AND deleted = 0")
    int paySuccess(@Param("orderId") Long orderId, @Param("version") Integer version);

    // 用户取消：仅允许"待付款 → 已取消"（与超时关单的"已关闭"区分，风控口径不同）
    @Update("UPDATE `order` SET status = 4, cancel_time = NOW(), version = version + 1, update_time = NOW() " +
            "WHERE id = #{orderId} AND status = 0 AND version = #{version} AND deleted = 0")
    int cancelOrder(@Param("orderId") Long orderId, @Param("version") Integer version);

    // 退款入账：允许"已付款 / 已发货 / 已完成 → 已退款"；只在第三方确认退款成功后调用
    @Update("UPDATE `order` SET status = 6, refund_time = NOW(), refund_amount = #{refundAmount}, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{orderId} " +
            "AND status IN (1, 2, 3) " +
            "AND version = #{version} " +
            "AND deleted = 0")
    int refundOrder(@Param("orderId") Long orderId,
                    @Param("version") Integer version,
                    @Param("refundAmount") BigDecimal refundAmount);

    // ---- 物流信息 ----

    // 只更新传入的非空字段，且要求数据库当前值等于旧值才更新，防多管理员并发覆盖
    @Update("<script>" +
            "UPDATE `order` SET update_time = NOW() " +
            "<if test='trackingNo != null'>, tracking_no = #{trackingNo}</if>" +
            "<if test='logisticsCompany != null'>, logistics_company = #{logisticsCompany}</if>" +
            "<if test='remark != null'>, remark = #{remark}</if>" +
            " WHERE id = #{orderId} AND deleted = 0 " +
            "<if test='trackingNo != null'>" +
            "  AND (tracking_no = #{oldTrackingNo} " +
            "       OR (tracking_no IS NULL AND #{oldTrackingNo} IS NULL))" +
            "</if>" +
            "<if test='logisticsCompany != null'>" +
            "  AND (logistics_company = #{oldLogisticsCompany} " +
            "       OR (logistics_company IS NULL AND #{oldLogisticsCompany} IS NULL))" +
            "</if>" +
            "<if test='remark != null'>" +
            "  AND (remark = #{oldRemark} " +
            "       OR (remark IS NULL AND #{oldRemark} IS NULL))" +
            "</if>" +
            "</script>")
    int updateLogisticsInfoWithOldValue(
            @Param("orderId") Long orderId,
            @Param("trackingNo") String trackingNo,
            @Param("oldTrackingNo") String oldTrackingNo,
            @Param("logisticsCompany") String logisticsCompany,
            @Param("oldLogisticsCompany") String oldLogisticsCompany,
            @Param("remark") String remark,
            @Param("oldRemark") String oldRemark);

    // ---- 下单风控（防恶意占库存） ----

    // 统计用户当前持有的待付款订单数，用于配额判定；走 idx_user_status_cancel 索引
    @Select("SELECT COUNT(*) FROM `order` " +
            "WHERE user_id = #{userId} AND status = 0 AND deleted = 0")
    int countUnpaidByUserId(@Param("userId") Long userId);

    // 统计近 N 天内"下单后从未支付被超时关单"的次数（惯犯识别）；只算关单(5)，不算主动取消(4)
    @Select("SELECT COUNT(*) FROM `order` " +
            "WHERE user_id = #{userId} AND status = 5 AND deleted = 0 " +
            "  AND cancel_time IS NOT NULL " +
            "  AND cancel_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int countRecentTimeoutClosedByUserId(@Param("userId") Long userId,
                                         @Param("days") Integer days);

    // ---- 兜底扫描（定时任务） ----

    // 扫描「待付款且已到达关单时刻」的订单 id。
    // 只取 id 不取整行：后续每笔都会再走一次 CAS 关单，查询本身只负责"圈定候选集"。
    // 走 idx_status 索引；LIMIT 由调用方控制，避免一次扫全表。
    @Select("SELECT id FROM `order` " +
            "WHERE status = 0 AND deleted = 0 " +
            "  AND create_time <= #{deadline} " +
            "ORDER BY id LIMIT #{limit}")
    List<Long> selectTimeoutPendingOrderIds(@Param("deadline") LocalDateTime deadline,
                                            @Param("limit") int limit);

    // 扫描「已发货且发货时间已到达自动确认收货时刻」的订单 id。
    @Select("SELECT id FROM `order` " +
            "WHERE status = 2 AND deleted = 0 " +
            "  AND shipping_time IS NOT NULL " +
            "  AND shipping_time <= #{deadline} " +
            "ORDER BY id LIMIT #{limit}")
    List<Long> selectAutoConfirmOrderIds(@Param("deadline") LocalDateTime deadline,
                                         @Param("limit") int limit);

    // ---- 后台统计 ----

    // 订单总数
    @Select("SELECT COUNT(*) FROM `order` WHERE deleted = 0")
    Long countAllOrders();

    // 累计成交额（GMV）
    @Select("SELECT COALESCE(SUM(pay_amount), 0) FROM `order` WHERE deleted = 0")
    BigDecimal sumGmv();

    // 今日订单数
    @Select("SELECT COUNT(*) FROM `order` WHERE deleted = 0 AND create_time >= CURDATE()")
    Long countTodayOrders();

    // 今日成交额
    @Select("SELECT COALESCE(SUM(pay_amount), 0) FROM `order` WHERE deleted = 0 AND create_time >= CURDATE()")
    BigDecimal sumTodayGmv();

    // 订单状态分布
    @Select("SELECT status, COUNT(*) AS cnt FROM `order` WHERE deleted = 0 GROUP BY status ORDER BY status")
    List<Map<String, Object>> selectOrderStatusDist();

    // 近 7 日订单趋势（按天聚合订单数与金额）
    @Select("SELECT DATE(create_time) AS d, COUNT(*) AS cnt, COALESCE(SUM(pay_amount), 0) AS amt " +
            "FROM `order` WHERE deleted = 0 AND create_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) " +
            "GROUP BY DATE(create_time) ORDER BY d")
    List<Map<String, Object>> selectTrend7();
}
