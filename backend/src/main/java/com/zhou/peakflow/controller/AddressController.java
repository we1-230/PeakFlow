package com.zhou.peakflow.controller;

import com.zhou.peakflow.dto.address.AddAddressDto;
import com.zhou.peakflow.dto.address.UpdateAddressDto;
import com.zhou.peakflow.pojo.Address;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.service.AddressService;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 收货地址控制器
 */
@Slf4j
@RestController
@RequestMapping("/address")
@RequiredArgsConstructor
@Tag(name = "地址管理", description = "收货地址相关接口")
public class AddressController extends BaseController  {

    private final AddressService addressService;



    // 无缓存，直查 DB —— 需要限流保护
    @GetMapping("/list")
    public Result<List<Address>> list(@RequestHeader("Authorization") String token) {
        log.info("获取地址列表请求");
        Long userId = super.getUserIdFromToken(token);

        List<Address> addresses = addressService.getUserAddresses(userId);
        return Result.success(addresses);
    }

    // 无缓存，直查 DB —— 需要限流保护
    @GetMapping("/default")
    public Result<Address> getDefault(@RequestHeader("Authorization") String token) {
        log.info("获取默认地址请求");

        Long userId = getUserIdFromToken(token);

        Address address = addressService.getDefaultAddress(userId);
        return Result.success(address);
    }

    @PostMapping("/add")
    public Result<Void> add(@RequestHeader("Authorization") String token,
                            @Valid @RequestBody AddAddressDto addressDto) {
        log.info("新增地址请求");

        Long userId = getUserIdFromToken(token);

        addressService.addAddress(addressDto, userId);
        return Result.success("新增成功");
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestHeader("Authorization") String token,
                               @Valid @RequestBody UpdateAddressDto addressDto) {
        log.info("更新地址请求，id={}", addressDto.getId());
        Long userId = getUserIdFromToken(token);

        addressService.updateAddress(addressDto, userId);
        return Result.success("更新成功");
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@RequestHeader("Authorization") String token,
                               @PathVariable Long id) {
        log.info("删除地址请求，id={}", id);

        Long userId = getUserIdFromToken(token);

        addressService.deleteAddress(id, userId);
        return Result.success("删除成功");
    }

    @PutMapping("/default/{id}")
    public Result<Void> setDefault(@RequestHeader("Authorization") String token,
                                   @PathVariable Long id) {

        log.info("设置默认地址请求，id={}", id);
        Long userId = getUserIdFromToken(token);

        addressService.setDefaultAddress(id, userId);
        return Result.success("设置成功");
    }
}
