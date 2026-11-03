package com.zhou.peakflow.dto.refund;

import com.zhou.peakflow.dto.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 退款单查询 DTO（用户端 / 管理端共用）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "退款单查询入参")
public class RefundQueryDto extends PageRequest {

    // 订单号（精确）
    @Schema(description = "订单号")
    private String orderNo;

    // 用户ID（管理端筛选用；用户端会被强制覆盖为当前登录用户）
    @Schema(description = "用户ID")
    private Long userId;

    // 退款状态：0-待审核 1-退款中 2-退款成功 3-退款失败 4-已驳回 5-已撤销
    @Schema(description = "退款状态")
    private Integer status;

    // 商户退款单号（精确）
    @Schema(description = "退款单号")
    private String refundNo;
}
