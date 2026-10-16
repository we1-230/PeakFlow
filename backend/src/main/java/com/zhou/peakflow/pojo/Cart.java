package com.zhou.peakflow.pojo;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 购物车DTO
 */
@Data
public class Cart {

    // 商品ID
    private Long productId;

    // 购买数量
    private Integer quantity;

    // 是否选中（0-未选中，1-选中）
    private Integer selected;

    // 商品名称
    private String productName;

    // 商品价格
    private BigDecimal price;

    // 商品图片URL
    private String imageUrl;

    // 是否秒杀商品（0-否，1-是）——用于前端在购物车/结算时区分秒杀与普通商品
    private Integer isSeckill;
}