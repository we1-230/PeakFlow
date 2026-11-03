package com.zhou.peakflow.dto.user;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Data;

import static com.zhou.peakflow.constant.RegularPatterns.PASSWORD;

/** 按用户名强制改密入参（/auth/changePassword，区别于用户自助改密） */
@Data
public class UpdateUserPasswordDto {

    @NotBlank(message = "旧密码不能为空")
    @Pattern(regexp = PASSWORD, message = "密码格式：2-20位字母、中文、数字")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Pattern(regexp = PASSWORD, message = "密码格式：2-20位字母、中文、数字")
    private String newPassword;

    // 强制改密的用户名
    @NotBlank(message = "用户名不能为空")
    private String username;
}
