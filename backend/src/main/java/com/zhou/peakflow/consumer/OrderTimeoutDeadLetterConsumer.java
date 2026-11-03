package com.zhou.peakflow.consumer;

import com.zhou.peakflow.constant.OrderConstants;
import com.zhou.peakflow.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订单超时死信消费者：重试耗尽后的最终出口（告警 + 即时触发一轮兜底扫描）
 */
@Slf4j
@Component
@RequiredArgsConstructor
// 死信 Topic 固定规则：%DLQ% + 消费者组名（RocketMQ 自动投递）
@RocketMQMessageListener(
        topic = "%DLQ%" + OrderConstants.ORDER_TIMEOUT_CONSUMER_GROUP,
        consumerGroup = "order_timeout_dlq_group"
)
public class OrderTimeoutDeadLetterConsumer implements RocketMQListener<MessageExt> {

    private final OrderService orderService;

    private static final String LOG_PREFIX = "【订单超时死信队列】";

    @Override
    public void onMessage(MessageExt message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        // 1.  告警：这是死信，说明主路径（MQ 准点触发）连续失败
        log.error("{}【告警】订单超时消息重试耗尽进入死信，body={}, 已重试次数={}，"
                        + "将由兜底扫描接管，请排查 MQ 与数据库健康状态",
                LOG_PREFIX, body, message.getReconsumeTimes());

        // 2.  立即触发一轮兜底扫描（比等定时任务到期更快）

        try {
            orderService.sweepTimeoutPendingOrders(OrderConstants.SWEEP_BATCH_SIZE);
        } catch (Exception e) {
            // 必须吞掉：抛出去会让消息再次进入死信队列，形成死信套娃
            log.error("{}死信触发兜底扫描失败，交由定时任务接管，body={}", LOG_PREFIX, body, e);
        }
    }
}
