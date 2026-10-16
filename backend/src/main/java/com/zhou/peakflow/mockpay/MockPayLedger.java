package com.zhou.peakflow.mockpay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MockPay（模拟第三方支付平台）的交易 / 退款台账。
 * 台账必须存共享 Redis：原先在 Controller 的 static Map 里，多实例下会出现「收款记在 A、
 * 退款落到 B」误判原交易不存在、同一退款单号被两个实例各处理一次而重复退款、已退款集合互不
 * 可见导致换单号再退一次 —— 三类都是资损。改用 SETNX(setIfAbsent) 原子占位，「是否已受理」
 * 在实例间天然一致（谁先抢到谁受理）；Redis 不可用时降级为本实例内存台账。
 */
@Slf4j
@Component
public class MockPayLedger {

    private static final String LOG_PREFIX = "【MockPay 台账】";
    private static final String PREFIX = "mockpay:";
    private static final String KEY_PAID = PREFIX + "paid:";
    private static final String KEY_REFUND = PREFIX + "refund:";
    private static final String KEY_REFUNDED = PREFIX + "refunded:";

    /** 台账保留时长：真实第三方会长期留存交易/退款记录，这里取 30 天（远超可退款窗口 15 天） */
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate stringRedisTemplate;

    // Redis 不可用时的降级台账（仅保证单实例可用，多实例一致性失效）
    private final Map<String, String> degradedPaid = new ConcurrentHashMap<>();
    private final Map<String, String> degradedRefund = new ConcurrentHashMap<>();
    private final Set<String> degradedRefundedTrades = ConcurrentHashMap.newKeySet();

    public MockPayLedger(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 收款台账

    /**
     * 受理一笔收款（原子占位）。
     *
     * @return true = 本次为首次受理；false = 该交易此前已被受理（重复付款请求，应复用原交易号）
     */
    public boolean markPaid(String outTradeNo, String tradeNo) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                    .setIfAbsent(KEY_PAID + outTradeNo, tradeNo, TTL));
        } catch (RedisConnectionFailureException e) {
            warnDegraded(e);
            return degradedPaid.putIfAbsent(outTradeNo, tradeNo) == null;
        }
    }

    /** 读取该交易已受理的第三方交易号；未受理过返回 null */
    public String getPaidTradeNo(String outTradeNo) {
        try {
            return stringRedisTemplate.opsForValue().get(KEY_PAID + outTradeNo);
        } catch (RedisConnectionFailureException e) {
            warnDegraded(e);
            return degradedPaid.get(outTradeNo);
        }
    }

    /** 原交易是否已收款（退款前置校验；多实例下读的是同一份共享台账） */
    public boolean isPaid(String outTradeNo) {
        if (outTradeNo == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(KEY_PAID + outTradeNo));
        } catch (RedisConnectionFailureException e) {
            warnDegraded(e);
            return degradedPaid.containsKey(outTradeNo);
        }
    }

    // 退款台账

    /** 读取该退款单已受理的第三方退款流水号；未受理过返回 null（幂等重发用） */
    public String getRefundTradeNo(String outRefundNo) {
        try {
            return stringRedisTemplate.opsForValue().get(KEY_REFUND + outRefundNo);
        } catch (RedisConnectionFailureException e) {
            warnDegraded(e);
            return degradedRefund.get(outRefundNo);
        }
    }

    /**
     * 受理一个退款单号（原子占位，防止并发/多实例把同一个退款单处理两次）。
     *
     * @return true = 本次抢到受理权；false = 已被别的实例受理，应走幂等重发
     */
    public boolean claimRefund(String outRefundNo, String refundTradeNo) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                    .setIfAbsent(KEY_REFUND + outRefundNo, refundTradeNo, TTL));
        } catch (RedisConnectionFailureException e) {
            warnDegraded(e);
            return degradedRefund.putIfAbsent(outRefundNo, refundTradeNo) == null;
        }
    }

    /** 放弃本次退款受理占位（该交易被判定为「已退款」时调用，避免白占一个退款单号） */
    public void releaseRefund(String outRefundNo) {
        try {
            stringRedisTemplate.delete(KEY_REFUND + outRefundNo);
        } catch (RedisConnectionFailureException e) {
            warnDegraded(e);
            degradedRefund.remove(outRefundNo);
        }
    }

    /**
     * 标记「该交易已发起过退款」（资损红线：同一笔交易只允许退一次）。
     *
     * @return true = 本次为首次退款；false = 该交易已退款，必须拒绝
     */
    public boolean claimTradeRefunded(String outTradeNo) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                    .setIfAbsent(KEY_REFUNDED + outTradeNo, "1", TTL));
        } catch (RedisConnectionFailureException e) {
            warnDegraded(e);
            return degradedRefundedTrades.add(outTradeNo);
        }
    }

    private void warnDegraded(Exception e) {
        log.warn("{}Redis 不可用，MockPay 台账降级为本实例内存（单机可用，但多实例一致性失效）", LOG_PREFIX, e);
    }
}

