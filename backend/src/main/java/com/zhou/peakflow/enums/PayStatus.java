package com.zhou.peakflow.enums;

import lombok.Getter;

/**
 * 支付单状态枚举（一张支付单 = 一次向第三方发起收款的尝试，独立于订单状态存在）
 */
@Getter
public enum PayStatus {

    /** 待支付：已创建支付单，等待用户付款 */
    WAIT_PAY(0, "待支付"),

    /** 支付成功：由第三方异步回调触发，是全流程唯一能"确认收款"的入口 */
    SUCCESS(1, "支付成功"),

    /** 已关闭：订单取消或超时关单时同步关闭，不能再被支付 */
    CLOSED(2, "已关闭"),

    /** 已退款：由退款流程触发 */
    REFUNDED(3, "已退款");

    private final Integer code;
    private final String desc;

    PayStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按状态码反查枚举，查不到返回 null */
    public static PayStatus fromCode(Integer code) {
        for (PayStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }

    /** 是否允许被支付入账：只有待支付的单子才能被回调置为成功 */
    public static boolean canPay(Integer code) {
        return WAIT_PAY.getCode().equals(code);
    }
}
