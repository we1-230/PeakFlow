package com.zhou.peakflow.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.Collection;

/**
 * 自定义 UserDetails：在 Spring Security 的 User 上补充 userId 与账号状态
 */
public class CustomUserDetails extends User {
    private Long userId;
    private Integer status;

    public CustomUserDetails(Long userId, String username, String password, Integer status,
                             Collection<? extends GrantedAuthority> authorities) {
        super(username, password != null ? password : "", authorities);
        this.userId = userId;
        this.status = status;
    }

    public CustomUserDetails(Long userId, String username,
                             Collection<? extends GrantedAuthority> authorities, Integer status) {
        super(username, "", authorities);   // ← 由 null 改为空串（Spring Security 不允许 null 密码）
        this.userId = userId;
        this.status = status;
    }

    public Long getUserId() { return userId; }
    public Integer getStatus() { return status; }
}
