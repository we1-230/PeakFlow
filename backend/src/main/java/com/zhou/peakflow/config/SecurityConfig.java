package com.zhou.peakflow.config;

import com.zhou.peakflow.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 装配：无状态 JWT 鉴权；放行表先具体后通配，首个匹配生效
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final UserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        log.info("开始配置 SecurityFilterChain ...");

        http
                .csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeHttpRequests()
                    // 安全收紧：强制改密按 username 定位账号，绝不能对外匿名开放。
                    // 这条必须排在 "/auth/**" 的 permitAll 之前（首个匹配生效），否则会被下面那条放行。
                    .antMatchers(HttpMethod.POST, "/auth/changePassword").authenticated()
                    .antMatchers("/auth/**").permitAll()
                   .antMatchers("/product/page", "/product/detail/**").permitAll()
                   .antMatchers("/category/list").permitAll()
                   .antMatchers("/actuator/**").permitAll() // 监控端点：运维探活，免登录
                   .antMatchers("/seckill/list", "/seckill/detail/**").permitAll()
                   .antMatchers("/swagger-ui/**", "/v3/api-docs/**", "/doc.html", "/webjars/**").permitAll()
                   .antMatchers("/agent/session/**").permitAll() // Agent 会话存储：Java 侧业务审计，供 Agent 调用
                    // ---- 支付相关（关键） ----
                   .antMatchers("/pay/notify", "/pay/return").permitAll()
                    // /refund/notify 是第三方退款结果回调，与 /pay/notify 同理：
                    // 免登录，安全性由「验签 + 金额比对 + 退款单状态机 CAS」三重保证。
                   .antMatchers("/refund/notify").permitAll()
                    // 模拟第三方支付平台（MockPay）——它扮演支付宝，本身是对外提供服务的角色
                   .antMatchers("/mock-pay/**", "/mock-pay-cashier.html").permitAll()
                    // 静态资源（商城前端页面，免登录）
                    .antMatchers("/", "/index.html", "/static/**", "/favicon.ico",
                            "/*.html", "/*.js", "/*.css", "/images/**").permitAll()
                    .antMatchers("/error").permitAll()
                    .anyRequest().authenticated()
                .and()
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        log.info("SecurityFilterChain 配置完成");
        return http.build();
    }

    // 密码加密Bean
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 认证管理器Bean
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

}