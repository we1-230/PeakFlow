package com.zhou.peakflow.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体类
 */
@Data
@TableName("product")
public class Product extends BaseEntity{

    @TableId(type = IdType.AUTO)
    private Long id;

    // 商品名称
    private String name;

    // 商品描述
    private String description;

    // 商品详情
    private String detail;

    // 商品图片URL
    private String image;

    // 原价
    private BigDecimal price;

    // 秒杀价（秒杀商品时使用）
    private BigDecimal seckillPrice;

    // 普通库存
    private Integer stock;

    // 秒杀库存
    private Integer seckillStock;

    // 已售数量
    private Integer soldCount;

    // 商品分类ID（关联category表）
    private Long categoryId;

    // 是否秒杀商品（0-否，1-是）
    private Integer isSeckill;

    // 秒杀开始时间
    private LocalDateTime seckillStartTime;

    // 秒杀结束时间
    private LocalDateTime seckillEndTime;

    // 商品状态（0-下架，1-上架）
    private Integer status;

    // 乐观锁版本号（用于防止超卖）
    private Integer version;

    // 逻辑删除标记（0-未删除，1-已删除）
    @TableLogic
    private Integer deleted;

}