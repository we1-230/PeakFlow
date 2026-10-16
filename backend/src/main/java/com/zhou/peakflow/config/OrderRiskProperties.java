package com.zhou.peakflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 下单风控配置（防恶意下单占库存）
 */
@Component
@Data
@ConfigurationProperties(prefix = "order-risk")
public class OrderRiskProperties {

    // 单用户同时可持有的「待付款」订单数上限（基础配额）
    private Integer maxUnpaidOrders = 5;

    // 风控统计窗口（天）
    private Integer abandonWindowDays = 7;

    /** 窗口内每累计 N 次超时未支付，配额收紧 1 笔 */
    private Integer abandonPerPenalty = 5;

    // 配额最多收紧几笔
    private Integer maxQuotaPenalty = 4;

    // 配额下限
    private Integer minUnpaidQuota = 1;

    // 计算"有效配额"：基础配额减去惯犯惩罚
    public int resolveEffectiveQuota(int abandonCount) {
        int perPenalty = positiveOr(abandonPerPenalty, 5);
        int maxPenalty = positiveOr(maxQuotaPenalty, 4);
        int base = positiveOr(maxUnpaidOrders, 5);
        int minQuota = positiveOr(minUnpaidQuota, 1);

        int penalty = Math.min(abandonCount / perPenalty, maxPenalty);
        return Math.max(base - penalty, minQuota);
    }

    // 风控窗口天数（带兜底）
    public int resolveAbandonWindowDays() {
        return positiveOr(abandonWindowDays, 7);
    }

    /** 配置项缺失或非正数时回落到默认值，避免因为一份错误的配置把功能整个关掉 */
    private int positiveOr(Integer value, int fallback) {
        return (value == null || value <= 0) ? fallback : value;
    }
}
