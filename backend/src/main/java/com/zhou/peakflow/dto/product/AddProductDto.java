package com.zhou.peakflow.dto.product;

import javax.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import static com.zhou.peakflow.constant.RegularPatterns.PRODUCT_NAME;
import static com.zhou.peakflow.constant.RegularPatterns.URL;

/** 普通商品新增入参（秒杀商品走 AddSeckillProductDto） */
@Data
public class AddProductDto {

    @NotBlank(message = "商品名称不能为空")
    @Pattern(regexp = PRODUCT_NAME, message = "商品名称只能包含中文、字母、数字、下划线、短横线，长度2-30位")
    private String name;

    @Size(max = 200, message = "商品描述长度不能超过200个字符")
    private String description;

    @Size(max = 2000, message = "商品详情长度不能超过2000个字符")
    private String detail;

    @NotBlank(message = "商品图片不能为空")
    @Pattern(regexp = URL, message = "商品图片必须是合法的http/https链接")
    private String image;

    @NotNull(message = "商品原价不能为空")
    @DecimalMin(value = "0.01", message = "商品原价必须大于0")
    private BigDecimal price;

    @NotNull(message = "商品库存不能为空")
    @Min(value = 0, message = "商品库存不能小于0")
    private Integer stock;

    @NotNull(message = "商品分类ID不能为空")
    @Min(value = 1, message = "商品分类ID必须大于0")
    private Long categoryId;
}
