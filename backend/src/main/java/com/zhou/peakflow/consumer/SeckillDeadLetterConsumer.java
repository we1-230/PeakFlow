package com.zhou.peakflow.consumer;

import com.alibaba.fastjson.JSON;
import com.zhou.peakflow.constant.SeckillConstants;
import com.zhou.peakflow.dto.order.SeckillMessageDto;
import com.zhou.peakflow.service.impl.SeckillOrderTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_ORDER_SUCCESS;
import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_PROCESS;
import static com.zhou.peakflow.constant.SeckillConstants.SECKILL_CONSUMER_GROUP;

/**
 * 秒杀死信消费者：消息重试耗尽后的最终兜底（写失败结果 + 释放门闩 + 回补库存）
 */
@Component
@RequiredArgsConstructor
@Slf4j
// 死信 Topic 固定规则：%DLQ% + 消费者组名（RocketMQ 自动投递）
@RocketMQMessageListener(
        topic = "%DLQ%" + SECKILL_CONSUMER_GROUP,
        consumerGroup = "seckill_order_dlq_group"
)
public class SeckillDeadLetterConsumer implements RocketMQListener<MessageExt> {

    private final SeckillOrderTxService seckillOrderTxService;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String LOG_PREFIX = "【秒杀死信队列】";

    @Override
    public void onMessage(MessageExt message) {
        log.error("{}消息重试耗尽，执行最终兜底，msgId={}", LOG_PREFIX, message.getMsgId());
        log.error("{}消息内容={}", LOG_PREFIX, new String(message.getBody(), StandardCharsets.UTF_8));

        try {
            SeckillMessageDto mqMsg = JSON.parseObject(message.getBody(), SeckillMessageDto.class);
            Long userId = mqMsg.getUserId();
            Long productId = mqMsg.getProductId();
            String requestId = mqMsg.getRequestId();
            releasePurchaseGate(userId, productId);
            // 1. 写失败结果（先写：用户轮询立刻看到终态而不是一直排队）
            seckillOrderTxService.writeFailResult(requestId, "系统繁忙，请稍后重试");
            // 2. 释放购买权门闩（订单未落库，不能挡用户重试）
            stringRedisTemplate.delete(SECKILL_ORDER_SUCCESS + ":" + userId + ":" + productId);
            // 3. 回补 Redis 库存预扣（仅当 Key 存在，防幽灵库存）
            seckillOrderTxService.rollbackRedisStock(productId, SeckillConstants.SECKILL_ORDER_QUANTITY);

            log.error("{}兜底处理完成，userId={}, productId={}, requestId={}",
                    LOG_PREFIX, userId, productId, requestId);
        } catch (Exception e) {
            // 死信兜底也失败：只能告警人工介入（此时用户会一直"排队中"直到结果 Key 过期）
            log.error("{}兜底处理异常，需人工介入", LOG_PREFIX, e);
        }
    }
    // 释放购买权门闩（仅订单未落库的失败路径调用）
    private void releasePurchaseGate(Long userId, Long productId) {
        stringRedisTemplate.delete(SECKILL_ORDER_SUCCESS + ":" + userId + ":" + productId);
    }
}
