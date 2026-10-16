package com.zhou.peakflow.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单响应VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderVo {

    private Long id;
    private String orderNo;
    private Long userId;
    private Long addressId;

    // 地址详情（关联查询）
    private AddressVo address;

    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private Integer status;
    private String statusDesc;
    private Integer orderType;

    private LocalDateTime payTime;
    private LocalDateTime shippingTime;
    private LocalDateTime completeTime;
    private LocalDateTime cancelTime;
    private LocalDateTime createTime;

    private String trackingNo;
    private String logisticsCompany;
    private String remark;

    // 订单商品列表
    private List<OrderItemVo> items;

    // 订单超时剩余秒数（用于前端倒计时）
    private Long timeoutRemaining;

    // ---- 售后（退款）进度 ----

    // 进行中（或最近一次）的退款单 ID，无退款单时为 null
    private Long refundId;

    // 商户退款单号
    private String refundNo;

    // 退款单状态码：0-待审核 1-退款中 2-退款成功 3-退款失败 4-已驳回 5-已撤销
    private Integer refundStatus;

    // 退款单状态文案（服务端翻译好，前端不用再维护一份状态码字典）
    private String refundStatusDesc;

    // 该笔退款是否「未完结」
    private Boolean refundProcessing;

    /** 订单收货地址快照 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddressVo {
        private String receiverName;
        private String receiverPhone;
        private String fullAddress;
    }

    /** 订单商品明细快照（下单时固化，不随商品改价变动） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemVo {
        private Long productId;
        private String productName;
        private String productImage;
        private BigDecimal productPrice;
        private Integer quantity;
        private BigDecimal subtotal;
    }
}
