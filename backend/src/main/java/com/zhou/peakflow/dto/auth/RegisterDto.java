package com.zhou.peakflow.dto.auth;
import lombok.Data;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

import static com.zhou.peakflow.constant.RegularPatterns.*;

/** 用户注册入参（手机号、昵称为选填） */
@Data
public class RegisterDto {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = USERNAME, message = "用户名格式：2-20位字母、数字、下划线、中文")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = PASSWORD, message = "密码格式：2-20位字母、中文、数字")
    private String password;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @Pattern(regexp = PHONE, message = "手机号格式不正确")
    private String phone;

    private String nickname;

}
