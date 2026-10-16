package com.zhou.peakflow.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static com.zhou.peakflow.constant.CacheExpireConstants.ORDER_SEQ_SECONDS;
import static com.zhou.peakflow.constant.RedisKeyConstant.ORDER_SEQ;

/**
 * 订单号生成器：时间戳 + 机器号 + Redis 自增序列（避免多实例撞号）
 */
@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${machine.id:1}")
    private int machineId;

    public String generate(String prefix) {
        String timestamp = LocalDateTime.now().format(FORMATTER);

        // 使用 Redis 生成全局唯一序列号
        String redisKey = ORDER_SEQ + ":" + timestamp;
        Long seq = redisTemplate.opsForValue().increment(redisKey);
        redisTemplate.expire(redisKey, ORDER_SEQ_SECONDS, TimeUnit.SECONDS);

        return String.format("%s%s%02d%04d", prefix, timestamp, machineId, seq % 10000);
    }
}