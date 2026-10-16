package com.zhou.peakflow.constant;

/**
 * 订单常量配置
 */
public final class OrderConstants {

    private OrderConstants() {
    }

    /** 订单超时时间（分钟）：未支付自动关单，改这里必须同步改 ORDER_TIMEOUT_DELAY_LEVEL */
    public static final long ORDER_TIMEOUT_MINUTES = 30;

    /**
     * 支付单有效期（分钟）：从支付单创建时刻算起，超过即不可再被支付。
     */
    public static final long PAY_RECORD_VALID_MINUTES = 15;

    /**
     * 发货后自动确认收货天数。
     */
    public static final int AUTO_CONFIRM_RECEIPT_DAYS = 7;

    /** 兜底扫描任务单批处理条数（防止一次扫描把内存和 DB 打满） */
    public static final int SWEEP_BATCH_SIZE = 200;

    /** 订单号前缀 */
    public static final String ORDER_NO_PREFIX = "ORD";

    /** 秒杀订单号前缀 */
    public static final String SECKILL_ORDER_NO_PREFIX = "SKO";

    /** 单个订单最大商品种类数（它决定一笔订单最多能锁住多少库存） */
    public static final int MAX_ORDER_ITEMS = 10;

    /** 单个商品单次最大购买数量（防恶意占库存的关键阈值；秒杀订单写死 1，不受此限） */
    public static final int MAX_PRODUCT_QUANTITY = 10;

    /** 订单类型：普通订单 */
    public static final int ORDER_TYPE_NORMAL = 0;

    /** 订单类型：秒杀订单 */
    public static final int ORDER_TYPE_SECKILL = 1;

    /** 分布式锁等待时间（秒） */
    public static final long LOCK_WAIT_SECONDS = 3;

    /** 分布式锁持有时间（秒） */
    public static final long LOCK_HOLD_SECONDS = 10;

    /** 秒杀 MQ Topic */
    public static final String SECKILL_TOPIC = "seckill_topic";

    /** 订单超时延迟消息 Topic */
    public static final String ORDER_TIMEOUT_TOPIC = "order_timeout_topic";

    /**
     * 订单超时消费者的消费者组名。
     */
    public static final String ORDER_TIMEOUT_CONSUMER_GROUP = "order-timeout-consumer-group";

    // 订单超时延迟消息等级（RocketMQ 固定档位，必须与 ORDER_TIMEOUT_MINUTES 对齐）
    public static final int ORDER_TIMEOUT_DELAY_LEVEL = 16;
}
