package com.zhou.peakflow.enums;

import lombok.Getter;

/**
 * 退款单状态枚举（退款单与订单解耦：订单表达"想不想退"，退款单表达"这笔钱退得怎么样"）
 */
@Getter
public enum RefundStatus {

    /** 待审核：用户已提交申请，等待管理员审核；此时可被用户撤销 */
    APPLYING(0, "待审核"),

    /** 退款中：已向第三方发起退款，等待异步回调确认（钱在路上） */
    REFUNDING(1, "退款中"),

    /** 退款成功：第三方确认退款到账（终态） */
    SUCCESS(2, "退款成功"),

    /** 退款失败：请求异常或渠道回调失败，可重试或作废 */
    FAILED(3, "退款失败"),

    /** 已驳回：管理员审核不通过（终态） */
    REJECTED(4, "已驳回"),

    /** 用户已撤销：审核前用户主动放弃（终态） */
    CANCELLED(5, "用户已撤销"),

    /** 已作废：退款失败且渠道确实退不了时由管理员收口（终态） */
    CLOSED(6, "已作废"),

    /** 待用户退货：售后审核已通过，等用户寄回；货到签收后才进入退款中并放款 */
    WAIT_RETURN(7, "待用户退货");

    private final Integer code;
    private final String desc;

    RefundStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按状态码反查枚举，查不到返回 null */
    public static RefundStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (RefundStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }

    /** 是否允许管理员审核（只有待审核可以） */
    public static boolean canAudit(Integer code) {
        return APPLYING.getCode().equals(code);
    }

    /** 是否允许用户撤销（待审核、待用户退货两个时机；商家签收后状态已进退款中，不能再撤） */
    public static boolean canCancel(Integer code) {
        return APPLYING.getCode().equals(code) || WAIT_RETURN.getCode().equals(code);
    }

    /** 是否允许签收退货（只有待用户退货可以） */
    public static boolean canConfirmReturn(Integer code) {
        return WAIT_RETURN.getCode().equals(code);
    }

    /**
     * 是否允许重新发起退款。
     */
    public static boolean canRetry(Integer code) {
        return FAILED.getCode().equals(code)
                || REFUNDING.getCode().equals(code);
    }

    /** 是否允许作废（只有退款失败可以；作废用于释放对订单的占用） */
    public static boolean canClose(Integer code) {
        return FAILED.getCode().equals(code);
    }

    // 「未完结」状态码的 SQL 片段（待审核 / 退款中 / 退款失败 / 待用户退货）
    public static final String OPEN_STATUS_SQL = "0,1,3,7";

    /** 「可撤销」状态码的 SQL 片段，与 {@link #canCancel} 同口径 */
    public static final String CANCELLABLE_STATUS_SQL = "0,7";

    /** 是否为「未完结」态（与 {@link #OPEN_STATUS_SQL} 语义一致，供 Java 侧判断） */
    public static boolean isOpen(Integer code) {
        return APPLYING.getCode().equals(code)
                || REFUNDING.getCode().equals(code)
                || FAILED.getCode().equals(code)
                || WAIT_RETURN.getCode().equals(code);
    }

    /** 是否为「正在走流程」（不含失败态，用于区分"还在流转"与"卡住等人处理"） */
    public static boolean isProcessing(Integer code) {
        return APPLYING.getCode().equals(code)
                || WAIT_RETURN.getCode().equals(code)
                || REFUNDING.getCode().equals(code);
    }

    /** 是否为终态（终态不再流转，也不再占用订单） */
    public static boolean isFinal(Integer code) {
        return SUCCESS.getCode().equals(code)
                || REJECTED.getCode().equals(code)
                || CANCELLED.getCode().equals(code)
                || CLOSED.getCode().equals(code);
    }
}
