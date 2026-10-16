package com.zhou.peakflow.dto.pay;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 第三方 → 商户：支付结果异步通知（回调）参数
 *
 * MockPay 第三方侧用「下划线」命名字段（out_trade_no / trade_no），
 * 而 DTO 字段是「驼峰」，Jackson 默认不会把下划线自动映射成驼峰，
 * 导致 outTradeNo / tradeNo 反序列化成 null → 验签缺参数 → 回调被拒、资金不入账。
 * 因此必须显式加 @JsonProperty 做映射。
 */
@Data
public class PayNotifyDto {

    // 商户订单号（第三方原样回传，用于找到本地支付单）
    @JsonProperty("out_trade_no")
    private String outTradeNo;

    // 第三方交易号（支付平台生成的流水号）
    @JsonProperty("trade_no")
    private String tradeNo;

    // 实际收款金额（必须与本地支付单金额比对，防止改价攻击）
    private BigDecimal amount;

    // 支付结果：1-支付成功（本模拟环境只回传成功）
    private Integer status;

    // 回调时间戳（毫秒），参与签名
    private Long timestamp;

    // 签名值
    private String sign;
}
