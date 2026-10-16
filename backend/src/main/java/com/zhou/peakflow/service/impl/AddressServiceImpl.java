package com.zhou.peakflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhou.peakflow.dto.address.AddAddressDto;
import com.zhou.peakflow.dto.address.UpdateAddressDto;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.AddressMapper;
import com.zhou.peakflow.pojo.Address;
import com.zhou.peakflow.service.AddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address> implements AddressService {

    private final AddressMapper addressMapper;

    /** 默认地址标识：1-默认，0-非默认 */
    private static final int IS_DEFAULT_YES = 1;
    private static final int IS_DEFAULT_NO = 0;

    // 查询当前用户地址列表（默认地址置顶，其余按创建时间倒序）
    @Override
    public List<Address> getUserAddresses(Long userId) {
        if (userId == null) {
            throw new BusinessException("用户 id 不能为空");
        }
        log.info("查询用户地址列表，userId={}", userId);

        LambdaQueryWrapper<Address> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Address::getUserId, userId)
                .orderByDesc(Address::getIsDefault)
                .orderByDesc(Address::getCreateTime);

        return this.list(wrapper);
    }

    // 获取当前用户的默认地址
    @Override
    public Address getDefaultAddress(Long userId) {
        if (userId == null) {
            throw new BusinessException("用户 id 不能为空");
        }
        log.info("获取用户默认地址，userId={}", userId);

        LambdaQueryWrapper<Address> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Address::getUserId, userId)
                .eq(Address::getIsDefault, IS_DEFAULT_YES);

        Address address = this.getOne(wrapper, false);
        if (address == null) {
            throw new BusinessException(ResultCode.ADDRESS_DEFAULT_MISSING);
        }
        return address;
    }

    // 新增地址
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addAddress(AddAddressDto addressDto, Long userId) {
        if (userId == null) {
            throw new BusinessException("用户 id 不能为空");
        }
        log.info("新增地址，userId={}", userId);

        // 1. 应用层重复校验（拦截绝大多数重复提交）
        LambdaQueryWrapper<Address> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Address::getUserId, userId)
                .eq(Address::getReceiverName, addressDto.getReceiverName())
                .eq(Address::getProvince, addressDto.getProvince())
                .eq(Address::getCity, addressDto.getCity())
                .eq(Address::getDistrict, addressDto.getDistrict())
                .eq(Address::getDetailAddress, addressDto.getDetailAddress());

        if (this.count(wrapper) > 0) {
            throw new BusinessException(ResultCode.ADDRESS_DUPLICATE);
        }

        Address address = new Address();
        BeanUtils.copyProperties(addressDto, address);
        address.setUserId(userId);
        // 显式置为"非默认"，避免依赖数据库默认值 / 出现 null
        address.setIsDefault(IS_DEFAULT_NO);

        // 2. 保存，唯一键兜底并发
        try {
            this.save(address);
        } catch (DuplicateKeyException e) {
            log.warn("并发插入重复地址，userId={}", userId);
            throw new BusinessException(ResultCode.ADDRESS_DUPLICATE);
        }

        log.info("新增地址成功，id={}", address.getId());
    }

    // 更新地址
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAddress(UpdateAddressDto addressDto, Long userId) {
        if (userId == null) {
            throw new BusinessException("用户 id 不能为空");
        }
        log.info("更新地址，id={}, userId={}", addressDto.getId(), userId);

        Address address = this.getById(addressDto.getId());
        if (address == null) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_FOUND);
        }
        if (!address.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_BELONG_USER);
        }

        BeanUtils.copyProperties(addressDto, address);

        if (!this.updateById(address)) {
            throw new BusinessException("更新失败，地址可能已被删除");
        }

        log.info("更新地址成功，id={}", addressDto.getId());
    }

    // 删除地址
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAddress(Long id, Long userId) {
        if (id == null || id <= 0) {
            throw new BusinessException("地址 id 不合法");
        }
        if (userId == null) {
            throw new BusinessException("用户 id 不能为空");
        }

        Address address = this.getById(id);
        if (address == null) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_FOUND);
        }
        if (!address.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_BELONG_USER);
        }

        boolean wasDefault = address.getIsDefault() != null
                && address.getIsDefault() == IS_DEFAULT_YES;

        if (!this.removeById(id)) {
            throw new BusinessException("删除失败，地址可能已被删除");
        }

        log.info("删除地址成功，id={}, userId={}, wasDefault={}", id, userId, wasDefault);
    }

    // 设置默认地址（单条 SQL 原子切换，避免并发下出现多个默认地址）
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefaultAddress(Long id, Long userId) {
        if (id == null || id <= 0) {
            throw new BusinessException("地址 id 不合法");
        }
        if (userId == null) {
            throw new BusinessException("用户 id 不能为空");
        }
        log.info("设置默认地址，id={}, userId={}", id, userId);

        Address address = this.getById(id);
        if (address == null) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_FOUND);
        }
        if (!address.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_BELONG_USER);
        }

        // 已经是默认地址，幂等返回
        if (address.getIsDefault() != null && address.getIsDefault() == IS_DEFAULT_YES) {
            log.info("地址已经是默认地址，无需重复设置，id={}", id);
            return;
        }

        int rows = addressMapper.setDefaultAddressAtomic(userId, id);
        if (rows == 0) {
            throw new BusinessException("设置默认地址失败，地址可能已被删除");
        }

        log.info("设置默认地址成功，id={}", id);
    }
}
