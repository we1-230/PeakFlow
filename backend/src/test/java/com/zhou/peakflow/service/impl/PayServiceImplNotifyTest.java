package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.enums.PayStatus;
import com.zhou.peakflow.dto.pay.PayNotifyDto;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.pojo.PayRecord;
import com.zhou.peakflow.service.OrderService;
import com.zhou.peakflow.utils.PaySignUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付回调 <b>单元测试</b>：{@code PayServiceImpl#handleNotify(dto, rawBody)}。
 *
 * <p>测 Service 而不是 Controller：{@code PayController#notify} 只有两行有效逻辑，真正的资金闸门全在 handleNotify。</p>
 *
 * <p>判定链：验签 → selectByOutTradeNo（null 则 failure）→ status == SUCCESS 则直接 success（幂等第一道）→
 * canPay 只放行 WAIT_PAY → amount 为 null 或金额不等则 failure（防改价）→ markPaid(WHERE status=0) CAS，
 * rows == 0 时重查（已 SUCCESS 则 success；否则 failure 需人工）→ markOrderPaid → success。</p>
 *
 * <p>三个设计要点：① 签名用生产代码 PaySignUtil 算（纯函数，既验证验签逻辑又不会手写错），
 * 注意签名参数用 snake_case 而 DTO 用 camelCase；② 拒绝类用例除返回值外还 verify(never()) 证明没碰 DB；
 * ③ 路径断言 —— 多个失败原因返回同一个 failure，只断言返回值会假阳性，故额外断言"走到了哪一步"。</p>
 *
 * <p>单元测试的独特价值：末尾两个 CAS 竞态用例（markPaid 影响 0 行）在集成测试里几乎造不出来，用 mock 可精确构造。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("支付回调单元测试 PayServiceImpl#handleNotify")
class PayServiceImplNotifyTest {

    // 被测对象与依赖（全部 mock，不连任何服务）

    @Mock
    private PayRecordMapper payRecordMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderService orderService;
    @Mock
    private MockPayProperties mockPayProperties;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private PayTxService payTxService;

    @InjectMocks
    private PayServiceImpl payService;

    // 常量

    private static final String SECRET = "unit-test-secret-key";
    private static final String OUT_TRADE_NO = "PAY2026101400001";
    private static final String TRADE_NO = "MOCK-TRADE-20261014-0001";
    private static final long TIMESTAMP = 1760400000000L;
    private static final BigDecimal AMOUNT = new BigDecimal("99.00");
    private static final Long ORDER_ID = 1001L;
    /** 回调里的 status（第三方侧语义：1 = 支付成功） */
    private static final int NOTIFY_STATUS_SUCCESS = 1;
    /** 模拟"回调报文原文"（Controller 序列化后传入） */
    private static final String RAW_BODY = "{\"outTradeNo\":\"" + OUT_TRADE_NO + "\"}";

    @BeforeEach
    void stubSecretKey() {
        // 验签是第一条判定，所有用例都会用到密钥
        when(mockPayProperties.getSecretKey()).thenReturn(SECRET);
    }

    // 一、验签（第一道闸门）

    /**
     * <p><b>为什么测它：</b>验签是资金入账的第一道闸门，被篡改的回调若被放行就是<b>凭空入账</b>。</p>
     * <p><b>关键断言：</b>返回 failure；且 <b>never</b> 查单、<b>never</b> 入账、<b>never</b> 改订单 —— 一步都不能走。</p>
     */

    @Test
    @DisplayName("[验证失败] 签名被篡改 → 返回 failure，且【绝不查库、绝不入账、绝不动订单】")
    void handleNotify_tamperedSign_shouldRejectBeforeAnyDbAccess() {
        PayNotifyDto dto = buildDto(OUT_TRADE_NO, AMOUNT, false);

        String result = payService.handleNotify(dto, RAW_BODY);

        assertEquals("failure", result, "验签失败必须返回 failure，让第三方稍后重推");
        // 核心断言：验签在第一步，失败就该"什么都不做"
        verify(payRecordMapper, never()).selectByOutTradeNo(anyString());
        verify(payRecordMapper, never()).markPaid(anyString(), anyString(), any(), any());
        verify(orderService, never()).markOrderPaid(anyLong());
    }

    /**
     * <p><b>为什么测它：</b>边界：签名字段为空串时，PaySignUtil 会直接判 false，不能因为「没传」就绕过验签。</p>
     * <p><b>关键断言：</b>返回 failure；<b>never</b> 入账。</p>
     */

    @Test
    @DisplayName("[验证失败] sign 为空串 → 返回 failure（PaySignUtil 对空签名直接判 false）")
    void handleNotify_blankSign_shouldReject() {
        PayNotifyDto dto = buildDto(OUT_TRADE_NO, AMOUNT, true);
        dto.setSign("");

        assertEquals("failure", payService.handleNotify(dto, RAW_BODY));
        verify(payRecordMapper, never()).markPaid(anyString(), anyString(), any(), any());
    }

    // 二、支付单查找（第二道闸门）

    /**
     * <p><b>为什么测它：</b>本地没有这笔支付单时若还入账，等于<b>凭空多出一笔钱</b>。</p>
     * <p><b>关键断言：</b>返回 failure；并 <b>verify</b> 查单被调用过（反证验签已通过，失败发生在第二步）。</p>
     */

    @Test
    @DisplayName("[非法输入] 本地无此支付单 → 返回 failure，不入账")
    void handleNotify_payRecordNotFound_shouldReject() {
        when(payRecordMapper.selectByOutTradeNo(OUT_TRADE_NO)).thenReturn(null);

     String result = payService.handleNotify(buildDto(OUT_TRADE_NO, AMOUNT, true), RAW_BODY);

        assertEquals("failure", result, "本地没有这笔支付单时必须拒绝，否则会凭空入账");
        verify(payRecordMapper, never()).markPaid(anyString(), anyString(), any(), any());
    verify(orderService, never()).markOrderPaid(anyLong());
    // 路径断言：证明这个 failure 发生在目标步骤，而不是被第 1 步验签拦下
    // （若签名算错导致验签失败，本断言会失败 —— 从而暴露『假阳性』）
    assertSignGatePassed();
}

    // 三、幂等（重复回调）

    /**
     * <p><b>为什么测它：</b>第三方重复推送是常态。<b>幂等第一道</b>保证已成功的单子不再被 UPDATE。</p>
     * <p><b>关键断言：</b>返回 success；<b>never</b> 调 markPaid（证明根本没走到 UPDATE，是「读」这一层拦下的）。</p>
     */

    @Test
    @DisplayName("[幂等] 支付单已成功 → 返回 success，且【不再执行 markPaid】（重复回调不重复入账）")
    void handleNotify_alreadySuccess_shouldReturnSuccessWithoutUpdate() {
        givenExistingPayRecord(PayStatus.SUCCESS.getCode(), AMOUNT);

        String result = payService.handleNotify(buildDto(OUT_TRADE_NO, AMOUNT, true), RAW_BODY);

        assertEquals("success", result, "重复回调应直接应答 success，让第三方停止重推");
        // 幂等在"读"这一层就拦下了，根本没走到 UPDATE
        verify(payRecordMapper, never()).markPaid(anyString(), anyString(), any(), any());
        verify(orderService, never()).markOrderPaid(anyLong());
    }

    /**
     * <p><b>为什么测它：</b>边界：幂等判定<b>优先于</b>金额校验 —— 已成功的单子即使报文金额对不上也应放行（否则第三方会一直重推）。</p>
     * <p><b>关键断言：</b>金额传 0.01 仍返回 success；<b>never</b> 入账。</p>
     */

    @Test
    @DisplayName("[幂等] 重复回调时即使金额报文不同，也照样回 success（幂等判定先于金额校验）")
    void handleNotify_alreadySuccess_amountIgnored() {
        givenExistingPayRecord(PayStatus.SUCCESS.getCode(), AMOUNT);

        // 幂等分支在金额校验之前，所以金额不同也不影响结果
        String result = payService.handleNotify(
                buildDto(OUT_TRADE_NO, new BigDecimal("0.01"), true), RAW_BODY);

        assertEquals("success", result, "幂等判定的优先级高于金额校验");
        verify(payRecordMapper, never()).markPaid(anyString(), anyString(), any(), any());
    }

    // 四、状态闸门（只有待支付能入账）

    /**
     * <p><b>为什么测它：</b>订单已取消/超时关单后支付单被关闭，此时收到「支付成功」绝不能自动入账 —— 这笔钱要人工退。</p>
     * <p><b>关键断言：</b>返回 failure；<b>never</b> 入账、<b>never</b> 改订单。</p>
     */

    @Test
    @DisplayName("[非法状态] 支付单已关闭 → 返回 failure（canPay 只放行待支付），不入账")
    void handleNotify_closedPayRecord_shouldReject() {
        givenExistingPayRecord(PayStatus.CLOSED.getCode(), AMOUNT);

        String result = payService.handleNotify(buildDto(OUT_TRADE_NO, AMOUNT, true), RAW_BODY);

        assertEquals("failure", result,
                "订单已取消/超时关单后收到成功回调：这笔钱要人工退，不能让系统自己标成功");
        verify(payRecordMapper, never()).markPaid(anyString(), anyString(), any(), any());
        verify(orderService, never()).markOrderPaid(anyLong());
        // 路径断言：证明这个 failure 发生在目标步骤，而不是被第 1 步验签拦下
        // （若签名算错导致验签失败，本断言会失败 —— 从而暴露『假阳性』）
        assertSignGatePassed();
    }

    /**
     * <p><b>为什么测它：</b>已退款的支付单更不能再入账（canPay 只放行待支付）。</p>
     * <p><b>关键断言：</b>返回 failure；<b>never</b> 入账。</p>
     */

    @Test
    @DisplayName("[非法状态] 支付单已退款 → 返回 failure")
    void handleNotify_refundedPayRecord_shouldReject() {
        givenExistingPayRecord(PayStatus.REFUNDED.getCode(), AMOUNT);

        assertEquals("failure", payService.handleNotify(buildDto(OUT_TRADE_NO, AMOUNT, true), RAW_BODY));
        verify(payRecordMapper, never()).markPaid(anyString(), anyString(), any(), any());
        // 路径断言：证明这个 failure 发生在目标步骤，而不是被第 1 步验签拦下
        // （若签名算错导致验签失败，本断言会失败 —— 从而暴露『假阳性』）
        assertSignGatePassed();
    }

    // 五、金额校验（防改价攻击）

    /**
     * <p><b>为什么测它：</b><b>防改价攻击</b>：不校验金额的话，攻击者用 1 分钱就能把 99 元订单标记为已付款。</p>
     * <p><b>关键断言：</b>返回 failure（签名是合法的，只靠金额校验拦住）；数据一律不变。</p>
     */

    @Test
    @DisplayName("[攻击·改价] 回调金额 0.01 ≠ 应付 99.00 → 返回 failure，不入账")
    void handleNotify_amountTamperedDown_shouldReject() {
        givenExistingPayRecord(PayStatus.WAIT_PAY.getCode(), AMOUNT);

        String result = payService.handleNotify(
                buildDto(OUT_TRADE_NO, new BigDecimal("0.01"), true), RAW_BODY);

        assertEquals("failure", result, "否则 1 分钱就能把 99 元的订单标记为已付款");
        verify(payRecordMapper, never()).markPaid(anyString(), anyString(), any(), any());
        verify(orderService, never()).markOrderPaid(anyLong());
        // 路径断言：证明这个 failure 发生在目标步骤，而不是被第 1 步验签拦下
        // （若签名算错导致验签失败，本断言会失败 —— 从而暴露『假阳性』）
        assertSignGatePassed();
    }

    /**
     * <p><b>为什么测它：</b>金额被改大同样必须拒绝，不能只看「是否够付」。</p>
     * <p><b>关键断言：</b>返回 failure；<b>never</b> 入账。</p>
     */

    @Test
    @DisplayName("[攻击·改价] 回调金额 999999 被改大 → 返回 failure，不入账")
    void handleNotify_amountTamperedUp_shouldReject() {
        givenExistingPayRecord(PayStatus.WAIT_PAY.getCode(), AMOUNT);

        assertEquals("failure", payService.handleNotify(
                buildDto(OUT_TRADE_NO, new BigDecimal("999999.00"), true), RAW_BODY));
        verify(payRecordMapper, never()).markPaid(anyString(), anyString(), any(), any());
        // 路径断言：证明这个 failure 发生在目标步骤，而不是被第 1 步验签拦下
        // （若签名算错导致验签失败，本断言会失败 —— 从而暴露『假阳性』）
        assertSignGatePassed();
    }

    /**
     * <p><b>为什么测它：</b>空值不参与签名 ⇒ 报文仍能算出合法签名，所以<b>只能靠 `amount == null` 这个判断拦住</b>，本用例专门守它。</p>
     * <p><b>关键断言：</b>返回 failure；数据不变。</p>
     */

    @Test
    @DisplayName("[攻击·边界] amount 为 null → 返回 failure"
            + "（空值不参与签名，所以签名合法，只能靠这个 null 判断拦住）")
    void handleNotify_amountNull_shouldReject() {
        givenExistingPayRecord(PayStatus.WAIT_PAY.getCode(), AMOUNT);

        String result = payService.handleNotify(buildDto(OUT_TRADE_NO, null, true), RAW_BODY);

        assertEquals("failure", result);
        verify(payRecordMapper, never()).markPaid(anyString(), anyString(), any(), any());
        // 路径断言：证明这个 failure 发生在目标步骤，而不是被第 1 步验签拦下
        // （若签名算错导致验签失败，本断言会失败 —— 从而暴露『假阳性』）
        assertSignGatePassed();
    }

    /**
     * <p><b>为什么测它：</b>边界正向：`99.0` 与 `99.00` 的 equals 为 false 但 compareTo 为 0 —— 验证生产代码用的是 <b>compareTo</b>，避免 BigDecimal 精度陷阱。</p>
     * <p><b>关键断言：</b>返回 success（金额比较不该被 scale 绊倒）。</p>
     */

    @Test
    @DisplayName("[边界] 金额相等但精度表达不同（99.0 vs 99.00）→ 仍应放行"
            + "（用 compareTo 而非 equals，避免 BigDecimal 精度陷阱）")
    void handleNotify_amountSameValueDifferentScale_shouldPass() {
        givenExistingPayRecord(PayStatus.WAIT_PAY.getCode(), new BigDecimal("99.00"));
        when(payRecordMapper.markPaid(anyString(), anyString(), any(), any())).thenReturn(1);

        // 99.0 与 99.00 的 equals 为 false 但 compareTo 为 0 —— 这里验证生产代码用的是 compareTo
        String result = payService.handleNotify(
                buildDto(OUT_TRADE_NO, new BigDecimal("99.0"), true), RAW_BODY);

        assertEquals("success", result, "金额比较应用 compareTo，不该被 BigDecimal 精度绊倒");
    }

    // 六、正常入账

    /**
     * <p><b>为什么测它：</b>正常入账路径，并验证<b>回调报文原文被落库留档</b>（日后对账/排查的依据）。</p>
     * <p><b>关键断言：</b>返回 success；markPaid 入参含 outTradeNo/tradeNo/时间/原文；原文与传入一致。</p>
     */

    @Test
    @DisplayName("[业务正确] 待支付单 + 合法回调 → 返回 success，CAS 入账并写入回调报文留档")
    void handleNotify_valid_shouldMarkPaid() {
        givenExistingPayRecord(PayStatus.WAIT_PAY.getCode(), AMOUNT);
        when(payRecordMapper.markPaid(eq(OUT_TRADE_NO), eq(TRADE_NO), any(LocalDateTime.class), anyString()))
                .thenReturn(1);

        String result = payService.handleNotify(buildDto(OUT_TRADE_NO, AMOUNT, true), RAW_BODY);

        assertEquals("success", result);

        // 断言入参：商户单号 + 第三方交易号 + 回调时间 + 报文留档
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(payRecordMapper).markPaid(eq(OUT_TRADE_NO), eq(TRADE_NO),
                any(LocalDateTime.class), contentCaptor.capture());
        assertEquals(RAW_BODY, contentCaptor.getValue(), "回调报文原文应原样落库，便于对账");
    }

    /**
     * <p><b>为什么测它：</b>入账之后必须把订单改成已付款，否则钱到了但订单还是待付款。</p>
     * <p><b>关键断言：</b>markOrderPaid 收到的 orderId <b>来自支付单关联的订单</b>（不是随便传的值）。</p>
     */

    @Test
    @DisplayName("[业务正确] 入账成功后 → 把订单改为已付款（markOrderPaid 被调用且带上正确的 orderId）")
    void handleNotify_valid_shouldMarkOrderPaid() {
        givenExistingPayRecord(PayStatus.WAIT_PAY.getCode(), AMOUNT);
        when(payRecordMapper.markPaid(anyString(), anyString(), any(), any())).thenReturn(1);

        payService.handleNotify(buildDto(OUT_TRADE_NO, AMOUNT, true), RAW_BODY);

        // 用 Captor 断言订单号取自支付单，而不是随便传了个值
        ArgumentCaptor<Long> orderIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(orderService).markOrderPaid(orderIdCaptor.capture());
        assertEquals(ORDER_ID, orderIdCaptor.getValue(), "应把支付单关联的订单改为已付款");
    }

    /**
     * <p><b>为什么测它：</b>回调报文可能超长，超出列宽会插入失败 —— truncate 是保护。</p>
     * <p><b>关键断言：</b>落库内容长度恰好为 1000。</p>
     */

    @Test
    @DisplayName("[业务正确] 报文超长时 → 落库内容被截断到 1000 字符（truncate 保护）")
    void handleNotify_valid_shouldTruncateOverlongRawBody() {
        givenExistingPayRecord(PayStatus.WAIT_PAY.getCode(), AMOUNT);
        when(payRecordMapper.markPaid(anyString(), anyString(), any(), any())).thenReturn(1);
        String longBody = "x".repeat(1500);

        payService.handleNotify(buildDto(OUT_TRADE_NO, AMOUNT, true), longBody);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(payRecordMapper).markPaid(anyString(), anyString(), any(), captor.capture());
        assertEquals(1000, captor.getValue().length(), "超过 1000 字符应被截断，避免入库失败");
    }

    // 七、CAS 竞态（单元测试的独特价值：集成测试很难构造这两个场景）

    /**
     * <p><b>为什么测它：</b><b>并发场景</b>：CAS 影响 0 行说明被别人抢先入账了。此时对第三方来说这笔已成功，应答 success 才不会让它一直重推。</p>
     * <p><b>关键断言：</b>返回 success；<b>never</b> 重复改订单。</p>
     */

    @Test
    @DisplayName("[并发] CAS 影响 0 行 + 重查已是成功 → 返回 success（被抢先，不能算失败）"
            + " 且不再重复改订单")
    void handleNotify_casZeroRowsAlreadySuccess_shouldReturnSuccess() {
        // 第一次查：待支付；markPaid 因并发影响 0 行；第二次查：已被别人改成成功
        when(payRecordMapper.selectByOutTradeNo(OUT_TRADE_NO))
                .thenReturn(payRecordOf(PayStatus.WAIT_PAY.getCode(), AMOUNT))
                .thenReturn(payRecordOf(PayStatus.SUCCESS.getCode(), AMOUNT));
        when(payRecordMapper.markPaid(anyString(), anyString(), any(), any())).thenReturn(0);

        String result = payService.handleNotify(buildDto(OUT_TRADE_NO, AMOUNT, true), RAW_BODY);

        assertEquals("success", result,
                "并发回调被对方抢先入账 —— 对第三方来说这笔已成功，应答 success 才不会让它一直重推");
        verify(orderService, never()).markOrderPaid(anyLong());
    }

    /**
     * <p><b>为什么测它：</b><b>异常场景</b>：入账失败但状态又不是成功（例如单子已被关闭后用户却付了钱）—— 必须返回 failure 继续重推并留下人工处理窗口。</p>
     * <p><b>关键断言：</b>返回 failure；<b>never</b> 改订单。</p>
     */

    @Test
    @DisplayName("[异常场景] CAS 影响 0 行 + 重查是已关闭 → 返回 failure（用户真付了钱但单已关，需人工介入）")
    void handleNotify_casZeroRowsAndClosed_shouldReturnFailure() {
        when(payRecordMapper.selectByOutTradeNo(OUT_TRADE_NO))
                .thenReturn(payRecordOf(PayStatus.WAIT_PAY.getCode(), AMOUNT))
                .thenReturn(payRecordOf(PayStatus.CLOSED.getCode(), AMOUNT));
        when(payRecordMapper.markPaid(anyString(), anyString(), any(), any())).thenReturn(0);

        String result = payService.handleNotify(buildDto(OUT_TRADE_NO, AMOUNT, true), RAW_BODY);

        assertEquals("failure", result, "返回 failure 让第三方继续重推，同时留出人工处理窗口");
        verify(orderService, never()).markOrderPaid(anyLong());
        // 路径断言：证明这个 failure 发生在目标步骤，而不是被第 1 步验签拦下
        // （若签名算错导致验签失败，本断言会失败 —— 从而暴露『假阳性』）
        assertSignGatePassed();
    }

    // 路径断言助手（本类最重要的设计，详见类注释「为什么必须有路径断言」）

    /**
     * 证明「验签已通过」。
     *
     * <p>原理：仅当第 1 步验签返回 true，{@code handleNotify} 才会执行到查单那一步。
     * 所以「查单被调用过」就是「合法签名确实生效」的直接证据。</p>
     *
     * <p>不加这一步断言时，{@code assertEquals("failure", ...)} 无法区分
     * 「验签被拒」与「支付单不存在」—— 会形成测试假阳性。</p>
     */
    private void assertSignGatePassed() {
        verify(payRecordMapper, atLeastOnce()).selectByOutTradeNo(OUT_TRADE_NO);
    }

    // 造数助手

    /**
     * 构造回调报文。
     *
     * @param validSign true = 用生产代码算出合法签名；false = 故意给一个被篡改的签名
     */
    private PayNotifyDto buildDto(String outTradeNo, BigDecimal amount, boolean validSign) {
        PayNotifyDto dto = new PayNotifyDto();
        dto.setOutTradeNo(outTradeNo);
        dto.setTradeNo(TRADE_NO);
        dto.setAmount(amount);
        dto.setStatus(NOTIFY_STATUS_SUCCESS);
        dto.setTimestamp(TIMESTAMP);
        dto.setSign(validSign ? signOf(outTradeNo, amount) : "TAMPERED-SIGN");
        return dto;
    }

    /**
     * 用生产代码的签名工具计算签名。
     *
     * <p>待签串的 key 必须是 <b>snake_case</b>，与 {@code PayServiceImpl#verifySign} 完全一致；
     * 而请求体 DTO 的字段是 camelCase。这个不一致是验签的隐式契约，写错就永远验不过。</p>
     *
     * <p>另外注意 {@link PaySignUtil#buildSignContent} 的规则：<b>空值不参与签名</b>，
     * 所以 amount 为 null 时报文仍能算出合法签名 —— 这正是"amount 缺失"用例存在的原因。</p>
     */
    private String signOf(String outTradeNo, BigDecimal amount) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("out_trade_no", outTradeNo);
        params.put("trade_no", TRADE_NO);
        params.put("amount", amount == null ? null : amount.toPlainString());
        params.put("status", NOTIFY_STATUS_SUCCESS);
        params.put("timestamp", TIMESTAMP);
        return PaySignUtil.sign(params, SECRET);
    }

    /** 构造一个支付单记录（只填本测试用得到的字段） */
    private PayRecord payRecordOf(int status, BigDecimal amount) {
        PayRecord record = new PayRecord();
        record.setId(1L);
        record.setOutTradeNo(OUT_TRADE_NO);
        record.setOrderId(ORDER_ID);
        record.setAmount(amount);
        record.setStatus(status);
        return record;
    }

    /** stub：数据库里已存在一条指定状态的支付单 */
    private void givenExistingPayRecord(int status, BigDecimal amount) {
        PayRecord record = payRecordOf(status, amount);
        assertNotNull(record, "造数据不应为 null");
        when(payRecordMapper.selectByOutTradeNo(OUT_TRADE_NO)).thenReturn(record);
    }
}
