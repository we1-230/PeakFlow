package com.zhou.peakflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款单展示对象（发给前端）
 */
@Data
@Builder
@Schema(description = "退款单信息")
public class RefundVo {

    @Schema(description = "退款单ID")
    private Long id;

    @Schema(description = "商户退款单号")
    private String refundNo;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "退款金额")
    private BigDecimal refundAmount;

    @Schema(description = "退款类型码：1-仅退款 2-退货退款（售后）")
    private Integer refundType;

    @Schema(description = "退款类型文案")
    private String refundTypeDesc;

    @Schema(description = "退款渠道")
    private String refundChannel;

    @Schema(description = "用户申请原因")
    private String reason;

    @Schema(description = "退款状态码")
    private Integer status;

    @Schema(description = "退款状态文案")
    private String statusDesc;

    @Schema(description = "审核人姓名")
    private String auditorName;

    @Schema(description = "审核时间")
    private LocalDateTime auditTime;

    @Schema(description = "审核意见")
    private String auditRemark;

    @Schema(description = "退款失败原因")
    private String failReason;

    @Schema(description = "第三方退款流水号")
    private String refundTradeNo;

    @Schema(description = "申请时间")
    private LocalDateTime applyTime;

    @Schema(description = "退款完成时间")
    private LocalDateTime refundTime;

    // ---- 退货信息（仅 refundType = 2 退货退款时有值） ----

    @Schema(description = "退货物流公司")
    private String returnLogisticsCompany;

    @Schema(description = "退货物流单号")
    private String returnTrackingNo;

    @Schema(description = "商家确认收到退货的时间；为空表示货还没回到商家手里")
    private LocalDateTime returnReceivedTime;

    // 前端展示用：当前状态是否允许管理员「确认收到退货」
    @Schema(description = "是否允许确认收到退货")
    private Boolean canConfirmReturn;

    // 前端展示用：当前状态是否允许用户撤销申请
    @Schema(description = "是否允许撤销")
    private Boolean canCancel;

    // 前端展示用：当前状态是否允许管理员重新发起退款
    @Schema(description = "是否允许重试退款")
    private Boolean canRetry;

    // 前端展示用：当前状态是否允许管理员作废（退款失败且渠道确实退不了时的出口）
    @Schema(description = "是否允许作废")
    private Boolean canClose;
}
