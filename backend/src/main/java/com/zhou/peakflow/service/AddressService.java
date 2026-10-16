package com.zhou.peakflow.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhou.peakflow.dto.address.AddAddressDto;
import com.zhou.peakflow.dto.address.UpdateAddressDto;
import com.zhou.peakflow.pojo.Address;

import java.util.List;

/**
 * 收货地址服务：所有写操作都校验归属，防水平越权
 */
public interface AddressService extends IService<Address> {

    // 查询用户的所有地址（默认地址置顶）
    List<Address> getUserAddresses(Long userId);

    // 未设置时抛业务异常引导用户去设置
    Address getDefaultAddress(Long userId);

    // 新增地址（新增时不会自动成为默认，避免出现多个默认地址）
    void addAddress(AddAddressDto addAddressDto, Long userId);

    void updateAddress(UpdateAddressDto dto, Long userId);

    // 删除地址（删默认地址后不会自动补新的，由用户显式设置）
    void deleteAddress(Long id, Long userId);

    // 设置默认地址（单条 SQL 原子切换，避免并发下出现多个默认）
    void setDefaultAddress(Long id, Long userId);
}
