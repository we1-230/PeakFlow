package com.zhou.peakflow.constant;

/**
 * 秒杀域常量（下单规则、超时档位、消费者组集中管理）
 */
public final class SeckillConstants {

    private SeckillConstants() {
    }

    // ---- 下单 ----

    /** 秒杀单笔订单购买数量（秒杀一人一单一件，不受普通订单上限约束） */
    public static final int SECKILL_ORDER_QUANTITY = 1;

    /** MQ 同步发送超时（毫秒）：失败触发降级同步落库 */
    public static final long MQ_SEND_TIMEOUT_MILLIS = 3000;

    /** 秒杀商品列表默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    // ---- 超时关单 ----

    /** 秒杀订单超时时间（分钟）：秒杀库存稀缺占用成本高，比普通订单 30 分钟更短 */
    public static final long SECKILL_TIMEOUT_MINUTES = 10;

    /** RocketMQ 延迟档位 14 = 10 分钟（固定档位表没有 15 分钟，必须与 SECKILL_TIMEOUT_MINUTES 对齐） */
    public static final int SECKILL_TIMEOUT_DELAY_LEVEL = 14;

    // ---- 消费者 ----

    /** 秒杀消费者组（死信 Topic 固定规则：%DLQ% + 消费者组名） */
    public static final String SECKILL_CONSUMER_GROUP = "seckill_consumer_group";
}
