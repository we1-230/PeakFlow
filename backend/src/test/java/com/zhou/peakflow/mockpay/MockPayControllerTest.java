package com.zhou.peakflow.mockpay;

import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.utils.PaySignUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MockPayController <b>单元测试</b>：收款 / 退款 的幂等与资损防线。
 *
 * <p>台账原先放在 {@code MockPayController} 的 static 内存 Map 里，多实例时会出现三类问题：
 * 实例 A 收的款、退款请求打到实例 B ⇒ 查不到原交易而误拒退款；同一 {@code outRefundNo} 分发到
 * 两个实例 ⇒ 重复退款；同一 {@code outTradeNo} 换退款单号再退 ⇒ 同一笔钱退两次。修复后判定全部基于
 * {@link MockPayLedger}（Redis 共享 + SETNX 原子），故本类桩掉台账、断言控制器如何调用台账：
 * 只要控制器坚持"先原子占位、占位失败才复用/拒绝"，多实例语义就成立。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockPay 收退款幂等单元测试 MockPayController")
class MockPayControllerTest {

    private static final String SECRET = "peakflow-mock-pay-secret-key-2026";
    private static final String NOTIFY_URL = "http://127.0.0.1:8080/pay/notify";
    private static final String REFUND_NOTIFY_URL = "http://127.0.0.1:8080/pay/refundNotify";

    private static final String OUT_TRADE_NO = "ORD2026101600001";
    private static final String AMOUNT = "199.00";
    private static final String OUT_REFUND_NO = "REFORD2026101600001-1";
    private static final String EXIST_TRADE_NO = "MOCK20261016000000EXIST";
    private static final String EXIST_REFUND_TRADE_NO = "MOCKREF2026101600EXIST";

    @Mock
    private MockPayProperties props;
    @Mock
    private MockPayLedger ledger;
    @Mock
    private RestTemplate restTemplate;

    private MockPayController controller;

    @BeforeEach
    void setUp() {
        lenient().when(props.getSecretKey()).thenReturn(SECRET);
        lenient().when(props.getNotifyUrl()).thenReturn(NOTIFY_URL);
        lenient().when(props.getRefundNotifyUrl()).thenReturn(REFUND_NOTIFY_URL);
        controller = new MockPayController(props, ledger);
        // controller 内部自己 new 了 RestTemplate，测试里替换成桩
        ReflectionTestUtils.setField(controller, "restTemplate", restTemplate);
    }

    // 测试用签名/报文构造

    private String paySign(String outTradeNo, String amount, String timestamp) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("out_trade_no", outTradeNo);
        p.put("amount", amount);
        p.put("timestamp", timestamp);
        return PaySignUtil.sign(p, SECRET);
    }

    private Map<String, Object> payBody(String sign) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("out_trade_no", OUT_TRADE_NO);
        b.put("amount", AMOUNT);
        b.put("timestamp", "1792079000000");
        b.put("sign", sign);
        return b;
    }

    private Map<String, Object> refundBody(String outRefundNo, String sign) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("out_refund_no", outRefundNo);
        b.put("out_trade_no", OUT_TRADE_NO);
        b.put("refund_amount", AMOUNT);
        b.put("notify_url", REFUND_NOTIFY_URL);
        b.put("timestamp", "1792079000000");
        b.put("sign", sign);
        return b;
    }

    private String refundSign(String outRefundNo) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("out_refund_no", outRefundNo);
        p.put("out_trade_no", OUT_TRADE_NO);
        p.put("refund_amount", AMOUNT);
        p.put("notify_url", REFUND_NOTIFY_URL);
        p.put("timestamp", "1792079000000");
        return PaySignUtil.sign(p, SECRET);
    }

    /** 抓取回调给商户的报文体 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePayload(String url) {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(eq(url), captor.capture(), eq(String.class));
        return (Map<String, Object>) captor.getValue().getBody();
    }

    // 一、收款 /pay

    @Test
    @DisplayName("[收款] 首次受理：原子占位成功 → 生成交易号并回调商户，返回「支付成功」")
    void pay_firstTime_acceptedAndNotified() {
        when(restTemplate.postForObject(eq(NOTIFY_URL), any(), eq(String.class))).thenReturn("success");
        when(ledger.markPaid(eq(OUT_TRADE_NO), anyString())).thenReturn(true);

        Map<String, Object> resp = controller.pay(payBody(paySign(OUT_TRADE_NO, AMOUNT, "1792079000000")));

        assertEquals(Boolean.TRUE, resp.get("success"));
        assertEquals("支付成功", resp.get("message"));
        Map<String, Object> payload = capturePayload(NOTIFY_URL);
        assertEquals(OUT_TRADE_NO, payload.get("out_trade_no"));
        assertTrue(String.valueOf(payload.get("trade_no")).startsWith("MOCK"), "应携带第三方交易号回调");
        verify(ledger, never()).getPaidTradeNo(anyString());
    }

    @Test
    @DisplayName("[收款·幂等] 重复付款请求：占位失败 → 复用原交易号重发通知，不产生第二笔交易")
    void pay_duplicate_reusesExistingTradeNo() {
        when(ledger.markPaid(eq(OUT_TRADE_NO), anyString())).thenReturn(false);
        when(ledger.getPaidTradeNo(OUT_TRADE_NO)).thenReturn(EXIST_TRADE_NO);
        when(restTemplate.postForObject(eq(NOTIFY_URL), any(), eq(String.class))).thenReturn("success");

        Map<String, Object> resp = controller.pay(payBody(paySign(OUT_TRADE_NO, AMOUNT, "1792079000000")));

        assertEquals(Boolean.TRUE, resp.get("success"));
        assertTrue(String.valueOf(resp.get("message")).contains("复用原交易号"),
                "重复请求必须走幂等复用分支，而不是再生成一笔新交易");
        assertEquals(EXIST_TRADE_NO, capturePayload(NOTIFY_URL).get("trade_no"),
                "重发的通知必须用原交易号（多实例下由共享台账仲裁）");
    }

    @Test
    @DisplayName("[收款·安全] 验签失败：直接拒绝，且不触碰台账")
    void pay_badSign_rejected() {
        Map<String, Object> resp = controller.pay(payBody("this-is-a-forged-sign"));

        assertEquals(Boolean.FALSE, resp.get("success"));
        assertEquals("商户签名校验失败", resp.get("message"));
        verify(ledger, never()).markPaid(anyString(), anyString());
    }

    @Test
    @DisplayName("[收款] 回调失败：钱已收到仍返回成功（台账已落，商户可稍后查单/重推）")
    void pay_notifyFailed_stillAccepted() {
        when(ledger.markPaid(eq(OUT_TRADE_NO), anyString())).thenReturn(true);
        when(restTemplate.postForObject(eq(NOTIFY_URL), any(), eq(String.class)))
                .thenThrow(new RestClientException("connection refused"));

        Map<String, Object> resp = controller.pay(payBody(paySign(OUT_TRADE_NO, AMOUNT, "1792079000000")));

        assertEquals(Boolean.TRUE, resp.get("success"), "回调不通不能否定'钱已收到'");
        assertTrue(String.valueOf(resp.get("message")).contains("稍后重推"));
    }

    // 二、退款 /refund

    @Test
    @DisplayName("[退款] 原交易不存在（如请求打到没处理过该笔款的实例）→ 拒绝，且不占用退款单号")
    void refund_unknownTrade_rejected() {
        when(ledger.getRefundTradeNo(OUT_REFUND_NO)).thenReturn(null);
        when(ledger.isPaid(OUT_TRADE_NO)).thenReturn(false);

        Map<String, Object> resp = controller.refund(refundBody(OUT_REFUND_NO, refundSign(OUT_REFUND_NO)));

        assertEquals(Boolean.FALSE, resp.get("success"));
        assertEquals("原交易不存在或未收款", resp.get("message"));
        verify(ledger, never()).claimRefund(anyString(), anyString());
    }

    @Test
    @DisplayName("[退款·幂等] 同一退款单号重复请求 → 重发上次结果，不重新受理")
    void refund_sameRefundNo_resent() {
        when(ledger.getRefundTradeNo(OUT_REFUND_NO)).thenReturn(EXIST_REFUND_TRADE_NO);
        when(restTemplate.postForObject(eq(REFUND_NOTIFY_URL), any(), eq(String.class))).thenReturn("success");

        Map<String, Object> resp = controller.refund(refundBody(OUT_REFUND_NO, refundSign(OUT_REFUND_NO)));

        assertEquals(Boolean.TRUE, resp.get("success"));
        assertEquals("该退款单已受理，处理结果已重发", resp.get("message"));
        assertEquals(EXIST_REFUND_TRADE_NO, capturePayload(REFUND_NOTIFY_URL).get("refund_trade_no"));
        verify(ledger, never()).claimRefund(anyString(), anyString());
    }

    @Test
    @DisplayName("[退款·并发] 同一退款单号被别的实例先占位 → 不重复打款，改为重发胜者结果")
    void refund_concurrentSameRefundNo_beatenByOtherInstance() {
        // 第 1 次读：还没有；抢位失败；第 2 次读：拿到胜者的流水号
        when(ledger.getRefundTradeNo(OUT_REFUND_NO)).thenReturn(null, EXIST_REFUND_TRADE_NO);
        when(ledger.isPaid(OUT_TRADE_NO)).thenReturn(true);
        when(ledger.claimRefund(eq(OUT_REFUND_NO), anyString())).thenReturn(false);
        when(restTemplate.postForObject(eq(REFUND_NOTIFY_URL), any(), eq(String.class))).thenReturn("success");

        Map<String, Object> resp = controller.refund(refundBody(OUT_REFUND_NO, refundSign(OUT_REFUND_NO)));

        assertEquals(Boolean.TRUE, resp.get("success"));
        assertEquals("该退款单已受理，处理结果已重发", resp.get("message"));
        assertEquals(EXIST_REFUND_TRADE_NO, capturePayload(REFUND_NOTIFY_URL).get("refund_trade_no"));
        verify(ledger, never()).claimTradeRefunded(anyString());
    }

    @Test
    @DisplayName("[退款·资损红线] 同一交易换退款单号再退 → 原子判定为已退款，拒绝并释放本次占位")
    void refund_sameTradeSecondTime_rejected() {
        when(ledger.getRefundTradeNo(OUT_REFUND_NO)).thenReturn(null);
        when(ledger.isPaid(OUT_TRADE_NO)).thenReturn(true);
        when(ledger.claimRefund(eq(OUT_REFUND_NO), anyString())).thenReturn(true);
        when(ledger.claimTradeRefunded(OUT_TRADE_NO)).thenReturn(false);

        Map<String, Object> resp = controller.refund(refundBody(OUT_REFUND_NO, refundSign(OUT_REFUND_NO)));

        assertEquals(Boolean.FALSE, resp.get("success"));
        assertEquals("该交易已退款，不可重复退款", resp.get("message"));
        verify(ledger).releaseRefund(OUT_REFUND_NO);
        verify(restTemplate, never()).postForObject(anyString(), any(), eq(String.class));
    }

    @Test
    @DisplayName("[退款] 正常受理：占位成功 + 交易首次退款 → 回调商户退款结果")
    void refund_happyPath() {
        when(ledger.getRefundTradeNo(OUT_REFUND_NO)).thenReturn(null);
        when(ledger.isPaid(OUT_TRADE_NO)).thenReturn(true);
        when(ledger.claimRefund(eq(OUT_REFUND_NO), anyString())).thenReturn(true);
        when(ledger.claimTradeRefunded(OUT_TRADE_NO)).thenReturn(true);
        when(restTemplate.postForObject(eq(REFUND_NOTIFY_URL), any(), eq(String.class))).thenReturn("success");

        Map<String, Object> resp = controller.refund(refundBody(OUT_REFUND_NO, refundSign(OUT_REFUND_NO)));

        assertEquals(Boolean.TRUE, resp.get("success"));
        assertEquals("退款受理成功，结果已通知商户", resp.get("message"));
        Map<String, Object> payload = capturePayload(REFUND_NOTIFY_URL);
        assertEquals(OUT_REFUND_NO, payload.get("out_refund_no"));
        assertNotNull(payload.get("refund_trade_no"));
        verify(ledger, never()).releaseRefund(anyString());
    }
}
