package com.zhou.peakflow.dto.user;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Data;

import static com.zhou.peakflow.constant.RegularPatterns.PASSWORD;

/**
 * 用户自助修改密码入参（/user/password）。
 *
 * 与 {@link UpdateUserPasswordDto} 的区别：后者用于「按用户名强制改密」（/auth/changePassword），
 * 需要 {@code username} 字段；而本接口的目标用户由登录态决定，不应要求（也不应信任）请求体里传 username。
 * 两者拆开是为了让 {@code @NotBlank} 等校验注解各自贴合自己的接口语义。
 */
@Data
public class ChangePasswordDto {

    @NotBlank(message = "旧密码不能为空")
    @Pattern(regexp = PASSWORD, message = "密码格式：2-20位字母、中文、数字")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Pattern(regexp = PASSWORD, message = "密码格式：2-20位字母、中文、数字")
    private String newPassword;
}
