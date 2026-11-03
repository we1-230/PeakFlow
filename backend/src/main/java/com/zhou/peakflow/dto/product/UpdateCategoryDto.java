package com.zhou.peakflow.dto.product;

import javax.validation.constraints.*;
import lombok.Data;

import static com.zhou.peakflow.constant.RegularPatterns.*;

/** 商品分类修改入参 */
@Data
public class UpdateCategoryDto {

    /** 分类ID */
    @NotNull(message = "分类id不能为空")
    @Min(value = 1, message = "分类id必须大于等于1")
    private Long id;

    // 分类名称
    @NotBlank(message = "商品分类名称不能为空")
    @Pattern(regexp = CATEGORY_NAME, message = "商品分类名称格式不正确")
    private String name;

    // 分类图标URL
    @NotBlank(message = "商品分类图标URL不能为空")
    @Pattern(regexp = URL, message = "商品分类图标URL格式不正确")
    private String icon;

    // 排序（越小越靠前）
    @NotNull(message = "排序号不能为空")
    @Min(value = 1, message = "排序号必须大于等于1")
    private Integer sort;

    // 状态（0-禁用，1-启用）
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态值只能为0或1")
    @Max(value = 1, message = "状态值只能为0或1")
    private Integer status;
}
