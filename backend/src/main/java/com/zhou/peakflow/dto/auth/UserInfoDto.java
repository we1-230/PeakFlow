package com.zhou.peakflow.dto.auth;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户信息返回对象（不含密码等敏感字段） */
@Data
public class UserInfoDto {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
    private Integer status;
    private LocalDateTime lastLoginTime;
}