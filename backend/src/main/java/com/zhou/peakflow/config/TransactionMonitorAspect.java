package com.zhou.peakflow.config;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务耗时监控切面：包住 @Transactional 方法，超阈值只告警、不干预业务
 */
@Slf4j
@Aspect
@Component
public class TransactionMonitorAspect {

    /** 长事务告警阈值（毫秒）：超 5s 记一条 warn，仅观测、不回滚 */
    private static final long TRANSACTION_WARN_THRESHOLD = 5000;

    // 拦截所有带有 @Transactional 注解的方法
    @Around("@annotation(transactional)")
    public Object around(ProceedingJoinPoint joinPoint, Transactional transactional) throws Throwable {
        long start = System.currentTimeMillis();
        String traceId = MDC.get("traceId");
        String methodName = joinPoint.getSignature().toShortString();

        try {
            // 执行被@Transactional标记的业务方法
            return joinPoint.proceed();
        } finally {
            long costMs = System.currentTimeMillis() - start;
            if (costMs > TRANSACTION_WARN_THRESHOLD) {
                log.warn("【长事务告警】traceId={}, method={}, 事务耗时={}ms，超过阈值{}ms",
                        traceId, methodName, costMs, TRANSACTION_WARN_THRESHOLD);
            }
        }
    }
}