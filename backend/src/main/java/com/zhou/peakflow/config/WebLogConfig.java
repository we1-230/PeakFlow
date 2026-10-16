package com.zhou.peakflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

/**
 * 请求日志：打印 URL 与请求体（截断 2000 字符），刻意不打印 Header 以免泄露 Authorization
 */
@Configuration
public class WebLogConfig {
    @Bean
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        CommonsRequestLoggingFilter filter = new CommonsRequestLoggingFilter();
        filter.setIncludeQueryString(true);    // url查询参数
        filter.setIncludePayload(true);        // 打印请求body
        filter.setMaxPayloadLength(2000);      // body最大打印长度
        filter.setIncludeHeaders(false);
        return filter;
    }
}