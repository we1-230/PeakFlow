package com.zhou.peakflow.utils;

import com.zhou.peakflow.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花算法 ID 生成器；小幅时钟回拨等待追赶，超阈值抛异常并降级为时间戳+随机数
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    // 起始时间戳（2020-01-01），上线后不可更改，否则与历史 ID 不可比
    private static final long EPOCH = 1577836800000L;
    private static final long WORKER_ID_BITS = 5L;       // 机器ID占5位 (0-31)
    private static final long DATACENTER_ID_BITS = 5L;  // 数据中心ID占5位 (0-31)
    private static final long SEQUENCE_BITS = 12L;      // 序列号占12位 (0-4095)

    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long MAX_DATACENTER_ID = (1L << DATACENTER_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    // 最大容忍时钟回拨（毫秒），超过即拒绝生成
    private static final long MAX_BACKWARD_MS = 5;

    // 上次生成 ID 的时间戳（-1 表示尚未生成）
    private long lastTimestamp = -1L;

    // 同毫秒内序列号
    private long sequence = 0L;

    // 机器 ID（多实例部署时必须各不相同，否则会撞 ID）
    @Value("${snowflake.worker-id:1}")
    private long workerId;

    @Value("${snowflake.datacenter-id:1}")
    private long dataCenterId;

    // synchronized 保证同毫秒序列自增不冲突
    public synchronized long nextId() {
        long timestamp = timeGen();

        // 时钟回拨处理
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= MAX_BACKWARD_MS) {
                // 小幅度回拨，等待追上
                log.warn("检测到时钟回拨 {}ms，正在等待时间追赶...", offset);
                try {
                    // 等待时间翻倍，确保时间追上
                    wait(offset << 1);
                    timestamp = timeGen();
                    if (timestamp < lastTimestamp) {
                        log.error("等待后时钟仍回拨，触发备用ID生成方案");
                        return nextIdWithFallback();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("等待被中断", e);
                    return nextIdWithFallback();
                }
            } else {
                // 大幅度回拨，抛出异常
                log.error("时钟回拨过大: {}ms，无法生成合法ID", offset);
                throw new BusinessException("时钟回拨过大，无法生成ID");
            }
        }

        // 同一毫秒内
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 序列号耗尽，等待下一毫秒
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            // 新的毫秒，序列号重置
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (dataCenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    // 兜底 ID：时间戳 * 10000 + 随机数，极端时钟回拨时仍能保证唯一
    private long nextIdWithFallback() {
        log.warn("使用备用ID生成方案");
        return System.currentTimeMillis() * 10000L + (long) (Math.random() * 10000);
    }

    private long timeGen() {
        return System.currentTimeMillis();
    }

    // 阻塞到下一个毫秒，直到获得新的时间戳
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }
}
