package com.zhou.peakflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 「发起支付」响应
 */
@Data
@Builder
@Schema(description = "发起支付响应")
public class PrepayVo {

    // 商户订单号（我方生成，前端后续用它轮询支付结果）
    @Schema(description = "商户订单号")
    private String outTradeNo;

    // 业务订单号
    @Schema(description = "业务订单号")
    private String orderNo;

    // 应付金额
    @Schema(description = "应付金额")
    private BigDecimal amount;

    // 支付渠道
    @Schema(description = "支付渠道", example = "MOCK_PAY")
    private String payChannel;

    // 收银台地址
    @Schema(description = "收银台地址")
    private String cashierUrl;
}
