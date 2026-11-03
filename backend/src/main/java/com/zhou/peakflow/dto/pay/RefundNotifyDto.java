package com.zhou.peakflow.dto.pay;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 第三方 → 商户：退款结果异步通知（回调）参数
 *
 * 与 PayNotifyDto 同理：MockPay 用下划线命名字段，DTO 用驼峰，
 * 必须显式 @JsonProperty 映射，否则验签缺参数、退款回调被拒。
 */
@Data
public class RefundNotifyDto {

    // 商户退款单号（第三方原样回传，用于定位本地的退款单）
    @JsonProperty("out_refund_no")
    private String outRefundNo;

    // 第三方退款流水号（退款成功时才有值，对账凭据）
    @JsonProperty("refund_trade_no")
    private String refundTradeNo;

    // 原支付单的商户订单号
    @JsonProperty("out_trade_no")
    private String outTradeNo;

    // 实际退款金额（必须与本地退款单金额比对，防止被篡改）
    @JsonProperty("refund_amount")
    private BigDecimal refundAmount;

    // 退款结果：1-退款成功，0-退款失败
    private Integer status;

    // 失败原因（status=0 时有值）
    private String reason;

    // 回调时间戳（毫秒），参与签名
    private Long timestamp;

    // 签名值（除 sign 外所有参数参与签名）
    private String sign;
}
