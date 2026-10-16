package com.zhou.peakflow.dto.product;

import com.zhou.peakflow.dto.page.PageRequest;
import lombok.Data;

/** 商品列表查询入参（按分类与关键字筛选） */
@Data
public class SelectProductDto extends PageRequest {

    private Long categoryId;
    private String keyword;
}
