package com.zhou.peakflow.config;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.UUID;

/**
 * 链路 TraceId 过滤器：每请求生成 traceId 写入 MDC 并回传响应头，最高优先级最先执行
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    // MDC里面的key，和logback里面%X{traceId}保持一致
    private static final String TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 生成唯一traceId
        String traceId = UUID.randomUUID().toString().replace("-", "");
        try {
            // 放入MDC
            MDC.put(TRACE_ID_KEY, traceId);
            // 把traceId返回给前端，方便用户反馈问题时直接复制这个id
            response.setHeader("X-Trace-Id", traceId);
            // 继续执行后续controller/service
            filterChain.doFilter(request, response);
        } finally {
            // 线程池复用线程，如果不清空，旧traceId会带到下一个请求，串号！
            MDC.remove(TRACE_ID_KEY);
        }
    }
}