package com.zhou.peakflow.enums;

import lombok.Getter;

/**
 * 退款类型（区分「退款」与「售后」；一律由服务端按订单状态推导，客户端无权指定）
 */
@Getter
public enum RefundType {

    /** 仅退款：货物没出库，退款成功即回补库存 */
    REFUND_ONLY(1, "仅退款"),

    /** 退货退款：货已出库（即"申请售后"），必须先退货入库再放款 */
    RETURN_AND_REFUND(2, "退货退款");

    private final Integer code;
    private final String desc;

    RefundType(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按类型码反查枚举，查不到返回 null */
    public static RefundType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (RefundType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }

    // 按订单状态推导退款类型（唯一的类型来源）
    public static Integer resolveByOrderStatus(Integer orderStatus) {
        if (OrderStatus.SHIPPED.getCode().equals(orderStatus)
                || OrderStatus.COMPLETED.getCode().equals(orderStatus)) {
            return RETURN_AND_REFUND.getCode();
        }
        return REFUND_ONLY.getCode();
    }

    /** 是否为"退货退款"（售后） */
    public static boolean isReturnAndRefund(Integer code) {
        return RETURN_AND_REFUND.getCode().equals(code);
    }
}
