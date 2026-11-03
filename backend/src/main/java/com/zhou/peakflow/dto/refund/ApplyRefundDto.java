package com.zhou.peakflow.dto.refund;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.Size;

/**
 * 用户申请退款 / 申请售后入参
 */
@Data
@Schema(description = "申请退款/售后入参")
public class ApplyRefundDto {

    // 关联的订单 ID（走 /order/refund/{orderId} 旧入口时可以为空，以路径参数为准）
    @Schema(description = "订单ID", example = "1001")
    private Long orderId;

    // 退款原因（选填，但强烈建议填，是客服沟通与后续风控分析的依据）
    @Size(max = 200, message = "退款原因不能超过200字")
    @Schema(description = "退款原因", example = "拍错了，不想要了")
    private String reason;

    // ---- 以下两项仅「已发货 / 已完成」订单（退货退款/售后）需要填 ----

    // 退货物流公司
    @Size(max = 64, message = "退货物流公司名称不能超过64字")
    @Schema(description = "退货物流公司（售后退货必填）", example = "顺丰速运")
    private String returnLogisticsCompany;

    // 退货物流单号（对已发货 / 已完成订单申请售后时必填）
    @Size(max = 64, message = "退货物流单号不能超过64字")
    @Schema(description = "退货物流单号（售后退货必填）", example = "SF1234567890123")
    private String returnTrackingNo;
}
