package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.pojo.Address;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 收货地址 Mapper
 */
@Mapper
public interface AddressMapper extends BaseMapper<Address> {

    // 原子切换默认地址：一条 SQL 同时"清旧默认 + 设新默认"，避免中间态（无默认 / 多个默认）
    @Update("UPDATE address SET is_default = CASE WHEN id = #{id} THEN 1 ELSE 0 END, update_time = NOW() " +
            "WHERE user_id = #{userId} AND deleted = 0")
    int setDefaultAddressAtomic(@Param("userId") Long userId, @Param("id") Long id);
}
