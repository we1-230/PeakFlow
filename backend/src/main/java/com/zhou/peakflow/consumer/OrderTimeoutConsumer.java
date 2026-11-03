package com.zhou.peakflow.consumer;

import com.zhou.peakflow.constant.OrderConstants;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 订单超时延迟消息消费者：到点触发关单；基础设施异常抛出让 MQ 重试，耗尽进死信
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = OrderConstants.ORDER_TIMEOUT_TOPIC,
        consumerGroup = OrderConstants.ORDER_TIMEOUT_CONSUMER_GROUP,
        maxReconsumeTimes = 3
)
public class OrderTimeoutConsumer implements RocketMQListener<String> {
    private final OrderService orderService;

    private static final String LOG_PREFIX = "【订单超时消费者】";


    @Override
    public void onMessage(String s) {
        log.info("{}收到延时订单消息：{}", LOG_PREFIX, s);

        Long orderId = null;
        try {
            orderId = Long.parseLong(s);
        } catch (Exception e) {
            log.error("{}消息解析失败", LOG_PREFIX, e);
            // 交 MQ 重试，最终进死信
            throw new RuntimeException("消息解析失败", e);
        }

        if (orderId == null || orderId <= 0) {
            log.error("{}消息解析失败：{}", LOG_PREFIX, s);
            throw new RuntimeException("消息解析失败");
        }

        orderService.timeoutCancelOrder(orderId);
    }
}

