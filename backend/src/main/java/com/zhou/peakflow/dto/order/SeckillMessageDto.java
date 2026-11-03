package com.zhou.peakflow.dto.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀 MQ 消息体（下单入口发出，消费者异步落库）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessageDto {

    /** 用户ID */
    private Long userId;

    /** 秒杀商品ID */
    private Long productId;

    /** 收货地址ID */
    private Long addressId;

    /** 本次抢购请求标识（结果轮询与幂等门闩共用） */
    private String requestId;
}
