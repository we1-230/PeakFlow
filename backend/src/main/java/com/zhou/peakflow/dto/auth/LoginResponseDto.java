package com.zhou.peakflow.dto.auth;
import com.zhou.peakflow.constant.CommonConstant;
import com.zhou.peakflow.pojo.User;
import lombok.Builder;
import lombok.Data;

/** 登录成功响应（JWT 与用户信息，不含密码） */
@Data
public class LoginResponseDto {

    // JWT token
    private String token;
    // token类型：Bearer
    private String tokenType;
    // 用户信息
    private UserInfo user;

    public LoginResponseDto(String token, User user) {
        this.token = token;
        this.tokenType = CommonConstant.TOKEN_PREFIX.trim();
        this.user = new UserInfo(user.getId(), user.getUsername(), user.getNickname(), user.getEmail(), user.getPhone(), user.getAvatar(), user.getStatus());
    }

    /** 精简用户信息 */
    @Data
    @Builder
    public static class UserInfo {
        private Long id;
        private String username;
        private String nickname;
        private String email;
        private String phone;
        private String avatar;
        private Integer status;
    }
}
