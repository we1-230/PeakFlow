package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.dto.product.UpdateCategoryDto;
import com.zhou.peakflow.pojo.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 商品分类 Mapper（删除与下架均带"分类下无商品"校验，避免脏引用）
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    // 更新分类：仅当目标状态为启用，或分类下已无商品时才允许
    @Update("""
        UPDATE category c
        SET c.name = #{name},
            c.icon = #{icon},
            c.sort = #{sort},
            c.status = #{status},
            c.update_time = NOW()
        WHERE c.id = #{id}
          AND c.deleted = 0
          AND (
              #{status} = 1
              OR NOT EXISTS (
                  SELECT 1 FROM product p
                  WHERE p.category_id = c.id
                    AND p.deleted = 0
              )
          )
        """)
    int updateCategoryWithCheck(UpdateCategoryDto dto);

    // 删除分类：分类下还有商品时拒绝
    @Update("""
            UPDATE category c
            SET c.deleted = 1,
                c.update_time = NOW()
            WHERE c.id = #{id}
              AND c.deleted = 0
              AND NOT EXISTS (
                  SELECT 1 FROM product p
                  WHERE p.category_id = c.id
                    AND p.deleted = 0
              )
            """)
    int deleteCategoryIfNoProduct(@Param("id") Long id);

    // 分类总数（未删除）
    @Select("SELECT COUNT(*) FROM category WHERE deleted = 0")
    Long countAllCategories();
}