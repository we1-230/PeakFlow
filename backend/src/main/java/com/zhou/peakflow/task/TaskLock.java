package com.zhou.peakflow.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 定时任务的分布式互斥执行器：多实例下把「同一轮调度」收敛到单个节点。
 *
 * 锁不设 leaseTime，走 Redisson watchdog 自动续期——任务跑多久锁就持多久；
 * 节点崩溃后 watchdog 停止续期，锁约 30s 后自动释放，不会把任务永久锁死。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskLock {

    private final RedissonClient redissonClient;

    /**
     * 抢到锁才执行 {@code action}；抢不到说明其他实例正在跑，本轮跳过。
     *
     * @param lockKey  锁键，按任务粒度唯一（不同任务用不同键，否则会互相饿死）
     * @param taskName 日志可读名
     * @param action   任务体
     */
    public void runExclusively(String lockKey, String taskName, Runnable action) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            // waitTime=0：不排队。多实例互相等待只会让任务越积越多，跳过、交给下一轮更安全
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("【{}】本轮已由其他实例执行，跳过", taskName);
                return;
            }
            action.run();
        } catch (InterruptedException e) {
            // 恢复中断标记，让上层能感知线程被要求停止
            Thread.currentThread().interrupt();
            log.warn("【{}】等待分布式锁被中断", taskName);
        } catch (Exception e) {
            // 兜住异常：单轮失败不得中断后续调度
            log.error("【{}】执行异常", taskName, e);
        } finally {
            // 只有确实持锁的线程才能释放，否则 unlock 会抛 IllegalMonitorStateException
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
