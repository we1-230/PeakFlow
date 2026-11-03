package com.zhou.peakflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 鉴权白名单（前缀 security）：供 JwtAuthenticationFilter 跳过免登录路径
 */
@Component
@Data
@ConfigurationProperties(prefix = "security")
public class WhiteListConfig {
    /** 免登录路径列表，支持 Ant 通配 */
    private List<String> whiteList;

}
