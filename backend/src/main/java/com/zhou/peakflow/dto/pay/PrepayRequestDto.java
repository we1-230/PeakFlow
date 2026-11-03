package com.zhou.peakflow.dto.pay;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 「发起支付」请求参数
 */
@Data
@Schema(description = "发起支付请求")
public class PrepayRequestDto {

    // 待支付的业务订单 ID
    @NotNull(message = "订单ID不能为空")
    @Schema(description = "业务订单ID", example = "1")
    private Long orderId;
}
