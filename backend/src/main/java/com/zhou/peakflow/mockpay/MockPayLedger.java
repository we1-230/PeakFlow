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
 *
 * 台账原先写在 MockPayController 的 static Map 里，只存在于单个 JVM 内存中。
 * 单实例看不出问题，一旦同机起多实例或水平扩容，就会暴露三类资损缺陷：
 * 收款记在实例 A、退款请求落到实例 B，会误判「原交易不存在」而拒绝退款；
 * 同一退款单号被负载均衡打到两个实例，两边各自判定「没处理过」导致重复退款；
 * 进程内的已退款集合互不可见，换个退款单号就能对同一笔交易再退一次。
 *
 * 修法是把台账搬到所有实例共享的 Redis，用 SETNX（setIfAbsent）做原子占位，
 * 让「是否已受理」的判定在实例间天然一致——谁先抢到谁受理，后到的走幂等重发或拒绝。
 *
 * Key 设计（与业务 Redis 同实例，按环境逻辑库隔离）：
 *   mockpay:paid:{outTradeNo}     -> tradeNo       已收款台账（退款时校验原交易是否存在）
 *   mockpay:refund:{outRefundNo}  -> refundTradeNo 退款单台账（同一退款单号幂等重发）
 *   mockpay:refunded:{outTradeNo} -> 1             该交易已发起过退款（防同一交易重复退款）
 *
 * 降级策略与项目既有的 Redis 故障降级保持一致：Redis 不可用时退化为本实例内存台账，
 * 单机仍能跑通支付 / 退款演示，但多实例一致性失效（退化成修复前的行为），日志会明确告警。
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

