package com.zhou.peakflow.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池装配：容量走配置，拒绝策略用 CallerRuns 保证任务不静默丢失
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    // 从配置文件注入，默认值兜底
    @Value("${threadpool.async.core-pool-size:${cpu.core}}")
    private int corePoolSize;

    @Value("${threadpool.async.max-pool-size:${cpu.core}*2}")
    private int maxPoolSize;

    @Value("${threadpool.async.queue-capacity:500}")
    private int queueCapacity;

    @Value("${threadpool.async.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    @Value("${threadpool.async.thread-name-prefix:async-}")
    private String threadNamePrefix;

    @Bean("asyncExecutor")
    public ThreadPoolTaskExecutor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);

        // 拒绝策略：CallerRunsPolicy（调用者线程执行，避免任务丢失）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 关闭时等待任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        log.info("线程池配置完成，corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }
}
