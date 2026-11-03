package com.zhou.peakflow.enums;

import lombok.Getter;

/**
 * 用户账号状态
 */
@Getter
public enum UserAccountStatus {

    /** 禁用（封号）：不能登录，已登录会话会被强制下线 */
    DISABLED(0, "禁用"),

    /** 启用（正常）：可正常登录使用 */
    ENABLED(1, "启用");

    private final Integer code;
    private final String desc;

    UserAccountStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按状态码反查枚举，查不到返回 null */
    public static UserAccountStatus fromCode(Integer code) {
        for (UserAccountStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
