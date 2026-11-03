package com.zhou.peakflow.dto.auth;
import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

import static com.zhou.peakflow.constant.RegularPatterns.USERNAME;

/** 账号密码登录入参（含图形验证码） */
@Data
public class LoginDto {

    @NotBlank(message = "账号不能为空")
    @Pattern(regexp = USERNAME, message = "账号格式：2-20位字母、数字、下划线、中文")
    private String account;

    @NotBlank(message = "密码不能为空")
    private String password;

    // 验证码接口返回的 uuid
    private String captchaUuid;
    // 用户输入的图形验证码
    private String captchaCode;
}
