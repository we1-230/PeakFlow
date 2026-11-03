package com.zhou.peakflow.dto.user;

import lombok.Data;

/** 用户自助修改个人资料入参（昵称/邮箱/手机号） */
@Data
public class UpdateUserInfoDto {
    private String nickname;
    private String email;
    private String phone;
}
