package com.zhou.peakflow.dto.refund;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 管理员审核退款入参
 */
@Data
@Schema(description = "管理员审核退款入参")
public class AuditRefundDto {

    @NotNull(message = "退款单ID不能为空")
    @Schema(description = "退款单ID", example = "1")
    private Long refundId;

    // 是否通过：true=审核通过并发起退款，false=驳回
    @NotNull(message = "审核结论不能为空")
    @Schema(description = "是否通过：true=通过，false=驳回", example = "true")
    private Boolean approved;

    // 审核意见
    @Size(max = 200, message = "审核意见不能超过200字")
    @Schema(description = "审核意见", example = "商品已拆封影响二次销售，不予退款")
    private String remark;
}
