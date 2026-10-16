package com.zhou.peakflow.consumer;

import com.zhou.peakflow.constant.SeckillConstants;
import com.zhou.peakflow.dto.order.SeckillMessageDto;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.service.impl.SeckillOrderTxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀消息消费者 <b>单元测试</b>：{@code SeckillConsumer#onMessage(message)}。
 *
 * <p>链路位置：入口 doSeckill（Redis 预扣 + 发 MQ）→ RocketMQ → SeckillConsumer#onMessage
 * （异步落库 + 结果回写 + 门闩处置）→ SeckillOrderTxService#createSeckillOrder（事务落库）。
 * 入口为私有方法，纯单测覆盖不到，见集成测试。</p>
 *
 * <p>被测方法流程：解析消息（失败抛 RuntimeException 交 MQ 重试）→ 消息级幂等门闩 acquireProcessGate
 * SETNX {@code seckill:process:{requestId}}（拿不到直接 return）→ createSeckillOrder 落库 →
 * 成功则写成功结果 + 发超时延迟消息（xxxQuietly，失败只留痕不抛）；业务失败写失败结果 +
 * 回补 Redis 预扣，并按错误码分流：SECKILL_REPEAT(3001) 补占购买权门闩（自愈），
 * 其它释放门闩（fail-open）；基础设施异常释放两道门闩后抛出。</p>
 *
 * <p>本类最重要的一条：消费者侧绝不能占购买权门闩。入口与消费者若用同一个 key 占门闩，
 * 会令每笔成功请求都被误判成"您已参与过该秒杀活动"，秒杀永远无法成交。由
 * {@link #onMessage_mustNotOccupyPurchaseGate} 守住这条。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("秒杀消息消费者单元测试 SeckillConsumer#onMessage")
class SeckillConsumerTest {

    @Mock
    private SeckillOrderTxService seckillOrderTxService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private SeckillConsumer seckillConsumer;

    // 常量

    private static final Long USER_ID = 1001L;
    private static final Long PRODUCT_ID = 2001L;
    private static final Long ADDRESS_ID = 3001L;
    private static final Long ORDER_ID = 4001L;
    private static final String ORDER_NO = "ORD2026101400001";
    private static final String REQUEST_ID = "req-20261014-0001";

    /** 与 RedisKeyConstant 对齐 */
    private static final String PROCESS_KEY = "seckill:process:" + REQUEST_ID;
    private static final String PURCHASE_GATE_KEY = "seckill:order:success:" + USER_ID + ":" + PRODUCT_ID;

    // 一、消息解析

    @Test
    @DisplayName("[容错] 消息体不是合法 JSON → 抛 RuntimeException（交 MQ 重试），且【不落库】")
    void onMessage_badJson_shouldThrowForRetry() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> seckillConsumer.onMessage("{not-a-json"));

        assertTrue(ex.getMessage().contains("解析失败"), "实际：" + ex.getMessage());
        // 路径断言：解析都失败，绝不该走到落库
        verify(seckillOrderTxService, never()).createSeckillOrder(anyLong(), anyLong(), anyLong());
    }

    // 二、消息级幂等门闩

    @Test
    @DisplayName("[幂等] 门闩已被占（MQ 重投递）→ 直接跳过，【不做任何业务动作】")
    void onMessage_duplicateDelivery_shouldSkipSilently() {
        givenProcessGateAcquired(false);

        assertDoesNotThrow(() -> seckillConsumer.onMessage(jsonOf()));

        verify(seckillOrderTxService, never()).createSeckillOrder(anyLong(), anyLong(), anyLong());
        verify(seckillOrderTxService, never()).writeFailResult(anyString(), anyString());
        // 跳过时不该释放门闩（它是别人占的）
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("[业务正确] 门闩用 SETNX（setIfAbsent）原子占位，且带过期时间（不是 check-then-set）")
    void onMessage_processGate_shouldUseAtomicSetnxWithTtl() {
        givenProcessGateAcquired(true);
        givenCreateOrderSucceeds();

        seckillConsumer.onMessage(jsonOf());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(keyCaptor.capture(), eq("1"), anyLong(), eq(TimeUnit.HOURS));
        assertEquals(PROCESS_KEY, keyCaptor.getValue(),
                "幂等门闩 key 必须带上 requestId，才能区分不同请求");
    }

    // 三、成功落库

    @Test
    @DisplayName("[业务正确] 落库成功 → 写成功结果（带订单号）+ 发超时延迟消息")
    void onMessage_success_shouldWriteResultAndSendTimeout() {
        givenProcessGateAcquired(true);
        givenCreateOrderSucceeds();

        seckillConsumer.onMessage(jsonOf());

        verify(seckillOrderTxService).writeSuccessResult(REQUEST_ID, ORDER_ID, ORDER_NO);
        verify(seckillOrderTxService).sendSeckillTimeoutDelayMessage(ORDER_ID);
    }

    @Test
    @DisplayName("[容错] 写成功结果失败 → 只留痕【不抛出】（订单已落库，抛出会重复建单）")
    void onMessage_writeResultFails_shouldNotThrow() {
        givenProcessGateAcquired(true);
        givenCreateOrderSucceeds();
        // writeSuccessResult 返回 void，不能用 when()，必须 doThrow().when()
        doThrow(new RuntimeException("redis down"))
                .when(seckillOrderTxService).writeSuccessResult(anyString(), anyLong(), anyString());

        assertDoesNotThrow(() -> seckillConsumer.onMessage(jsonOf()),
                "结果写失败只影响用户轮询体验，绝不能触发重试 —— 否则会重复建单");

        // 超时消息依旧要发（订单该关还得关）
        verify(seckillOrderTxService).sendSeckillTimeoutDelayMessage(ORDER_ID);
    }

    @Test
    @DisplayName("[容错] 发超时延迟消息失败 → 只留痕【不抛出】")
    void onMessage_sendTimeoutFails_shouldNotThrow() {
        givenProcessGateAcquired(true);
        givenCreateOrderSucceeds();
        doThrow(new RuntimeException("mq down"))
                .when(seckillOrderTxService).sendSeckillTimeoutDelayMessage(anyLong());

        assertDoesNotThrow(() -> seckillConsumer.onMessage(jsonOf()));
    }

    // 四、业务失败（终态，不重试）

    @Test
    @DisplayName("[业务错误] 业务失败 → 写失败结果 + 【回补 Redis 预扣】，且不抛出")
    void onMessage_businessFail_shouldWriteFailResultAndRollbackRedis() {
        givenProcessGateAcquired(true);
        when(seckillOrderTxService.createSeckillOrder(USER_ID, PRODUCT_ID, ADDRESS_ID))
                .thenThrow(new BusinessException("库存不足"));

        assertDoesNotThrow(() -> seckillConsumer.onMessage(jsonOf()),
                "业务失败是终态：抛出去会被 MQ 重试，重试也还是失败，反而浪费资源");

        verify(seckillOrderTxService).writeFailResult(eq(REQUEST_ID), contains("库存不足"));
        // 没成交 ⇒ 必须把 Redis 预扣的名额还回去，否则名额白占（少卖）
        verify(seckillOrderTxService).rollbackRedisStock(PRODUCT_ID, SeckillConstants.SECKILL_ORDER_QUANTITY);
    }

    @Test
    @DisplayName("[自愈] 被唯一索引拦下的重复抢购（3001）→ 【补占】购买权门闩")
    void onMessage_repeatByUniqueIndex_shouldReacquireGate() {
        givenProcessGateAcquired(true);
        when(seckillOrderTxService.createSeckillOrder(USER_ID, PRODUCT_ID, ADDRESS_ID))
                .thenThrow(new BusinessException(ResultCode.SECKILL_REPEAT));

        seckillConsumer.onMessage(jsonOf());

        // 补占：让该用户后续请求在入口就被挡住，不必白跑一趟 MQ
        verify(valueOperations).setIfAbsent(eq(PURCHASE_GATE_KEY), eq("1"), anyLong(), eq(TimeUnit.HOURS));
        // 这条路不该释放门闩（要的就是"占回去"）
        verify(stringRedisTemplate, never()).delete(eq(PURCHASE_GATE_KEY));
    }

    @Test
    @DisplayName("[对照] 其它业务失败（如库存不足）→ 【释放】购买权门闩（fail-open，允许用户重试）")
    void onMessage_businessFailNotRepeat_shouldReleaseGate() {
        givenProcessGateAcquired(true);
        when(seckillOrderTxService.createSeckillOrder(USER_ID, PRODUCT_ID, ADDRESS_ID))
                .thenThrow(new BusinessException(ResultCode.SECKILL_FAIL));

        seckillConsumer.onMessage(jsonOf());

        // 没成交 ⇒ 释放门闩，否则用户被入口占下的门闩永久锁住、再也抢不了
        verify(stringRedisTemplate).delete(PURCHASE_GATE_KEY);
        // 这条路不该去补占
        verify(valueOperations, never()).setIfAbsent(eq(PURCHASE_GATE_KEY), anyString(), anyLong(), any());
    }

    // 五、基础设施异常（要重试）

    @Test
    @DisplayName("[容错] 基础设施异常 → 释放【两道门闩】后抛出（交 MQ 重试，耗尽进死信）")
    void onMessage_infraError_shouldReleaseBothGatesAndThrow() {
        givenProcessGateAcquired(true);
        when(seckillOrderTxService.createSeckillOrder(USER_ID, PRODUCT_ID, ADDRESS_ID))
                .thenThrow(new RuntimeException("db connection lost"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> seckillConsumer.onMessage(jsonOf()));

        assertTrue(ex.getMessage().contains("待重试"), "实际：" + ex.getMessage());
        // 两道门闩都要释放，否则重试进来会被自己的门闩挡住（第一次的门闩还在）
        verify(stringRedisTemplate).delete(PURCHASE_GATE_KEY);
        verify(stringRedisTemplate).delete(PROCESS_KEY);
    }

    @Test
    @DisplayName("[容错] 基础设施异常【不写失败结果】—— 因为还要重试，写失败会让用户看到误报")
    void onMessage_infraError_shouldNotWriteFailResult() {
        givenProcessGateAcquired(true);
        when(seckillOrderTxService.createSeckillOrder(USER_ID, PRODUCT_ID, ADDRESS_ID))
                .thenThrow(new RuntimeException("transient error"));

        assertThrows(RuntimeException.class, () -> seckillConsumer.onMessage(jsonOf()));

        verify(seckillOrderTxService, never()).writeFailResult(anyString(), anyString());
        // 也不该回补库存 —— 这次没成交是因为要重试，不是真的失败
        verify(seckillOrderTxService, never()).rollbackRedisStock(anyLong(), any(Integer.class));
    }

    // 六、防回归：消费者侧绝不占购买权门闩

    @Test
    @DisplayName("[防回归] 消费者侧【绝不占】购买权门闩 —— 只有入口才占（setIfAbsent 只该被调 1 次）")
    void onMessage_mustNotOccupyPurchaseGate() {
        givenProcessGateAcquired(true);
        givenCreateOrderSucceeds();

        seckillConsumer.onMessage(jsonOf());

        // 全流程中 setIfAbsent 只应被调用一次（就是消息级幂等门闩），
        //    绝不能再对购买权门闩做占位 —— 否则入口已占下的同一个 key 会让
        //    每一笔成功请求都被误判成"您已参与过该秒杀活动"，秒杀永远无法成交。
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(1)).setIfAbsent(keyCaptor.capture(), anyString(), anyLong(), any());
        assertEquals(PROCESS_KEY, keyCaptor.getValue(),
                "唯一一次 SETNX 必须是「消息级幂等门闩」，不能是「购买权门闩」");
        assertTrue(!PURCHASE_GATE_KEY.equals(keyCaptor.getValue()),
                "购买权门闩只能由入口 doSeckill 占位，消费者侧占位会导致秒杀永远不成交（历史事故）");
    }

    // 助手

    /**
     * 构造合法的消息 JSON。
     *
     * <p>刻意不依赖 DTO 的 toString（它由 Lombok 生成，格式不属于契约），
     * 而是<b>手写 JSON 字面量</b> —— 这样测试验证的是"消费者能解析约定的报文格式"，
     * 而不是"能解析自己造出来的格式"。</p>
     */
    private String jsonOf() {
        return "{\"userId\":" + USER_ID
                + ",\"productId\":" + PRODUCT_ID
                + ",\"addressId\":" + ADDRESS_ID
                + ",\"requestId\":\"" + REQUEST_ID + "\"}";
    }

    /** stub：消息级幂等门闩的 SETNX 结果 */
    private void givenProcessGateAcquired(boolean acquired) {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(PROCESS_KEY), eq("1"), anyLong(), eq(TimeUnit.HOURS)))
                .thenReturn(acquired);
    }

    /** stub：落库成功 */
    private void givenCreateOrderSucceeds() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        when(seckillOrderTxService.createSeckillOrder(USER_ID, PRODUCT_ID, ADDRESS_ID))
                .thenReturn(order);
    }
}
