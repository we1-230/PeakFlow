package com.zhou.peakflow.mockpay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MockPayLedger <b>单元测试</b>：台账从 JVM 内存 Map 搬到 Redis 共享存储。
 *
 * <p>要证明两件事：① 落到了共享存储且用了原子语义 —— 收款/退款/防重复退款三个台账都走
 * {@code setIfAbsent}（SETNX）写 Redis，key 命名固定（{@code mockpay:paid:*} / {@code mockpay:refund:*} /
 * {@code mockpay:refunded:*}），多实例读同一份数据、"谁先抢到谁受理"由 Redis 原子保证；
 * ② Redis 故障时降级为本实例内存（仍返回正确语义），不把支付链路打挂。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockPay 台账单元测试（Redis 共享存储 + 降级）")
class MockPayLedgerTest {

    private static final String OUT_TRADE_NO = "ORD1234567890";
    private static final String TRADE_NO = "MOCK20261016000000123456";
    private static final String OUT_REFUND_NO = "REF1234567890-1";
    private static final String REFUND_TRADE_NO = "MOCKREF20261016000000654321";

    private static final String KEY_PAID = "mockpay:paid:" + OUT_TRADE_NO;
    private static final String KEY_REFUND = "mockpay:refund:" + OUT_REFUND_NO;
    private static final String KEY_REFUNDED = "mockpay:refunded:" + OUT_TRADE_NO;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MockPayLedger ledger;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        ledger = new MockPayLedger(stringRedisTemplate);
    }

    // 一、收款台账

    @Test
    @DisplayName("[共享存储] 首次收款：SETNX 写 mockpay:paid:{outTradeNo} 并带 TTL → 返回 true")
    void markPaid_firstTime() {
        when(valueOperations.setIfAbsent(eq(KEY_PAID), eq(TRADE_NO), any(Duration.class))).thenReturn(true);

        assertTrue(ledger.markPaid(OUT_TRADE_NO, TRADE_NO));

        verify(valueOperations).setIfAbsent(eq(KEY_PAID), eq(TRADE_NO), any(Duration.class));
    }

    @Test
    @DisplayName("[幂等] 重复收款：SETNX 返回 false → 判定为重复请求（多实例下由 Redis 原子仲裁）")
    void markPaid_duplicate() {
        when(valueOperations.setIfAbsent(eq(KEY_PAID), eq(TRADE_NO), any(Duration.class))).thenReturn(false);

        assertFalse(ledger.markPaid(OUT_TRADE_NO, TRADE_NO));
    }

    @Test
    @DisplayName("[共享读取] 读取已受理交易号：从 Redis 取 mockpay:paid:{outTradeNo}")
    void getPaidTradeNo() {
        when(valueOperations.get(KEY_PAID)).thenReturn(TRADE_NO);

        assertEquals(TRADE_NO, ledger.getPaidTradeNo(OUT_TRADE_NO));
    }

    @Test
    @DisplayName("[退款前置] 原交易是否已收款：null 直接返回 false（不触发 Redis），否则按 hasKey 判定")
    void isPaid() {
        assertFalse(ledger.isPaid(null));
        verify(stringRedisTemplate, never()).hasKey(anyString());

        when(stringRedisTemplate.hasKey(KEY_PAID)).thenReturn(true);
        assertTrue(ledger.isPaid(OUT_TRADE_NO));
    }

    // 二、退款台账

    @Test
    @DisplayName("[并发仲裁] 受理退款单：SETNX 写 mockpay:refund:{outRefundNo} → 只有一方抢到")
    void claimRefund() {
        when(valueOperations.setIfAbsent(eq(KEY_REFUND), eq(REFUND_TRADE_NO), any(Duration.class)))
                .thenReturn(true, false);

        assertTrue(ledger.claimRefund(OUT_REFUND_NO, REFUND_TRADE_NO));
        assertFalse(ledger.claimRefund(OUT_REFUND_NO, "MOCKREF-OTHER"));
    }

    @Test
    @DisplayName("[幂等重发] 读取退款单已受理的流水号 / 未受理返回 null")
    void getRefundTradeNo() {
        when(valueOperations.get(KEY_REFUND)).thenReturn(REFUND_TRADE_NO, (String) null);

        assertEquals(REFUND_TRADE_NO, ledger.getRefundTradeNo(OUT_REFUND_NO));
        assertNull(ledger.getRefundTradeNo(OUT_REFUND_NO));
    }

    @Test
    @DisplayName("[放弃受理] 释放退款单占位：删除 mockpay:refund:{outRefundNo}")
    void releaseRefund() {
        ledger.releaseRefund(OUT_REFUND_NO);

        verify(stringRedisTemplate).delete(KEY_REFUND);
    }

    @Test
    @DisplayName("[资损红线] 同一交易只允许退一次：SETNX 写 mockpay:refunded:{outTradeNo}，第二次必须为 false")
    void claimTradeRefunded() {
        when(valueOperations.setIfAbsent(eq(KEY_REFUNDED), eq("1"), any(Duration.class)))
                .thenReturn(true, false);

        assertTrue(ledger.claimTradeRefunded(OUT_TRADE_NO));
        assertFalse(ledger.claimTradeRefunded(OUT_TRADE_NO));
    }

    // 三、Redis 故障降级（保证支付链路不因缓存故障直接不可用）

    @Test
    @DisplayName("[降级] Redis 不可用：退化为本实例内存台账，语义仍正确（首次 true / 重复 false / 可读可查）")
    void degraded_onRedisFailure() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("redis down");
        when(stringRedisTemplate.opsForValue()).thenThrow(down);
        when(stringRedisTemplate.hasKey(anyString())).thenThrow(down);
        when(stringRedisTemplate.delete(anyString())).thenThrow(down);

        assertTrue(ledger.markPaid(OUT_TRADE_NO, TRADE_NO), "降级后首次受理仍应成功");
        assertFalse(ledger.markPaid(OUT_TRADE_NO, "MOCK-ANOTHER"), "降级后重复受理仍应被识别出来");
        assertEquals(TRADE_NO, ledger.getPaidTradeNo(OUT_TRADE_NO));
        assertTrue(ledger.isPaid(OUT_TRADE_NO));

        assertTrue(ledger.claimTradeRefunded(OUT_TRADE_NO), "降级后首次退款标记成功");
        assertFalse(ledger.claimTradeRefunded(OUT_TRADE_NO), "降级后同一交易重复退款必须拒绝");

        assertTrue(ledger.claimRefund(OUT_REFUND_NO, REFUND_TRADE_NO));
        ledger.releaseRefund(OUT_REFUND_NO);
        assertNull(ledger.getRefundTradeNo(OUT_REFUND_NO), "释放占位后应查不到该退款单");
    }
}
