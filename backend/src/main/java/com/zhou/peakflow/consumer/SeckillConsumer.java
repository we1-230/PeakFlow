package com.zhou.peakflow.consumer;

import com.alibaba.fastjson.JSON;
import com.zhou.peakflow.constant.SeckillConstants;
import com.zhou.peakflow.dto.order.SeckillMessageDto;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.service.impl.SeckillOrderTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

import static com.zhou.peakflow.constant.CacheExpireConstants.SECKILL_IDEMPOTENT_HOURS;
import static com.zhou.peakflow.constant.CacheExpireConstants.SECKILL_PROCESS_HOURS;
import static com.zhou.peakflow.constant.OrderConstants.SECKILL_TOPIC;
import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_ORDER_SUCCESS;
import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_PROCESS;
import static com.zhou.peakflow.constant.SeckillConstants.SECKILL_CONSUMER_GROUP;

/**
 * 秒杀消息消费者：双 SETNX 门闩（消息幂等 + 单人单次购买权）+ 事务落库 + 基础设施异常交 MQ 重试
 */
@Service
@RocketMQMessageListener(
        topic = SECKILL_TOPIC,
        consumerGroup = SECKILL_CONSUMER_GROUP,
        consumeThreadNumber = 64,
        consumeThreadMax = 64,
        maxReconsumeTimes = 3
)
@RequiredArgsConstructor
@Slf4j
public class SeckillConsumer implements RocketMQListener<String> {

    private final SeckillOrderTxService seckillOrderTxService;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String LOG_PREFIX = "【秒杀消费者】";

    @Override
    public void onMessage(String message) {
        // 0. 解析消息：坏消息抛出让 MQ 重试，耗尽后进死信
        SeckillMessageDto mqMsg;
        try {
            mqMsg = JSON.parseObject(message, SeckillMessageDto.class);
        } catch (Exception e) {
            log.error("{}消息解析失败，丢弃：{}", LOG_PREFIX, message, e);
            throw new RuntimeException("消息解析失败", e);  // 交 MQ 重试，最终进死信
        }
        Long userId = mqMsg.getUserId();
        Long productId = mqMsg.getProductId();
        String requestId = mqMsg.getRequestId();

        // 1. 消息级幂等门闩（SETNX 原子）：防 MQ 重投递/重试造成重复消费
        if (!acquireProcessGate(requestId)) {
            log.warn("{}消息已处理过，跳过，requestId={}", LOG_PREFIX, requestId);
            return;
        }

        try {

            try {
                // 3. 事务落库（DB 权威校验 + 扣库存 + 订单明细，任何一步失败整体回滚）
                Order order = seckillOrderTxService.createSeckillOrder(userId, productId, mqMsg.getAddressId());

                // 4. 订单已落库：写结果 + 超时消息。失败只留痕绝不抛出（否则重试会重复建单）
                writeSuccessResultQuietly(requestId, order.getId(), order.getOrderNo());
                sendTimeoutMessageQuietly(order.getId());

            } catch (BusinessException e) {
                // 业务失败是终态（商品 / 地址 / 时间窗 / 库存 / 重复抢购）：写失败结果 + 回补 Redis 预扣。
                // 「购买权门闩」的处置要分两种情况，见下面分支说明。
                seckillOrderTxService.writeFailResult(requestId, e.getMessage());
                seckillOrderTxService.rollbackRedisStock(productId, SeckillConstants.SECKILL_ORDER_QUANTITY);

                if (ResultCode.SECKILL_REPEAT.getCode().equals(e.getCode())) {
                    // 情况一：数据库唯一索引 uk_seckill_user_product 拦下的「一人一单」重复抢购。
                    reacquirePurchaseGate(userId, productId);
                } else {
                    // 情况二：库存不足 / 地址非法 / 时间窗外等，属于「没成交」，
                    //   释放购买权让用户修正后可以重试（有意的 fail-open）
                    releasePurchaseGate(userId, productId);
                }

                log.warn("{}秒杀业务失败，requestId={}, userId={}, productId={}, code={}, reason={}",
                        LOG_PREFIX, requestId, userId, productId, e.getCode(), e.getMessage());
            }

        } catch (Exception e) {
            // 基础设施异常：事务已回滚、订单未落库；释放两道门闩后抛出交 MQ 重试，重试耗尽进死信兜底
            log.error("{}基础设施异常，交给MQ重试，requestId={}, userId={}, productId={}",
                    LOG_PREFIX, requestId, userId, productId, e);
            releasePurchaseGate(userId, productId);
            releaseProcessGate(requestId);
            throw new RuntimeException("秒杀消息处理失败，待重试", e);
        }
    }

    // 消息级幂等门闩：SETNX 原子占位（拿到才处理，杜绝 check-then-set 竞态）
    private boolean acquireProcessGate(String requestId) {
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(SECKILL_PROCESS + ":" + requestId, "1",
                        SECKILL_PROCESS_HOURS, TimeUnit.HOURS);
        return Boolean.TRUE.equals(acquired);
    }

    // 释放消息级门闩（仅基础设施异常需要重试时释放）
    private void releaseProcessGate(String requestId) {
        stringRedisTemplate.delete(SECKILL_PROCESS + ":" + requestId);
    }

    // 购买权门闩（一人一单）的职责划分
    //
    // 这里刻意没有 acquirePurchaseGate，请勿"补"回来。
    //
    // 购买权门闩的 key 是 seckill:order:success:{userId}:{productId}，由
    // 入口 doSeckill：用 SETNX 原子占下（在 Redis 预扣之前就完成拦截），
    // 且入口在成功发出 MQ 消息后不释放—— 这正是"一人一单"生效的地方。
    //
    // 曾经消费者这边也有一份 acquirePurchaseGate，但两处用的是同一个 key：
    // 入口已经占下，消费者再 setIfAbsent 必然返回 false ⇒ 会把每一笔成功请求都
    // 误判成"您已参与过该秒杀活动"（写失败结果 + 回滚 Redis 库存 + return）
    // ⇒ 秒杀永远无法成交、订单永远落不了库。所以消费者侧的"占位"已删除。
    //
    // 消费者侧只保留两个动作：
    //   · releasePurchaseGate  —— 业务失败(没成交) 或 基础设施异常(待重投) 时解开；
    //                             否则用户会被入口占下的门闩锁住，无法重试。
    //   · reacquirePurchaseGate —— 被 DB 唯一索引拦下时补占回来（自愈），
    //                             让该用户后续请求在入口就被挡住，不必白跑一趟 MQ。
    //
    // 而"一人一单"的最终正确性保障来自 DB：uk_seckill_user_product 唯一索引。

    // 释放购买权门闩（仅"没成交"的路径调用：业务失败 / 待重投）
    private void releasePurchaseGate(Long userId, Long productId) {
        stringRedisTemplate.delete(SECKILL_ORDER_SUCCESS + ":" + userId + ":" + productId);
    }

    // 补占购买权门闩：仅当「数据库唯一索引判定该用户已占过名额」时调用
    private void reacquirePurchaseGate(Long userId, Long productId) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(SECKILL_ORDER_SUCCESS + ":" + userId + ":" + productId, "1",
                            SECKILL_IDEMPOTENT_HOURS, TimeUnit.HOURS);
            log.info("{}【自愈】已补占购买权门闩，userId={}, productId={}, acquired={}",
                    LOG_PREFIX, userId, productId, acquired);
        } catch (Exception e) {
            // 自愈失败不影响主流程：数据库唯一索引才是最终防线
            log.error("{}【自愈】补占购买权门闩失败，userId={}, productId={}",
                    LOG_PREFIX, userId, productId, e);
        }
    }

    // 成功结果写入失败只留痕：订单已落库，不能抛出触发重试（重试会重复建单）
    private void writeSuccessResultQuietly(String requestId, Long orderId, String orderNo) {
        try {
            seckillOrderTxService.writeSuccessResult(requestId, orderId, orderNo);
        } catch (Exception e) {
            log.error("{}成功结果写入失败，用户轮询将超时，但订单已落库，requestId={}, orderId={}",
                    LOG_PREFIX, requestId, orderId, e);
        }
    }

    // 超时消息发送失败只留痕：生产者同步发送内部自带重试
    private void sendTimeoutMessageQuietly(Long orderId) {
        try {
            seckillOrderTxService.sendSeckillTimeoutDelayMessage(orderId);
        } catch (Exception e) {
            log.error("{}超时消息发送失败，该订单无法自动关单，需人工关注，orderId={}", LOG_PREFIX, orderId, e);
        }
    }
}
