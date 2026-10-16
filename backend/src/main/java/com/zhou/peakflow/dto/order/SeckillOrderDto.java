package com.zhou.peakflow.dto.order;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import lombok.Data;

/**
 * 秒杀下单DTO
 */
@Data
public class SeckillOrderDto {

    /** 秒杀商品ID */
    @NotNull(message = "商品ID不能为空")
    @Min(value = 1, message = "商品ID无效")
    private Long productId;

    /** 地址ID */
    @NotNull(message = "地址ID不能为空")
    @Min(value = 1, message = "地址ID无效")
    private Long addressId;
}