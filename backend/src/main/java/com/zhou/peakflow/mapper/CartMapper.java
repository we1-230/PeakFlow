package com.zhou.peakflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 购物车表 Mapper（购物车主体已改为纯 Redis 实现，这里只保留商品删除时的同步清理入口）
 */
@Mapper
public interface CartMapper {

    // 按商品 ID 逻辑删除所有用户的购物车记录；商品下架 / 删除时调用，避免购物车残留失效商品
    @Update("UPDATE cart SET deleted = 1 WHERE product_id = #{productId} AND deleted = 0")
    void deleteByProductId(@Param("productId") Long productId);
}
