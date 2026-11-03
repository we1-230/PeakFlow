package com.zhou.peakflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模拟支付（MockPay）配置
 */
@Component
@Data
@ConfigurationProperties(prefix = "mock-pay")
public class MockPayProperties {

    /** 双方约定的签名密钥（商户与"第三方"共享，模拟环境里用同一个值） */
    private String secretKey;

    /** 支付渠道标识，写入支付流水 */
    private String channel = "MOCK_PAY";

    /** 收银台地址（发起支付时返回给前端跳转的地址） */
    private String cashierUrl;

    /** 商户侧的异步回调地址（"第三方"收到付款后回调这里） */
    private String notifyUrl;

    /** 商户同步跳转地址（用户付完款浏览器跳回的页面） */
    private String returnUrl;

    // 第三方退款接口地址（商户 → MockPay，发起退款）
    private String refundUrl;

    // 商户侧退款结果回调地址（MockPay → 商户）
    private String refundNotifyUrl;

    /** 模拟"第三方"回调失败概率（0-100，默认 0 不失败），用于联调验签/幂等异常分支 */
    private int failRate = 0;

    // 模拟"第三方退款失败"概率（0-100，默认 0 不失败）
    private int refundFailRate = 0;
}
