package com.zhou.peakflow.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * StringRedisTemplate 的轻量封装（只做读写删，业务语义由调用方负责）
 */
@Component
@RequiredArgsConstructor
public class RedisUtils {

    private final StringRedisTemplate stringRedisTemplate;

    /** 写入并设置过期时间 */
    public void set(String key, String value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /** 读取，不存在返回 null */
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /** 删除 */
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }
}
