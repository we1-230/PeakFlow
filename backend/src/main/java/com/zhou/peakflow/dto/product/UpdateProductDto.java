package com.zhou.peakflow.dto.product;

import javax.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

/** 普通商品修改入参（秒杀商品走 UpdateSeckillProductDto） */
@Data
public class UpdateProductDto {

    @NotNull(message = "商品ID不能为空")
    @Min(value = 1, message = "商品ID必须大于0")
    private Long id;

    @NotBlank(message = "商品名称不能为空")
    @Size(min = 2, max = 100, message = "商品名称长度2-100位")
    private String name;

    @Size(max = 500, message = "商品描述长度不能超过500个字符")
    private String description;

    @Size(max = 65535, message = "商品详情长度过长")
    private String detail;

    @NotBlank(message = "商品图片不能为空")
    @Size(max = 500, message = "商品图片URL长度不能超过500")
    private String image;

    @NotNull(message = "商品原价不能为空")
    @DecimalMin(value = "0.01", message = "商品原价必须大于0")
    private BigDecimal price;

    @DecimalMin(value = "0.01", message = "商品秒杀价必须大于0")
    private BigDecimal seckillPrice;

    @NotNull(message = "商品库存不能为空")
    @Min(value = 0, message = "商品库存不能小于0")
    private Integer stock;

    @NotNull(message = "商品分类ID不能为空")
    @Min(value = 1, message = "商品分类ID必须大于0")
    private Long categoryId;

    @NotNull(message = "商品状态不能为空")
    @Min(value = 0, message = "商品状态不合法")
    @Max(value = 1, message = "商品状态不合法")
    private Integer status;
}
