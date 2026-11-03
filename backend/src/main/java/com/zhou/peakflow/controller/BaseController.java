package com.zhou.peakflow.controller;

import com.zhou.peakflow.constant.CommonConstant;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.utils.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 控制器基类：从 Authorization 头解析用户 ID，供各业务控制器复用
 */
@Component
@Slf4j
public class BaseController {

    @Autowired
    private JwtUtil jwtUtil;

    Long getUserIdFromToken(String token) {
        if (token != null && token.startsWith(CommonConstant.TOKEN_PREFIX)) {
            token = token.substring(CommonConstant.TOKEN_PREFIX.length());
            return jwtUtil.getUserIdFromToken(token);
        }
        throw new BusinessException("token无效");
    }
}
