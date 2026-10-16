package com.zhou.peakflow.enums;

/**
 * 统一业务响应码：按域分段（4xx、1000 参数、2000 用户、3000 业务、4000 缓存、5000 系统）
 */
public enum ResultCode {

    // 成功
    SUCCESS(200, "操作成功"),

    // 客户端错误 4xx
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证，请先登录"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),

    // 参数错误 1000-1999
    PARAM_ERROR(1000, "参数错误"),
    PARAM_MISSING(1001, "参数缺失"),
    PARAM_FORMAT_ERROR(1002, "参数格式错误"),
    PARAM_VALID_FAIL(1003, "参数校验失败"),

    // 用户错误 2000-2999
    USER_NOT_LOGIN(2000, "用户未登录"),
    USER_NOT_FOUND(2001, "用户不存在"),
    USER_PASSWORD_ERROR(2002, "用户名或密码错误"),
    USER_DISABLED(2003, "账号已禁用"),
    USERNAME_EXISTS(2004, "用户名已存在"),
    EMAIL_EXISTS(2005, "邮箱已存在"),
    TOKEN_INVALID(2006, "token无效"),
    TOKEN_EXPIRED(2007, "token已过期"),
    USER_LOGIN_OUT(2008, "登出失败"),

    // 业务错误 3000-3999
    // 注意：3000-3004 为历史已发布错误码，禁止修改编号（前端/测试已依赖）
    STOCK_NOT_ENOUGH(3000, "库存不足"),
    SECKILL_REPEAT(3001, "请勿重复秒杀"),
    SECKILL_FAIL(3002, "秒杀失败"),
    ORDER_NOT_FOUND(3003, "订单不存在"),
    ORDER_CREATE_FAIL(3004, "订单创建失败"),

    // 新增通用/细分业务码 3005-3099
    BUSINESS_ERROR(3005, "业务处理失败"),
    ORDER_STATUS_ERROR(3006, "订单状态不允许此操作"),
    ORDER_NO_PERMISSION(3007, "无权操作此订单"),
    CART_STOCK_NOT_ENOUGH(3008, "商品库存不足"),
    PRODUCT_NOT_FOUND(3009, "商品不存在"),
    PRODUCT_OFF_SHELF(3010, "商品已下架"),
    PRODUCT_NAME_EXISTS(3011, "商品名称已存在"),
    ADDRESS_NOT_FOUND(3012, "地址不存在"),
    ADDRESS_NOT_BELONG_USER(3013, "地址不属于当前用户"),
    ADDRESS_DUPLICATE(3014, "该地址已存在"),
    ADDRESS_DEFAULT_MISSING(3015, "没有默认地址"),
    CATEGORY_NAME_EXISTS(3016, "分类名称已存在"),
    CATEGORY_HAS_PRODUCT(3017, "分类下有商品，无法操作"),

    // 下单风控 3018-3019
    ORDER_UNPAID_QUOTA_EXCEEDED(3018, "待支付订单过多，请先完成支付或取消后再下单"),

    // 支付相关 3020-3029
    PAY_RECORD_NOT_FOUND(3020, "支付单不存在"),
    PAY_AMOUNT_MISMATCH(3021, "支付金额与订单金额不一致"),
    PAY_SIGN_ERROR(3022, "支付签名校验失败"),
    PAY_STATUS_ERROR(3023, "支付单状态不允许此操作"),
    PAY_CHANNEL_NOT_SUPPORT(3024, "不支持的支付渠道"),
    PAY_NOTIFY_FAIL(3025, "支付结果通知处理失败"),

    // 退款相关 3030-3049
    REFUND_NOT_FOUND(3030, "退款单不存在"),
    REFUND_STATUS_ERROR(3031, "退款单当前状态不允许此操作"),
    REFUND_AMOUNT_ERROR(3032, "退款金额不合法"),
    REFUND_REPEAT(3033, "该订单已有退款申请正在处理中"),
    REFUND_NOT_ALLOWED(3034, "当前订单不满足退款条件"),
    REFUND_WINDOW_EXPIRED(3035, "已超过可退款期限"),
    REFUND_NO_PERMISSION(3036, "无权操作此退款单"),
    REFUND_NOTIFY_FAIL(3037, "退款结果通知处理失败"),
    REFUND_PAY_NOT_FOUND(3038, "未找到该订单的支付成功流水，无法退款"),
    REFUND_REQUEST_FAIL(3039, "发起第三方退款请求失败"),

    // 售后退货退款 3040-3049
    REFUND_RETURN_INFO_MISSING(3040, "退货退款需填写退货物流公司与退货单号"),
    REFUND_TYPE_NOT_MATCH(3042, "该退款单类型不支持此操作"),

    // 缓存错误 4000-4999
    CACHE_PENETRATION(4000, "缓存穿透"),
    CACHE_BREAKDOWN(4001, "缓存击穿"),
    CACHE_AVALANCHE(4002, "缓存雪崩"),

    // 系统错误 5000-5999
    SYSTEM_ERROR(5000, "系统繁忙，请稍后重试"),
    DB_ERROR(5001, "数据库异常"),
    CACHE_ERROR(5002, "缓存异常"),
    MQ_ERROR(5003, "消息队列异常"),
    LOCK_ERROR(5004, "获取锁失败");

    private final Integer code;
    private final String message;

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}