package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.pojo.PayRecord;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付流水 Mapper：状态流转同样用「带旧状态的 UPDATE」实现幂等
 */
@Mapper
public interface PayRecordMapper extends BaseMapper<PayRecord> {

    // 按商户订单号查询（回调、查询、幂等判断都靠它）
    @Select("SELECT * FROM pay_record WHERE out_trade_no = #{outTradeNo}")
    PayRecord selectByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    // 查某订单下仍可支付的支付单；prepay 幂等靠它复用同一张待支付单，不重复建单
    @Select("SELECT * FROM pay_record WHERE order_id = #{orderId} AND status = 0 " +
            "ORDER BY id DESC LIMIT 1")
    PayRecord selectWaitPayByOrderId(@Param("orderId") Long orderId);

    // 查某订单下"支付成功"的流水；退款金额与第三方追溯凭据都以它为准
    @Select("SELECT * FROM pay_record WHERE order_id = #{orderId} AND status = 1 " +
            "ORDER BY id DESC LIMIT 1")
    PayRecord selectPaidByOrderId(@Param("orderId") Long orderId);

    // 加锁版本，只给超时关单用
    // FOR UPDATE 锁住 order_id 索引区间，阻止并发插入新支付单
    @Select("SELECT * FROM pay_record WHERE order_id = #{orderId} AND status = 0 " +
            "ORDER BY id DESC LIMIT 1 FOR UPDATE")
    PayRecord selectWaitPayByOrderIdForUpdate(@Param("orderId") Long orderId);

    // 支付入账：WHERE 带 status = 0，首次回调返回 1、重复回调返回 0，天然幂等
    @Update("UPDATE pay_record " +
            "SET status = 1, " +
            "    trade_no = #{tradeNo}, " +
            "    notify_time = #{notifyTime}, " +
            "    notify_content = #{notifyContent}, " +
            "    version = version + 1, " +
            "    update_time = NOW() " +
            "WHERE out_trade_no = #{outTradeNo} AND status = 0")
    int markPaid(@Param("outTradeNo") String outTradeNo,
                 @Param("tradeNo") String tradeNo,
                 @Param("notifyTime") LocalDateTime notifyTime,
                 @Param("notifyContent") String notifyContent);

    // 按订单关闭支付单（订单取消 / 超时关单）；带 status = 0，已支付成功的单子不允许被关闭
    @Update("UPDATE pay_record SET status = 2, version = version + 1, update_time = NOW() " +
            "WHERE order_id = #{orderId} AND status = 0")
    int closeByOrderId(@Param("orderId") Long orderId);

    // 按商户订单号关闭支付单（按单号关更精确）
    @Update("UPDATE pay_record SET status = 2, version = version + 1, update_time = NOW() " +
            "WHERE out_trade_no = #{outTradeNo} AND status = 0")
    int closeByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    // 置为已退款：带 status = 1，只有支付成功的单子能被退款，重复调用不重复生效
    @Update("UPDATE pay_record SET status = 3, version = version + 1, update_time = NOW() " +
            "WHERE out_trade_no = #{outTradeNo} AND status = 1")
    int markRefunded(@Param("outTradeNo") String outTradeNo);

    // ---- 兜底扫描（定时任务） ----

    /**
     * 扫描「已过有效期、且可以安全关闭」的待支付单 id。
     */
    @Select("SELECT p.id FROM pay_record p " +
            "JOIN `order` o ON o.id = p.order_id " +
            "WHERE p.status = 0 AND p.create_time <= #{deadline} " +
            "  AND o.status IN (0, 4, 5) AND o.deleted = 0 " +
            "ORDER BY p.id LIMIT #{limit}")
    List<Long> selectClosableExpiredPayIds(@Param("deadline") LocalDateTime deadline,
                                           @Param("limit") int limit);

    /**
     * 一致性告警：统计「支付单还是待支付，但订单已经进入收款之后的状态」的异常单数。
     */
    @Select("SELECT COUNT(*) FROM pay_record p " +
            "JOIN `order` o ON o.id = p.order_id " +
            "WHERE p.status = 0 AND p.create_time <= #{deadline} " +
            "  AND o.status IN (1, 2, 3, 6)")
    long countPaidOrderWithWaitPayRecord(@Param("deadline") LocalDateTime deadline);

    // 按主键关闭过期的待支付单（CAS：必须是待支付，已入账的单子不会被误关）
    @Update("UPDATE pay_record SET status = 2, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int closeExpiredById(@Param("id") Long id);
}
