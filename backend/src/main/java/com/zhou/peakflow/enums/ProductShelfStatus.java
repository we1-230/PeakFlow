package com.zhou.peakflow.enums;

import lombok.Getter;

/**
 * 商品上下架状态
 */
@Getter
public enum ProductShelfStatus {

    /** 下架：用户端不可见，也不能下单 */
    OFF_SHELF(0, "下架"),

    /** 上架：正常售卖中 */
    ON_SHELF(1, "上架");

    private final Integer code;
    private final String desc;

    ProductShelfStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按状态码反查枚举，查不到返回 null */
    public static ProductShelfStatus fromCode(Integer code) {
        for (ProductShelfStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }

    /** 状态码是否合法（供入参校验使用） */
    public static boolean isValid(Integer code) {
        return fromCode(code) != null;
    }
}
