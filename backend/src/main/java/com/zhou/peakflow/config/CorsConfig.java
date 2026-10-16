package com.zhou.peakflow.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.List;

/**
 * CORS 配置：生产按白名单收敛，未配置时才回退通配符（仅便于本地开发）
 */
@Slf4j
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:}")
    private List<String> allowedOrigins;

    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private List<String> allowedMethods;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 配置了 cors.allowed-origins 时按白名单收敛（生产必须收敛）；
        // 未配置（列表为空）时才回退到通配符，保证本地开发不被阻塞。
        List<String> patterns = (allowedOrigins == null || allowedOrigins.isEmpty())
                ? List.of("*")
                : allowedOrigins;
        registry.addMapping("/**")
                .allowedOriginPatterns(patterns.toArray(new String[0]))
                .allowedMethods(allowedMethods.toArray(new String[0]))
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);

        log.info("CORS配置加载完成，允许来源={}", patterns);
    }
}
