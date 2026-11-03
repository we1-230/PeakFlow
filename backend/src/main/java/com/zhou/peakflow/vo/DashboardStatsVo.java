package com.zhou.peakflow.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 后台仪表盘统计 VO
 */
@Data
public class DashboardStatsVo {

    // 用户总数
    private Long userTotal;
    // 正常用户数
    private Long userActive;
    // 封禁用户数
    private Long userBanned;

    // 商品总数（普通）
    private Long productTotal;
    // 秒杀商品数
    private Long seckillTotal;
    // 分类总数
    private Long categoryTotal;

    // 订单总数
    private Long orderTotal;
    // 累计成交额 GMV
    private BigDecimal gmv;
    // 今日订单数
    private Long todayOrders;
    // 今日成交额
    private BigDecimal todayGmv;

    // 订单状态分布
    private List<OrderStatusDist> orderStatusDist;
    // 近7日订单趋势
    private List<TrendItem> trend7;

    /**
     * 订单状态分布项
     */
    @Data
    public static class OrderStatusDist {
        private Integer status;
        private String statusDesc;
        private Long count;
    }

    /**
     * 趋势项（按日）
     */
    @Data
    public static class TrendItem {
        private String date;
        private Long count;
        private BigDecimal amount;
    }
}
