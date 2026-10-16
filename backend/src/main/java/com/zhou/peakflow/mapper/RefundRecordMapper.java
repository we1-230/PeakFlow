package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.pojo.RefundRecord;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 退款单 Mapper（所有状态流转都写成「带旧状态条件的 UPDATE」：判定与修改同一条 SQL，天然幂等且无需加锁）
 */
@Mapper
public interface RefundRecordMapper extends BaseMapper<RefundRecord> {

    // ---- 查询 ----

    /** 按商户退款单号查询（第三方回调与幂等判断都靠它） */
    @Select("SELECT * FROM refund_record WHERE refund_no = #{refundNo}")
    RefundRecord selectByRefundNo(@Param("refundNo") String refundNo);

    /** 查某订单下「未完结」的退款单，用于防重拦截；口径取自 {@link RefundStatus#OPEN_STATUS_SQL} */
    @Select("SELECT * FROM refund_record WHERE order_id = #{orderId} AND status IN ("
            + RefundStatus.OPEN_STATUS_SQL + ") ORDER BY id DESC LIMIT 1")
    RefundRecord selectOpenByOrderId(@Param("orderId") Long orderId);

    /** 批量查多个订单下「未完结」的退款单（一条 IN 查询替代逐单查询，避免 N+1） */
    @Select("<script>" +
            "SELECT * FROM refund_record WHERE status IN (" + RefundStatus.OPEN_STATUS_SQL + ") AND order_id IN " +
            "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<RefundRecord> selectOpenByOrderIds(@Param("orderIds") List<Long> orderIds);

    /** 查某订单「最近一条」退款单，不限状态（展示口径：终态也要能看到退款痕迹） */
    @Select("SELECT * FROM refund_record WHERE order_id = #{orderId} ORDER BY id DESC LIMIT 1")
    RefundRecord selectLatestByOrderId(@Param("orderId") Long orderId);

    /**
     * 统计某商品下「未完结」的退款 / 售后单数量（待审核 / 退款中 / 退款失败待重试 / 待用户退货）。
     *
     * 商品删除 / 下架的前置占用校验。注意这一条无法被「订单状态是否在途」覆盖：
     * 订单处于「已完成」时订单侧看着是终态，但只要售后还在流转（钱货未结清），
     * 撤下商品就会让售后无法继续处理。口径取自 {@link RefundStatus#OPEN_STATUS_SQL}。
     */
    @Select("SELECT COUNT(*) FROM order_item oi " +
            "JOIN refund_record rr ON rr.order_id = oi.order_id " +
            "WHERE oi.product_id = #{productId} AND oi.deleted = 0 " +
            "  AND rr.status IN (" + RefundStatus.OPEN_STATUS_SQL + ")")
    long countOpenRefundsByProductId(@Param("productId") Long productId);

    /** 批量查多个订单各自「最近一条」退款单；按 order_id, id DESC 排序，调用方分组取首条 */
    @Select("<script>" +
            "SELECT * FROM refund_record WHERE order_id IN " +
            "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            " ORDER BY order_id, id DESC" +
            "</script>")
    List<RefundRecord> selectLatestByOrderIds(@Param("orderIds") List<Long> orderIds);

    // ---- 兜底扫描（定时任务） ----

    // 扫描「待审核且已超期」的退款单 id。以 apply_time 为锚点（申请那一刻开始计时）。
    @Select("SELECT id FROM refund_record " +
            "WHERE status = 0 AND apply_time IS NOT NULL AND apply_time <= #{deadline} " +
            "ORDER BY id LIMIT #{limit}")
    List<Long> selectTimeoutApplyingIds(@Param("deadline") LocalDateTime deadline,
                                        @Param("limit") int limit);

    // 扫描「退款中且已卡住」的退款单 id。
    @Select("SELECT id FROM refund_record " +
            "WHERE status = 1 AND update_time IS NOT NULL AND update_time <= #{deadline} " +
            "ORDER BY id LIMIT #{limit}")
    List<Long> selectStuckRefundingIds(@Param("deadline") LocalDateTime deadline,
                                       @Param("limit") int limit);

    // 扫描「待用户退货且已超期」的退款单 id。
    @Select("SELECT id FROM refund_record " +
            "WHERE status = 7 AND return_received_time IS NULL " +
            "  AND update_time IS NOT NULL AND update_time <= #{deadline} " +
            "ORDER BY id LIMIT #{limit}")
    List<Long> selectTimeoutWaitReturnIds(@Param("deadline") LocalDateTime deadline,
                                          @Param("limit") int limit);

    // ---- 状态流转（全部为 CAS） ----

    /** 审核通过：待审核 → 目标状态；目标状态由调用方按退款类型决定（仅退款→1，退货退款→7） */
    @Update("UPDATE refund_record " +
            "SET status = #{newStatus}, auditor_id = #{auditorId}, auditor_name = #{auditorName}, " +
            "    audit_time = NOW(), audit_remark = #{auditRemark}, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int approve(@Param("id") Long id,
                @Param("auditorId") Long auditorId,
                @Param("auditorName") String auditorName,
                @Param("auditRemark") String auditRemark,
                @Param("newStatus") Integer newStatus);

    /** 审核驳回：待审核 → 已驳回 */
    @Update("UPDATE refund_record " +
            "SET status = 4, auditor_id = #{auditorId}, auditor_name = #{auditorName}, " +
            "    audit_time = NOW(), audit_remark = #{auditRemark}, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int reject(@Param("id") Long id,
               @Param("auditorId") Long auditorId,
               @Param("auditorName") String auditorName,
               @Param("auditRemark") String auditRemark);

    /** 用户撤销：待审核 / 待用户退货 → 用户已撤销；口径取自 {@link RefundStatus#CANCELLABLE_STATUS_SQL} */
    @Update("UPDATE refund_record " +
            "SET status = 5, audit_remark = #{auditRemark}, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status IN (" + RefundStatus.CANCELLABLE_STATUS_SQL + ")")
    int cancel(@Param("id") Long id, @Param("auditRemark") String auditRemark);

    // 退款成功：退款中 / 退款失败 → 退款成功（第三方回调触发，唯一能确认退款的入口）
    @Update("UPDATE refund_record " +
            "SET status = 2, refund_trade_no = #{refundTradeNo}, refund_time = NOW(), " +
            "    fail_reason = NULL, notify_content = #{notifyContent}, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE refund_no = #{refundNo} AND status IN (1, 3)")
    int markRefundSuccess(@Param("refundNo") String refundNo,
                          @Param("refundTradeNo") String refundTradeNo,
                          @Param("notifyContent") String notifyContent);

    /** 退款失败：退款中 → 退款失败 */
    @Update("UPDATE refund_record " +
            "SET status = 3, fail_reason = #{failReason}, " +
            "    notify_content = #{notifyContent}, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE refund_no = #{refundNo} AND status = 1")
    int markRefundFailed(@Param("refundNo") String refundNo,
                         @Param("failReason") String failReason,
                         @Param("notifyContent") String notifyContent);

    /**
     * 重新发起：退款失败 / 退款中 → 退款中。
     */
    @Update("UPDATE refund_record " +
            "SET status = 1, fail_reason = NULL, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status IN (1, 3)")
    int markRefunding(@Param("id") Long id);

    /**
     * 系统自动作废：待用户退货 → 已作废（终态）。
     */
    @Update("UPDATE refund_record " +
            "SET status = 6, audit_remark = #{remark}, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 7 AND return_received_time IS NULL")
    int closeAbandonedWaitReturn(@Param("id") Long id, @Param("remark") String remark);

    /** 管理员作废：退款失败 → 已作废（终态）；渠道确实退不了时的泄压阀，作废后释放对订单的占用 */
    @Update("UPDATE refund_record " +
            "SET status = 6, auditor_id = #{operatorId}, auditor_name = #{operatorName}, " +
            "    audit_time = NOW(), audit_remark = #{remark}, " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 3")
    int closeFailed(@Param("id") Long id,
                    @Param("operatorId") Long operatorId,
                    @Param("operatorName") String operatorName,
                    @Param("remark") String remark);

    // ---- 售后：签收退货 ----

    // 确认收到退货：待用户退货 → 退款中（同时打入库时间戳）
    @Update("UPDATE refund_record " +
            "SET status = 1, return_received_time = NOW(), " +
            "    version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} " +
            "  AND status = 7 " +
            "  AND refund_type = 2 " +
            "  AND return_received_time IS NULL")
    int confirmReturnReceived(@Param("id") Long id);

    // ---- 申请：原子落库 ----

    // 申请退款·原子落库：INSERT ... SELECT + WHERE 条件，把「订单可退」的校验与插入压进一条语句，
    // 避免"先查后插"之间被并发改动；未命中任何行即返回 0，由调用方按「订单状态已变更」处理
    @Insert("INSERT INTO refund_record " +
            "(refund_no, order_id, order_no, user_id, out_trade_no, refund_amount, refund_type, " +
            " refund_channel, reason, return_logistics_company, return_tracking_no, " +
            " status, apply_time, version, create_time, update_time) " +
            "SELECT " +
            "  #{refundNo}, o.id, o.order_no, o.user_id, #{outTradeNo}, #{refundAmount}, #{refundType}, " +
            "  #{refundChannel}, #{reason}, #{returnLogisticsCompany}, #{returnTrackingNo}, " +
            "  #{status}, NOW(), 0, NOW(), NOW() " +
            "FROM `order` o " +
            "WHERE o.id = #{orderId} " +
            "  AND o.user_id = #{userId} " +
            "  AND o.status IN (1, 2, 3) " +
            "  AND o.version = #{version} " +
            "  AND o.deleted = 0")
    int insertRefundIfOrderRefundable(
            @Param("refundNo") String refundNo,
            @Param("orderId") Long orderId,
            @Param("userId") Long userId,
            @Param("outTradeNo") String outTradeNo,
            @Param("refundAmount") java.math.BigDecimal refundAmount,
            @Param("refundChannel") String refundChannel,
            @Param("reason") String reason,
            @Param("status") Integer status,
            @Param("version") Integer version,
            @Param("refundType") Integer refundType,
            @Param("returnLogisticsCompany") String returnLogisticsCompany,
            @Param("returnTrackingNo") String returnTrackingNo);
}
