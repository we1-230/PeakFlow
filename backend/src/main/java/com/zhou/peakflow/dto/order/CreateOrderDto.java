package com.zhou.peakflow.dto.order;

import lombok.Data;
import java.util.List;

/**
 * 创建普通订单DTO（支持多商品）
 */
@Data
public class CreateOrderDto {

    // 地址ID
    private Long addressId;

    // 订单商品列表
    private List<OrderItemDto> items;

    // 备注
    private String remark;

    /** 下单明细项（商品与购买数量） */
    @Data
    public static class OrderItemDto {
        // 商品ID
        private Long productId;

        // 购买数量
        private Integer quantity;
    }
}
