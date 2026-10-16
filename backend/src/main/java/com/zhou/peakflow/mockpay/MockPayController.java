package com.zhou.peakflow.mockpay;

import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.utils.PaySignUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟第三方支付平台：提供收银台与付款/退款回调，验签与幂等对齐真实渠道
 */
@Slf4j
@RestController
@RequestMapping("/mock-pay")
public class MockPayController {

    private static final String LOG_PREFIX = "【MockPay 模拟第三方】";

    private static final DateTimeFormatter TRADE_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 交易 / 退款台账。
     *
     * 它是共享存储（Redis）而不是本 JVM 内存 —— 单机多实例部署时，
     * “这笔交易是否已受理 / 是否已退款”的判定必须在所有实例之间一致，
     * 否则会出现「实例 A 收的款、实例 B 查不到 ⇒ 拒退」与「同一笔钱退两次」的资损问题。
     * 详见 {@link MockPayLedger}。
     */
    private final MockPayLedger ledger;

    private final MockPayProperties props;

    /** 用于"第三方 → 商户"发起异步回调（真实场景是第三方服务器主动请求商户的 notify_url） */
    private final RestTemplate restTemplate;

    public MockPayController(MockPayProperties props, MockPayLedger ledger) {
        this.props = props;
        this.ledger = ledger;
        // 设置超时，避免回调地址不可达时把"第三方"线程挂死
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    @GetMapping(value = "/cashier", produces = MediaType.TEXT_HTML_VALUE)
    public String cashier() throws IOException {
        // 直接返回静态页面内容：@RestController 下 return "forward:xxx" 会被当作响应体原样输出，
        // 不会触发视图转发，导致收银台只回一串字面量文本；且重定向会丢失 URL 上的 out_trade_no 参数，
        // 页面依赖 location.search 取值，故这里原样内联返回页面。
        ClassPathResource page = new ClassPathResource("static/mock-pay-cashier.html");
        String html = StreamUtils.copyToString(page.getInputStream(), StandardCharsets.UTF_8);
        // 「返回商城」按钮的目标地址由配置注入（mock-pay.return-url）：
        // 页面里不再写死后端自带的静态演示页，默认指向 Vue 商城前端（订单页）。
        return html.replace("__INJECT_MALL_RETURN_URL__", props.getReturnUrl());
    }


    @PostMapping("/pay")
    public Map<String, Object> pay(@RequestBody Map<String, Object> body) {
        String outTradeNo = stringValue(body.get("out_trade_no"));
        String amount = stringValue(body.get("amount"));
        String timestamp = stringValue(body.get("timestamp"));
        String sign = stringValue(body.get("sign"));

        log.info("{}收到付款请求，outTradeNo={}, amount={}", LOG_PREFIX, outTradeNo, amount);

        // 1. 校验商户签名（方向：商户 → 第三方）
        Map<String, Object> signParams = new LinkedHashMap<>();
        signParams.put("out_trade_no", outTradeNo);
        signParams.put("amount", amount);
        signParams.put("timestamp", timestamp);
        if (!PaySignUtil.verify(signParams, props.getSecretKey(), sign)) {
            log.error("{}商户请求验签失败，拒绝交易，outTradeNo={}", LOG_PREFIX, outTradeNo);
            return result(false, "商户签名校验失败");
        }

        // 2. 受理收款：账记在「共享台账」里，并按 out_trade_no 幂等
        //   · 首次受理：生成第三方交易号并原子占位；台账先于"通知商户"落库 —— 钱是真收到了，
        //     无论商户回调成不成功，第三方都要认这笔账（否则后续退款会因为"查不到原交易"被拒）。
        //   · 重复受理（同一 out_trade_no 打到本实例或别的实例）：复用原交易号并重发通知，
        //     不会产生第二笔第三方交易（台账在 Redis 里，多实例判定一致）。
        String tradeNo = generateTradeNo();
        if (!ledger.markPaid(outTradeNo, tradeNo)) {
            String existTradeNo = ledger.getPaidTradeNo(outTradeNo);
            log.warn("{}重复付款请求，复用原交易号并重发通知，outTradeNo={}, tradeNo={}",
                    LOG_PREFIX, outTradeNo, existTradeNo);
            sendPayNotify(outTradeNo, existTradeNo, amount);
            return result(true, "支付成功（重复请求，已复用原交易号）");
        }

        // 3/4. 组装异步通知参数并签名（第三方 → 商户）→ 发起服务器到服务器回调
        boolean notified = sendPayNotify(outTradeNo, tradeNo, amount);
        if (!notified) {

            log.error("{}异步通知商户失败，outTradeNo={}（真实渠道会按策略重推，商户可主动查单兜底）",
                    LOG_PREFIX, outTradeNo);
            return result(true, "支付成功（商户通知将稍后重推）");
        }

        log.info("{}交易完成，outTradeNo={}, tradeNo={}", LOG_PREFIX, outTradeNo, tradeNo);
        return result(true, "支付成功");
    }


    // 组装支付结果通知 → 签名 → 回调商户
    private boolean sendPayNotify(String outTradeNo, String tradeNo, String amount) {
        long notifyTimestamp = System.currentTimeMillis();
        Map<String, Object> notifyParams = new LinkedHashMap<>();
        notifyParams.put("out_trade_no", outTradeNo);
        notifyParams.put("trade_no", tradeNo);
        notifyParams.put("amount", amount);
        notifyParams.put("status", 1);                 // 1 = 支付成功
        notifyParams.put("timestamp", notifyTimestamp);
        String notifySign = PaySignUtil.sign(notifyParams, props.getSecretKey());

        Map<String, Object> notifyBody = new LinkedHashMap<>(notifyParams);
        notifyBody.put("sign", notifySign);

        return sendNotify(notifyBody);
    }


    @PostMapping("/refund")
    public Map<String, Object> refund(@RequestBody Map<String, Object> body) {
        String outRefundNo = stringValue(body.get("out_refund_no"));
        String outTradeNo = stringValue(body.get("out_trade_no"));
        String refundAmount = stringValue(body.get("refund_amount"));
        String notifyUrl = stringValue(body.get("notify_url"));
        String timestamp = stringValue(body.get("timestamp"));
        String sign = stringValue(body.get("sign"));

        log.info("{}收到退款请求，outRefundNo={}, outTradeNo={}, amount={}",
                LOG_PREFIX, outRefundNo, outTradeNo, refundAmount);

        // 1. 校验商户签名（方向：商户 → 第三方）
        Map<String, Object> signParams = new LinkedHashMap<>();
        signParams.put("out_refund_no", outRefundNo);
        signParams.put("out_trade_no", outTradeNo);
        signParams.put("refund_amount", refundAmount);
        signParams.put("notify_url", notifyUrl);
        signParams.put("timestamp", timestamp);
        if (!PaySignUtil.verify(signParams, props.getSecretKey(), sign)) {
            log.error("{}商户退款请求验签失败，拒绝处理，outRefundNo={}", LOG_PREFIX, outRefundNo);
            return result(false, "商户签名校验失败");
        }

        // 2. 幂等：同一退款单号重复请求 → 直接重发上次结果（台账在共享存储里，多实例一致）
        String existRefundTradeNo = ledger.getRefundTradeNo(outRefundNo);
        if (existRefundTradeNo != null) {
            log.warn("{}重复退款请求，重发上次处理结果，outRefundNo={}", LOG_PREFIX, outRefundNo);
            return resendRefundResult(outRefundNo, outTradeNo, refundAmount, existRefundTradeNo, notifyUrl);
        }

        // 3. 校验原交易确实存在且已收款（共享台账：实例 A 收的款，实例 B 也查得到）
        if (!ledger.isPaid(outTradeNo)) {
            log.error("{}原交易不存在或未收款，拒绝退款，outTradeNo={}", LOG_PREFIX, outTradeNo);
            return result(false, "原交易不存在或未收款");
        }

        // 4. 原子占位退款单号：并发/多实例把同一退款单打到不同实例时，只有一方能受理，另一方走幂等重发
        String refundTradeNo = generateRefundTradeNo();
        if (!ledger.claimRefund(outRefundNo, refundTradeNo)) {
            String winner = ledger.getRefundTradeNo(outRefundNo);
            log.warn("{}并发退款请求已被其它实例受理，重发其结果，outRefundNo={}", LOG_PREFIX, outRefundNo);
            return resendRefundResult(outRefundNo, outTradeNo, refundAmount, winner, notifyUrl);
        }

        // 5. 防止同一笔交易被重复退款（资损红线）：多实例下由共享台账原子判定
        if (!ledger.claimTradeRefunded(outTradeNo)) {
            // 本次受理作废：释放刚占的退款单号，避免"白占号"导致该退款单的合法重试被误判为"已受理"
            ledger.releaseRefund(outRefundNo);
            log.error("{}该交易已发起过退款，拒绝重复退款，outTradeNo={}", LOG_PREFIX, outTradeNo);
            return result(false, "该交易已退款，不可重复退款");
        }

        // 6/7. 组装退款结果通知 → 签名 → 异步回调商户
        boolean notified = sendRefundResult(outRefundNo, outTradeNo, refundAmount, refundTradeNo, notifyUrl);
        if (!notified) {
            log.error("{}退款结果通知商户失败，outRefundNo={}（真实渠道会按策略重推，商户也可主动查单）",
                    LOG_PREFIX, outRefundNo);
            return result(false, "通知商户失败，请稍后重试");
        }

        log.info("{}退款处理完成，outRefundNo={}, refundTradeNo={}", LOG_PREFIX, outRefundNo, refundTradeNo);
        return result(true, "退款受理成功，结果已通知商户");
    }


    // 幂等重发退款结果，并统一包装返回
    private Map<String, Object> resendRefundResult(String outRefundNo, String outTradeNo,
                                                   String refundAmount, String refundTradeNo, String notifyUrl) {
        boolean resent = sendRefundResult(outRefundNo, outTradeNo, refundAmount, refundTradeNo, notifyUrl);
        return resent
                ? result(true, "该退款单已受理，处理结果已重发")
                : result(false, "通知商户失败，请稍后重试");
    }


    // 组装退款结果通知 → 签名 → 回调商户
    private boolean sendRefundResult(String outRefundNo, String outTradeNo, String refundAmount,
                                     String refundTradeNo, String notifyUrl) {
        Map<String, Object> notifyParams = new LinkedHashMap<>();
        notifyParams.put("out_refund_no", outRefundNo);
        notifyParams.put("refund_trade_no", refundTradeNo);
        notifyParams.put("out_trade_no", outTradeNo);
        notifyParams.put("refund_amount", refundAmount);
        notifyParams.put("status", 1);              // 1 = 退款成功
        notifyParams.put("reason", null);           // 成功时无失败原因（空值不参与签名）
        notifyParams.put("timestamp", System.currentTimeMillis());
        String notifySign = PaySignUtil.sign(notifyParams, props.getSecretKey());

        Map<String, Object> notifyBody = new LinkedHashMap<>(notifyParams);
        notifyBody.put("sign", notifySign);

        return sendRefundNotify(notifyBody, notifyUrl);
    }


    // 把签名后的参数 POST 给商户的 notify_url
    private boolean sendNotify(Map<String, Object> notifyBody) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(notifyBody, headers);

            String resp = restTemplate.postForObject(props.getNotifyUrl(), entity, String.class);
            log.info("{}商户回调应答：{}", LOG_PREFIX, resp);
            // 商户必须应答 success，否则第三方会（在未来版本中）重推
            return "success".equalsIgnoreCase(resp == null ? "" : resp.trim());
        } catch (Exception e) {
            log.error("{}回调商户失败，notifyUrl={}", LOG_PREFIX, props.getNotifyUrl(), e);
            return false;
        }
    }

    // 把签名后的退款结果 POST 给商户的退款回调地址
    private boolean sendRefundNotify(Map<String, Object> notifyBody, String notifyUrl) {
        String target = (notifyUrl == null || notifyUrl.trim().isEmpty())
                ? props.getRefundNotifyUrl()
                : notifyUrl.trim();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(notifyBody, headers);

            String resp = restTemplate.postForObject(target, entity, String.class);
            log.info("{}商户退款回调应答：{}", LOG_PREFIX, resp);
            return "success".equalsIgnoreCase(resp == null ? "" : resp.trim());
        } catch (Exception e) {
            log.error("{}退款回调商户失败，notifyUrl={}", LOG_PREFIX, target, e);
            return false;
        }
    }

    private String generateTradeNo() {
        return "MOCK"
                + LocalDateTime.now().format(TRADE_NO_FMT)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private String generateRefundTradeNo() {
        return "MOCKREF"
                + LocalDateTime.now().format(TRADE_NO_FMT)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /** 统一返回结构，方便前端展示 */
    private Map<String, Object> result(boolean success, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("message", message);
        return map;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        // 只有浮点/Decimal 金额才需要规整成两位小数；
        // 整数（如 timestamp 这个 long）直接 toString，绝不能 setScale——
        // 否则 1792039653626 会变成 1792039653626.00，与商户侧签名的待签串不一致，验签必失败。
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        return String.valueOf(value);
    }
}
