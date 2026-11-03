package com.zhou.peakflow.dto.address;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import lombok.Data;

import static com.zhou.peakflow.constant.RegularPatterns.PHONE;

/** 收货地址修改入参（需携带地址ID） */
@Data
public class UpdateAddressDto {

    @NotNull
    @Min(value = 1, message = "地址ID不能为空")
    private Long id;

    // 收货人姓名
    @NotBlank(message = "收货人姓名不能为空")
    private String receiverName;

    // 收货人电话
    @NotBlank(message = "收货人电话不能为空")
    @Pattern(regexp = PHONE, message = "手机号格式不正确")
    private String receiverPhone;

    // 省份
    @NotBlank(message = "省份不能为空")
    private String province;

    // 城市
    @NotBlank(message = "城市不能为空")
    private String city;

    // 区/县
    @NotBlank(message = "区/县不能为空")
    private String district;

    // 详细地址
    @NotBlank(message = "详细地址不能为空")
    private String detailAddress;
}
