package com.zhou.peakflow.vo;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品响应VO，只返回前端需要的字段
 */
@Data
@Builder
public class ProductVo {

    private Long id;
    private String name;
    private String description;
    private String image;
    private BigDecimal price;
    private BigDecimal seckillPrice;
    private Integer stock;
    private Integer soldCount;
    private Long categoryId;
    private Integer isSeckill;
    private LocalDateTime seckillStartTime;
    private LocalDateTime seckillEndTime;
    private Integer status;
    private LocalDateTime createTime;
}