package com.zhou.peakflow.constant;

/**
 * 全局通用常量（Token 前缀、JSON 响应类型、临时密码前缀）
 */
public final class CommonConstant {

    private CommonConstant() {
    }

    /** Token 前缀（Authorization 头里的 "Bearer "） */
    public static final String TOKEN_PREFIX = "Bearer ";

    /** JSON 响应 Content-Type */
    public static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    /** 临时密码前缀（管理员重置密码后，用户首次登录需强制改密） */
    public static final String TEMP_PASSWORD_PREFIX = "TEMP_";
}
