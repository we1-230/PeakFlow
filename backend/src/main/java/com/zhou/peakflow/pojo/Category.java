package com.zhou.peakflow.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 商品分类实体类
 */
@Data
@TableName("category")
public class Category extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 分类名称
    private String name;

    // 分类图标URL
    private String icon;

    // 排序（越小越靠前）
    private Integer sort;

    // 状态（0-禁用，1-启用）
    private Integer status;

    // 逻辑删除标记（0-未删除，1-已删除），与 product/address 保持一致
    @TableLogic
    private Integer deleted;

}