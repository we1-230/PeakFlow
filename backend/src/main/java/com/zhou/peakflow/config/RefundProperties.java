package com.zhou.peakflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 退款策略配置
 */
@Component
@Data
@ConfigurationProperties(prefix = "refund")
public class RefundProperties {

    // 小额自动审核阈值
    private BigDecimal autoAuditAmount = BigDecimal.ZERO;

    // 已完成订单的可退款窗口（天）
    private Integer windowDays = 15;

    /** 用户填写的退款原因最大长度（与 refund_record.reason 字段长度对应） */
    private Integer maxReasonLength = 200;

    /** 人工审核意见最大长度（与 refund_record.audit_remark 字段长度对应） */
    private Integer maxRemarkLength = 200;

    // ---- 中间态超时收敛（防止退款单永久卡死） ----

    /** 进入「退款中」后至少要等待多少分钟才允许重试 */
    private Integer retryDelayMinutes = 30;

    /**
     * 「退款中」从申请时刻算起超过多少分钟就不再自动重试，只告警。
     * 防止三方长期故障时无限打扰对方；到点后交给人工按「重试 / 作废」处理。
     */
    private Integer autoRetryMaxMinutes = 360;

    /** 「待审核」超过多少天自动驳回 */
    private Integer applyingTimeoutDays = 7;

    /**
     * 「待用户退货」超过多少天自动作废。
     * 用户一直不寄回时，该单会永久占着订单占用位，导致商品无法删除/下架。
     */
    private Integer waitReturnTimeoutDays = 30;

    /** 重试前的最小等待分钟数（配置缺失或非正数时回落 30） */
    public int resolveRetryDelayMinutes() {
        return positiveOr(retryDelayMinutes, 30);
    }

    /** 自动重试的时间上限（分钟，回溯申请时刻；配置缺失或非正数时回落 360） */
    public int resolveAutoRetryMaxMinutes() {
        return positiveOr(autoRetryMaxMinutes, 360);
    }

    /** 「待审核」自动驳回天数（配置缺失或非正数时回落 7） */
    public int resolveApplyingTimeoutDays() {
        return positiveOr(applyingTimeoutDays, 7);
    }

    /** 「待用户退货」自动作废天数（配置缺失或非正数时回落 30） */
    public int resolveWaitReturnTimeoutDays() {
        return positiveOr(waitReturnTimeoutDays, 30);
    }

    /** 配置项缺失或非正数时回落到默认值，避免一份坏配置把功能整个关掉（与 OrderRiskProperties 同口径） */
    private int positiveOr(Integer value, int fallback) {
        return (value == null || value <= 0) ? fallback : value;
    }

    // 是否开启小额自动审核
    public boolean autoAuditEnabled() {
        return autoAuditAmount != null && autoAuditAmount.compareTo(BigDecimal.ZERO) > 0;
    }
}
