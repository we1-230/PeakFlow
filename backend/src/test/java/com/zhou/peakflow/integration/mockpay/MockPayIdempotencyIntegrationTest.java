package com.zhou.peakflow.integration.mockpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.integration.support.IntegrationTestBase;
import com.zhou.peakflow.utils.PaySignUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockPay 收款/退款幂等 <b>集成测试</b>（真 Redis + 全链路 MVC）。
 *
 * <p>修复前交易台账是 {@code MockPayController} 里的 static 内存 Map，只在单个 JVM 可见，
 * 多实例下会出现"实例 A 收的款、实例 B 查不到 ⇒ 误拒退款""同一笔钱退两次"。本测试直接去 Redis
 * 核对台账 —— 台账落在共享存储等价于"另一个实例也能读到同一笔交易"，是多实例部署下幂等成立的前提。</p>
 *
 * <p>回调地址在测试环境通常不可达，故回调是否成功不作为断言条件，断言一律落在返回的
 * success/message 与 Redis 台账上，保证用例稳定可重复。</p>
 */
@DisplayName("MockPay 幂等（跨实例共享台账）集成测试")
class MockPayIdempotencyIntegrationTest extends IntegrationTestBase {

    private static final String KEY_PAID = "mockpay:paid:";
    private static final String KEY_REFUND = "mockpay:refund:";
    private static final String KEY_REFUNDED = "mockpay:refunded:";

    /**
     * 签名密钥必须与运行环境同源：配置整改后 {@code mock-pay.secret-key} 不再有兜底默认值，
     * 由环境变量 {@code MOCK_PAY_SECRET} 注入。这里直接读被测应用同一份配置，
     * 避免测试里再硬编码一份密钥而与环境不一致（那会让签名校验失败、用例假红）。
     */
    @Autowired
    private MockPayProperties mockPayProperties;

    private String paidOutTradeNo;
    private String refundOutRefundNo;

    @BeforeEach
    void requireSecretConfigured() {
        assertTrue(mockPayProperties.getSecretKey() != null && !mockPayProperties.getSecretKey().isEmpty(),
                "集成测试需要 MockPay 签名密钥：请通过环境变量 MOCK_PAY_SECRET 注入后再运行");
    }

    @AfterEach
    void cleanLedger() {
        if (paidOutTradeNo != null) {
            stringRedisTemplate.delete(KEY_PAID + paidOutTradeNo);
            stringRedisTemplate.delete(KEY_REFUNDED + paidOutTradeNo);
        }
        if (refundOutRefundNo != null) {
            stringRedisTemplate.delete(KEY_REFUND + refundOutRefundNo);
        }
    }

    // 报文构造

    private Map<String, Object> payBody(String outTradeNo) {
        String timestamp = "1792079000000";
        Map<String, Object> signParams = new LinkedHashMap<>();
        signParams.put("out_trade_no", outTradeNo);
        signParams.put("amount", "199.00");
        signParams.put("timestamp", timestamp);

        Map<String, Object> body = new LinkedHashMap<>(signParams);
        body.put("sign", PaySignUtil.sign(signParams, mockPayProperties.getSecretKey()));
        return body;
    }

    private Map<String, Object> refundBody(String outRefundNo, String outTradeNo) {
        String timestamp = "1792079000000";
        Map<String, Object> signParams = new LinkedHashMap<>();
        signParams.put("out_refund_no", outRefundNo);
        signParams.put("out_trade_no", outTradeNo);
        signParams.put("refund_amount", "199.00");
        signParams.put("notify_url", "");
        signParams.put("timestamp", timestamp);

        Map<String, Object> body = new LinkedHashMap<>(signParams);
        body.put("sign", PaySignUtil.sign(signParams, mockPayProperties.getSecretKey()));
        return body;
    }

    // 收款：幂等 + 台账落共享存储

    @Test
    @DisplayName("[收款] 首次/重复付款请求：台账落在 Redis，重复请求复用同一交易号（多实例判定一致）")
    void pay_twice_sameOutTradeNo_reuseSameTradeNo() throws Exception {
        paidOutTradeNo = "IT-MOCKPAY-PAY-" + System.currentTimeMillis();

        // ① 首次受理
        MvcResult first = postJson("/mock-pay/pay", payBody(paidOutTradeNo));
        JsonNode firstBody = body(first);
        assertTrue(firstBody.get("success").asBoolean(), "首次收款应成功");
        assertFalse(firstBody.get("message").asText().contains("复用"), "首次不应走幂等复用分支");

        // 台账必须在共享存储里 —— 这就是多实例能看到同一笔交易的证据
        String tradeNoInRedis = stringRedisTemplate.opsForValue().get(KEY_PAID + paidOutTradeNo);
        assertNotNull(tradeNoInRedis, "收款台账必须落在 Redis（而不是某个实例的 JVM 内存）");
        assertTrue(tradeNoInRedis.startsWith("MOCK"), "第三方交易号应以 MOCK 开头");

        // ② 重复受理（模拟同一请求被重试 / 被负载均衡打到另一个实例）
        MvcResult second = postJson("/mock-pay/pay", payBody(paidOutTradeNo));
        JsonNode secondBody = body(second);
        assertTrue(secondBody.get("success").asBoolean(), "重复收款也应返回成功（幂等）");
        assertTrue(secondBody.get("message").asText().contains("复用原交易号"),
                "重复请求必须复用原交易号，而不是又生成一笔新的第三方交易");

        assertEquals(tradeNoInRedis, stringRedisTemplate.opsForValue().get(KEY_PAID + paidOutTradeNo),
                "重复请求不得覆盖台账里的交易号（同一 outTradeNo 只能有一笔第三方交易）");
    }

    // 退款：幂等 + 资损红线

    @Test
    @DisplayName("[退款] 同一退款单号重复请求：台账不变、不重复受理（重量级幂等）")
    void refund_twice_sameOutRefundNo_idempotent() throws Exception {
        paidOutTradeNo = "IT-MOCKPAY-REF-TRADE-" + System.currentTimeMillis();
        refundOutRefundNo = "IT-MOCKPAY-REF-" + System.currentTimeMillis();

        // 先收款，保证原交易存在
        postJson("/mock-pay/pay", payBody(paidOutTradeNo));

        // ① 首次退款
        MvcResult first = postJson("/mock-pay/refund", refundBody(refundOutRefundNo, paidOutTradeNo));
        String firstRefundTradeNo = stringRedisTemplate.opsForValue().get(KEY_REFUND + refundOutRefundNo);
        assertNotNull(firstRefundTradeNo, "退款台账必须落在 Redis");
        assertEquals("1", stringRedisTemplate.opsForValue().get(KEY_REFUNDED + paidOutTradeNo),
                "该交易应被标记为『已发起退款』（防同一交易重复退款）");

        // ② 同一退款单号重试
        MvcResult second = postJson("/mock-pay/refund", refundBody(refundOutRefundNo, paidOutTradeNo));
        String secondRefundTradeNo = stringRedisTemplate.opsForValue().get(KEY_REFUND + refundOutRefundNo);
        assertEquals(firstRefundTradeNo, secondRefundTradeNo,
                "同一退款单号重试不得生成新的退款流水号（否则就是重复打款）");

        // 返回文案：回调可达时是"已受理…重发"，不可达时是"通知商户失败"，两者都属正确幂等路径
        String msg = body(second).get("message").asText();
        assertTrue(msg.contains("已受理") || msg.contains("通知商户失败"),
                "重复退款请求只能是『重发结果/重发失败』两种确定性结果，实际=" + msg);
        assertFalse(msg.contains("退款受理成功"), "重复请求不应表现为『再次受理成功』");
    }

    @Test
    @DisplayName("[退款·资损红线] 同一交易换退款单号再退 → 被原子判定拒绝，且释放本次退款单号占位")
    void refund_sameTradeDifferentRefundNo_rejected() throws Exception {
        paidOutTradeNo = "IT-MOCKPAY-DUP-TRADE-" + System.currentTimeMillis();
        String refundNo1 = "IT-MOCKPAY-DUP-A-" + System.currentTimeMillis();
        String refundNo2 = "IT-MOCKPAY-DUP-B-" + System.currentTimeMillis();
        refundOutRefundNo = refundNo2;

        postJson("/mock-pay/pay", payBody(paidOutTradeNo));
        postJson("/mock-pay/refund", refundBody(refundNo1, paidOutTradeNo));

        // 第一张退款单把交易标记为已退款（回调是否成功不影响该标记）
        assertEquals("1", stringRedisTemplate.opsForValue().get(KEY_REFUNDED + paidOutTradeNo));

        // 第二张退款单：必须被拒
        MvcResult second = postJson("/mock-pay/refund", refundBody(refundNo2, paidOutTradeNo));
        JsonNode secondBody = body(second);
        assertFalse(secondBody.get("success").asBoolean(), "同一交易第二次退款必须失败");
        assertEquals("该交易已退款，不可重复退款", secondBody.get("message").asText());

        // 被拒的这次不应白占退款单号（否则该退款单的合法重试会被误判为"已受理"）
        assertEquals(null, stringRedisTemplate.opsForValue().get(KEY_REFUND + refundNo2),
                "被拒绝的并发退款必须释放退款单号占位");

        // 清理第一张单
        stringRedisTemplate.delete(KEY_REFUND + refundNo1);
    }

    @Test
    @DisplayName("[退款] 原交易不存在（对应『请求打到没处理过该笔款的实例』）→ 拒绝，不占用退款单号")
    void refund_unknownTrade_rejected() throws Exception {
        refundOutRefundNo = "IT-MOCKPAY-UNKNOWN-" + System.currentTimeMillis();
        String unknownTradeNo = "IT-MOCKPAY-NOT-PAID-" + System.currentTimeMillis();

        MvcResult result = postJson("/mock-pay/refund", refundBody(refundOutRefundNo, unknownTradeNo));

        JsonNode b = body(result);
        assertFalse(b.get("success").asBoolean());
        assertEquals("原交易不存在或未收款", b.get("message").asText());
        assertEquals(null, stringRedisTemplate.opsForValue().get(KEY_REFUND + refundOutRefundNo),
                "被拒的退款请求不应留下退款台账");
        refundOutRefundNo = null;
    }
}
