package com.zhou.peakflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地降级上传目录的静态映射（配合 AliOssUtil 的降级分支）。
 *
 * 全局 spring.web.resources.add-mappings=false 关掉了默认静态资源，
 * 这里只精确放开 /uploads/**，且只指向独立目录 —— 不回退 classpath，
 * 避免把 jar 内资源一并暴露出去。
 */
@Configuration
public class UploadWebConfig implements WebMvcConfigurer {

    @Value("${app.upload.local-dir:./uploads}")
    private String localDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(localDir).toAbsolutePath().normalize();
        String location = dir.toUri().toString();
        // Spring 要求资源目录以 "/" 结尾，否则会被当成文件前缀拼错
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
