package com.zhou.peakflow.enums;

import lombok.Getter;

/**
 * 是否秒杀商品的标记
 *
 * 普通订单下单时会校验该标记并拒绝秒杀商品，提示走秒杀页面；秒杀链路则依赖它为 YES 走独立库存。
 */
@Getter
public enum SeckillFlag {

    /** 普通商品 */
    NO(0, "否"),

    /** 秒杀商品 */
    YES(1, "是");

    private final Integer code;
    private final String desc;

    SeckillFlag(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按标记码反查枚举，查不到返回 null */
    public static SeckillFlag fromCode(Integer code) {
        for (SeckillFlag flag : values()) {
            if (flag.code.equals(code)) {
                return flag;
            }
        }
        return null;
    }
}
