package com.zhou.peakflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhou.peakflow.config.WhiteListConfig;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.utils.DesensitizationUtil;
import com.zhou.peakflow.utils.JwtUtil;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import com.zhou.peakflow.constant.CommonConstant;
import com.zhou.peakflow.enums.ResultCode;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 认证过滤器：从 Token 解出身份权限写入 SecurityContext，白名单路径直接放行（不查库）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final WhiteListConfig whiteListConfig;
    private final com.zhou.peakflow.service.impl.CustomUserDetailsService customUserDetailsService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        log.debug("JWT过滤器处理请求：path={}, method={}", path, request.getMethod());

        if (isWhiteList(path)) {
            log.debug("白名单路径，跳过认证：{}", path);
           filterChain.doFilter(request, response);
            return;
        }

        // 1. 从请求中获取token
        String token = getTokenFromRequest(request);

        try {
            // 2. 如果token存在且有效
            if (StringUtils.hasText(token) && jwtUtil.validateToken(token)) {
                log.debug("Token验证通过，开始认证");

                // 3. 从token中获取用户名
                String username = jwtUtil.getUsernameFromToken(token);
                log.debug("从Token获取用户名：{}", username);
                long userId = jwtUtil.getUserIdFromToken(token);

                // 4. 加载用户信息
                List<String> permissions = jwtUtil.getPermissionsFromToken(token);
                List<GrantedAuthority> authorities = permissions.stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
                UserDetails userDetails = new CustomUserDetails(userId, username, authorities, 1);
                log.debug("加载用户信息成功，username={}, authorities={}",
                        username, userDetails.getAuthorities());

                // 5. 创建认证token
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // 6. 设置到SecurityContext
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("用户认证信息已设置到SecurityContext，username={}", username);

                // 7. token续期检查（剩余时间小于30分钟）
                if (jwtUtil.needRefresh(token)) {
                    log.debug("Token即将过期，剩余时间小于30分钟，建议续期");
                    response.setHeader("X-Token-Expiring", "true");
                }
            } else {
                log.warn("Token无效或不存在，path={}", path);
                throw new BusinessException("token无效");
            }

            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            log.warn("Token已过期，path={}", path);
            returnResult(response, ResultCode.UNAUTHORIZED.getCode(), "Token 已过期");
        } catch (MalformedJwtException e) {
            log.warn("Token格式不正确，path={}", path);
            returnResult(response, ResultCode.UNAUTHORIZED.getCode(), "Token 格式不正确");
        } catch (Exception e) {
            log.error("Token验证异常，path={}", path, e);
            returnResult(response, ResultCode.UNAUTHORIZED.getCode(), "Token 无效，请重新登录");
        }
    }

    private void returnResult(HttpServletResponse response, int code, String msg) throws IOException {
        response.setContentType(CommonConstant.CONTENT_TYPE_JSON);
        response.setCharacterEncoding("UTF-8");
        Result<Void> result = Result.error(code, msg);
        response.getWriter().write(objectMapper.writeValueAsString(result));   // ← 复用注入的
        log.debug("返回错误响应：code={}, msg={}", code, msg);
    }

    // 从请求头获取token
    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(CommonConstant.TOKEN_PREFIX)) {
            String token = bearerToken.substring(CommonConstant.TOKEN_PREFIX.length());
            log.debug("从请求头获取Token成功，token={}", DesensitizationUtil.maskToken(token));
            return token;
        }
        return null;
    }

    private boolean isWhiteList(String path) {
        List<String> whitePathList = whiteListConfig.getWhiteList();
        for (String whitePath : whitePathList) {
            if (pathMatcher.match(whitePath, path)) {
                return true;
            }
        }
        return false;
    }
}