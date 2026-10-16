package com.zhou.peakflow.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhou.peakflow.dto.product.AddCategoryDto;
import com.zhou.peakflow.dto.product.UpdateCategoryDto;
import com.zhou.peakflow.pojo.Category;

import java.util.List;

/**
 * 商品分类服务
 */
public interface CategoryService extends IService<Category> {

    // 查询所有启用的分类（按排序正序）
    List<Category> getEnabledCategories();

    // 新增分类（名称唯一）
    void addCategory(AddCategoryDto categoryDto);

    // 更新分类
    void updateCategory(UpdateCategoryDto categoryDto);

    // 删除分类（分类下有商品时拒绝）
    void deleteCategory(Long id);
}
