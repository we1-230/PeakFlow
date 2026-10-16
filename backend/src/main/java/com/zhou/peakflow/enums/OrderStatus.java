package com.zhou.peakflow.enums;

import lombok.Getter;

/**
 * 订单状态枚举（流转全部走乐观锁 CAS，见 OrderMapper）
 */
@Getter
public enum OrderStatus {

    /** 待付款：已锁库存，等待支付；超时关单后释放 */
    WAIT_PAY(0, "待付款"),

    /** 已付款：等待商家发货 */
    PAID(1, "已付款"),

    /** 已发货：等待用户确认收货 */
    SHIPPED(2, "已发货"),

    /** 已完成：用户确认收货，进入售后窗口期 */
    COMPLETED(3, "已完成"),

    /** 已取消：用户主动取消（与超时关单的"已关闭"区分，风控口径不同） */
    CANCELLED(4, "已取消"),

    /** 已关闭：超时未支付，系统关单；用于识别"下单不付款"的惯犯 */
    CLOSED(5, "已关闭"),

    /** 已退款：退款 / 售后完成后的终态 */
    REFUNDED(6, "已退款");

    private final Integer code;
    private final String desc;

    OrderStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按状态码反查枚举，查不到返回 null */
    public static OrderStatus fromCode(Integer code) {
        for (OrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }

    /** 是否允许用户取消（只有待付款可以） */
    public static boolean canCancel(Integer code) {
        return WAIT_PAY.getCode().equals(code);
    }

    /** 是否允许支付入账（只有待付款可以） */
    public static boolean canPay(Integer code) {
        return WAIT_PAY.getCode().equals(code);
    }

    /** 是否允许退款：已付款 / 已发货 / 已完成；其中"已完成"还要叠加退款窗口期，由退款服务单独判断 */
    public static boolean canRefund(Integer code) {
        return PAID.getCode().equals(code)
                || SHIPPED.getCode().equals(code)
                || COMPLETED.getCode().equals(code);
    }

    /**
     * 「在途」状态码的 SQL 片段（待付款 0 / 已付款 1 / 已发货 2）。
     *
     * 与 {@link #isInFlight} 同口径。用于「商品能否删除 / 下架」的前置校验：
     * 只要还有一笔订单没走完，商品就不该被撤下，否则用户付了钱却无货可发。
     */
    public static final String IN_FLIGHT_STATUS_SQL = "0,1,2";

    /** 是否为「在途」态（订单尚未终结，交易还在进行中） */
    public static boolean isInFlight(Integer code) {
        return WAIT_PAY.getCode().equals(code)
                || PAID.getCode().equals(code)
                || SHIPPED.getCode().equals(code);
    }

    /** 是否为「终态」（终态订单不再流转，不阻止商品删除 / 下架） */
    public static boolean isFinal(Integer code) {
        return CANCELLED.getCode().equals(code)
                || CLOSED.getCode().equals(code)
                || REFUNDED.getCode().equals(code)
                || COMPLETED.getCode().equals(code);
    }
}
