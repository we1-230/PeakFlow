package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.enums.UserAccountStatus;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.User;
import com.zhou.peakflow.mapper.UserMapper;
import com.zhou.peakflow.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 自定义 UserDetailsService：登录时查库装配用户与权限，并拦截封禁/临时密码账号
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final org.redisson.api.RedissonClient redissonClient;

    // 根据用户名加载用户详情（直接查询数据库）
    @Override
    public UserDetails loadUserByUsername(String username) {
        log.debug("加载用户详情，username={}", username);

        // 1. 查询数据库
        User user = userMapper.selectByUsername(username);
        if (user == null) throw new UsernameNotFoundException("用户名不存在");

        if (UserAccountStatus.DISABLED.getCode().equals(user.getStatus())) {
            throw new BusinessException("该账号已被封禁");
        }

        if (user.getIsTempPassword() != null && user.getIsTempPassword() == 1) {
            // 密码正确，但是是临时密码：返回特殊状态，前端强制跳修改密码页面
          throw new BusinessException("请自己通过临时密码重置自己的密码再登陆");

        }


        // 2. 获取权限
        List<String> permissionsList = userMapper.selectPermissionsByUsername(username);
        List<GrantedAuthority> authorities = permissionsList.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(java.util.stream.Collectors.toList());

        // 3. 返回包含 ID 和状态的自定义对象
        return new CustomUserDetails(user.getId(), username, user.getPassword(), user.getStatus(), authorities);
    }
}

