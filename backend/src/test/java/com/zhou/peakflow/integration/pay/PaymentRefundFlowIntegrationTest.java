package com.zhou.peakflow.integration.pay;

import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.enums.PayStatus;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.integration.support.IntegrationTestBase;
import com.zhou.peakflow.utils.PaySignUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付与退款链路 <b>集成测试</b>。
 *
 * <p>用集成测试的原因：CAS SQL 的真实影响行数（"UPDATE ... WHERE status = 0"到底影响几行）只有真实 DB
 * 能验，而这正是"重复回调不会重复入账"的唯一依据；退款成功要同时改退款单 + 订单 + 支付流水三张表，
 * {@code @Transactional} 是否真的生效只有真实上下文能验；回调报文留档、签名契约的端到端有效性同理。</p>
 *
 * <p>关于"为什么不用 MockPay 走完整链路"：{@code POST /mock-pay/pay} 会以服务器到服务器的 HTTP 调用
 * 回推 {@code /pay/notify}，地址是配置里的 {@code http://127.0.0.1:8080}，而本类基于
 * {@code @SpringBootTest + MockMvc}，不会真的监听 8080 端口。故本类手工构造带合法签名的回调报文直接打
 * {@code /pay/notify}，把验证重点落在服务端的真实 SQL / 事务 / 约束上（完整第三方交互链路属端到端测试）。</p>
 *
 * <p>环境依赖：MySQL 3306 / Redis 6380 / <b>RocketMQ 9876</b>（上下文启动需要）。</p>
 */
@DisplayName("支付与退款链路集成测试")
class PaymentRefundFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockPayProperties mockPayProperties;

    // 造出来的数据坐标（@AfterEach 清理）

    private Long userId;
    private Long categoryId;
    private Long productId;
    private Long addressId;
    private Long orderId;
    private Long refundId;
    private String outTradeNo;
    private String orderNo;
    private String outRefundNo;

    private static final BigDecimal AMOUNT = new BigDecimal("199.00");
    private static final String TRADE_NO = "MOCK-TRADE-IT-0001";
    private static final String REFUND_TRADE_NO = "MOCK-REFUND-IT-0001";

    @AfterEach
    void cleanup() {
        safe(() -> { if (refundId != null) jdbcTemplate.update("DELETE FROM refund_record WHERE id = ?", refundId); });
        safe(() -> { if (orderId != null) jdbcTemplate.update("DELETE FROM refund_record WHERE order_id = ?", orderId); });
        safe(() -> { if (outTradeNo != null) jdbcTemplate.update("DELETE FROM pay_record WHERE out_trade_no = ?", outTradeNo); });
        safe(() -> { if (orderId != null) jdbcTemplate.update("DELETE FROM `order` WHERE id = ?", orderId); });
        safe(() -> { if (addressId != null) jdbcTemplate.update("DELETE FROM address WHERE id = ?", addressId); });
        safe(() -> { if (productId != null) jdbcTemplate.update("DELETE FROM product WHERE id = ?", productId); });
        safe(() -> { if (categoryId != null) jdbcTemplate.update("DELETE FROM category WHERE id = ?", categoryId); });
        userId = categoryId = productId = addressId = orderId = refundId = null;
        outTradeNo = orderNo = outRefundNo = null;
    }

    // 一、支付链路（真实 SQL + 事务）

    @Test
    @DisplayName("[业务正确] 合法支付回调 → 订单 0→1、支付单 0→1，且【回调报文原文真的落库】")
    void payNotify_legal_shouldMarkOrderAndPayRecordPaid() throws Exception {
        preparePendingOrder();

        String resp = notifyPay(outTradeNo, AMOUNT, true);

        assertEquals("success", resp, "合法回调应返回 success");
        // 断言真实的 SQL 结果（不是接口自述）
        assertEquals(PayStatus.SUCCESS.getCode(), queryPayStatus(outTradeNo),
                "支付单应被 CAS 更新为「支付成功」");
        assertEquals(TRADE_NO, queryPayTradeNo(outTradeNo), "应记录第三方交易号");
        assertEquals(OrderStatus.PAID.getCode(), queryOrderStatus(orderId),
                "订单应被改为「已付款」");
        assertNotNull(queryNotifyContent(outTradeNo), "回调报文原文应落库留档（对账依据）");
    }

    @Test
    @DisplayName("[幂等·真实 CAS] 重复支付回调 → 返回 success，但支付单【update_time 不变】（证明没执行 UPDATE）")
    void payNotify_duplicate_shouldNotUpdateAgain() throws Exception {
        preparePendingOrder();
        assertEquals("success", notifyPay(outTradeNo, AMOUNT, true), "第一次应成功");

        String updateTimeAfterFirst = queryPayUpdateTime(outTradeNo);

        // 第二次（第三方重推）
        String resp = notifyPay(outTradeNo, AMOUNT, true);

        assertEquals("success", resp, "重复回调也应应答 success 让第三方停止重推");
        // 最强证据：update_time 没变 ⇒ 真的没执行 UPDATE（不是"更新了但值相同"）
        assertEquals(updateTimeAfterFirst, queryPayUpdateTime(outTradeNo),
                "重复回调不该产生任何 UPDATE —— 这是数据库层面能给出的最硬证据");
    }

    @Test
    @DisplayName("[攻击·改价] 回调金额与支付单不一致 → 返回 failure，且【DB 完全无变化】")
    void payNotify_amountTampered_shouldRejectWithoutDbChange() throws Exception {
        preparePendingOrder();
        String updateTimeBefore = queryPayUpdateTime(outTradeNo);

        String resp = notifyPay(outTradeNo, new BigDecimal("0.01"), true);

        assertEquals("failure", resp, "金额不符必须拒绝（否则 1 分钱就能买到 199 元的货）");
        assertEquals(PayStatus.WAIT_PAY.getCode(), queryPayStatus(outTradeNo),
                "支付单应保持「待支付」");
        assertEquals(OrderStatus.WAIT_PAY.getCode(), queryOrderStatus(orderId),
                "订单应保持「待付款」");
        assertEquals(updateTimeBefore, queryPayUpdateTime(outTradeNo),
                "被拒绝的回调不该产生任何写操作");
    }

    @Test
    @DisplayName("[验证失败] 伪造签名的回调 → 返回 failure，且 DB 完全无变化")
    void payNotify_forgedSign_shouldRejectWithoutDbChange() throws Exception {
        preparePendingOrder();

        String resp = notifyPay(outTradeNo, AMOUNT, false);

        assertEquals("failure", resp);
        assertEquals(PayStatus.WAIT_PAY.getCode(), queryPayStatus(outTradeNo),
                "验签不过绝不能入账");
    }

    // 二、退款链路（跨表事务一致性）

    @Test
    @DisplayName("[跨表事务] 退款成功回调 → 退款单=成功、订单=已退款、支付流水=已退款（三表必须一致）")
    void refundNotify_success_shouldUpdateAllThreeTables() throws Exception {
        preparePaidOrderAndRefundingRecord();

        String resp = notifyRefund(outRefundNo, AMOUNT, 1, true);

        assertEquals("success", resp, "合法退款回调应返回 success");

        // 三张表的状态必须在同一个事务里一起变 —— 少改一张就是数据不一致
        assertEquals(RefundStatus.SUCCESS.getCode(), queryRefundStatus(outRefundNo),
                "① 退款单应变为「退款成功」");
        assertEquals(OrderStatus.REFUNDED.getCode(), queryOrderStatus(orderId),
                "② 订单应变为「已退款」");
        assertEquals(PayStatus.REFUNDED.getCode(), queryPayStatus(outTradeNo),
                "③ 支付流水应变为「已退款」");
    }

    @Test
    @DisplayName("[幂等] 重复退款成功回调 → 返回 success，但三表状态不再变化")
    void refundNotify_duplicate_shouldBeIdempotent() throws Exception {
        preparePaidOrderAndRefundingRecord();
        assertEquals("success", notifyRefund(outRefundNo, AMOUNT, 1, true));

        String orderUpdateBefore = queryOrderUpdateTime(orderId);

        // 重复推送
        String resp = notifyRefund(outRefundNo, AMOUNT, 1, true);

        assertEquals("success", resp, "重复回调应答 success");
        assertEquals(RefundStatus.SUCCESS.getCode(), queryRefundStatus(outRefundNo));
        assertEquals(OrderStatus.REFUNDED.getCode(), queryOrderStatus(orderId));
        assertEquals(orderUpdateBefore, queryOrderUpdateTime(orderId),
                "重复回调不该再动订单（幂等第一道在「读」这层就拦下了）");
    }

    @Test
    @DisplayName("[攻击·改价] 退款回调金额与本地不一致 → 返回 failure，三表无变化")
    void refundNotify_amountMismatch_shouldReject() throws Exception {
        preparePaidOrderAndRefundingRecord();

        String resp = notifyRefund(outRefundNo, new BigDecimal("99999.00"), 1, true);

        assertEquals("failure", resp, "退款金额不符必须拒绝");
        assertEquals(RefundStatus.REFUNDING.getCode(), queryRefundStatus(outRefundNo),
                "退款单应保持「退款中」");
        assertEquals(OrderStatus.PAID.getCode(), queryOrderStatus(orderId),
                "订单不该被改成已退款");
    }

    @Test
    @DisplayName("[唯一约束·走接口] 同一订单第二次申请退款 → 真实 uk_open_order 拦下，返回 3033")
    void applyRefund_twice_shouldBeBlockedByUniqueIndex() throws Exception {
        // 用「真实注册用户」—— 因为退款接口需要 token，而 jdbcTemplate 造的用户密码不是 BCrypt 密文、登不上
        String username = uniqueUsername();
        registerAndGetUsername(username, DEFAULT_PASSWORD);
        String token = loginAndGetToken(username, DEFAULT_PASSWORD);
        userId = queryUserId(username);

        // 造一条「已付款」订单（退款校验要求订单处于可退状态）
        preparePaidOrderForRegisteredUser(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("reason", "IT 测试退款");

        // 第一次申请：应成功建单
        MvcResult first = postJson("/refund/apply", body, token, uniqueIp());
        assertSuccess(first);
        long firstRefundId = bizData(first).asLong();
        assertTrue(firstRefundId > 0, "第一次申请应返回退款单 id");
        // 记住它，@AfterEach 清理
        refundId = firstRefundId;
        outRefundNo = jdbcTemplate.queryForObject(
                "SELECT refund_no FROM refund_record WHERE id = ?", String.class, firstRefundId);

        // 第二次申请：真实链路会一路走到落库，然后被数据库唯一索引 uk_open_order 拦下
        MvcResult second = postJson("/refund/apply", body, token, uniqueIp());

        assertEquals(3033, bizCode(second),
                "重复申请退款必须被拦下并转成 3033（防重第 3 层：唯一索引）。实际：" + bizMsg(second));
        // 路径断言：库里仍然只有一条退款单
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_record WHERE order_id = ?", Integer.class, orderId);
        assertEquals(1, cnt, "唯一索引必须保证同一订单只有一条未完结退款单");
    }

    // 助手：造数

    /** 造：分类 / 商品 / 用户 / 地址 / 「待付款」订单 / 「待支付」支付单 */
    private void preparePendingOrder() {
        prepareBaseFixture();
        orderNo = "ORDIT-" + System.nanoTime();
        outTradeNo = "PAYIT-" + System.nanoTime();

        jdbcTemplate.update(
                "INSERT INTO `order` (order_no, user_id, address_id, total_amount, pay_amount, status, "
                        + "order_type, is_seckill, version, deleted, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, NOW(), NOW())",
                orderNo, userId, addressId, AMOUNT, AMOUNT, OrderStatus.WAIT_PAY.getCode());
        orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM `order` WHERE order_no = ?", Long.class, orderNo);

        jdbcTemplate.update(
                "INSERT INTO pay_record (out_trade_no, order_id, order_no, user_id, amount, pay_channel, "
                        + "status, version, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, 'MOCK_PAY', ?, 0, NOW(), NOW())",
                outTradeNo, orderId, orderNo, userId, AMOUNT, PayStatus.WAIT_PAY.getCode());
    }

    /** 造：「已付款」订单 + 「已成功」支付单 + 「退款中」退款单（用于测退款回调） */
    private void preparePaidOrderAndRefundingRecord() {
        prepareBaseFixture();
        orderNo = "ORDIT-" + System.nanoTime();
        outTradeNo = "PAYIT-" + System.nanoTime();
        outRefundNo = "REFIT-" + System.nanoTime();

        jdbcTemplate.update(
                "INSERT INTO `order` (order_no, user_id, address_id, total_amount, pay_amount, status, "
                        + "order_type, is_seckill, version, deleted, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, NOW(), NOW())",
                orderNo, userId, addressId, AMOUNT, AMOUNT, OrderStatus.PAID.getCode());
        orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM `order` WHERE order_no = ?", Long.class, orderNo);

        jdbcTemplate.update(
                "INSERT INTO pay_record (out_trade_no, order_id, order_no, user_id, amount, pay_channel, "
                        + "status, version, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, 'MOCK_PAY', ?, 0, NOW(), NOW())",
                outTradeNo, orderId, orderNo, userId, AMOUNT, PayStatus.SUCCESS.getCode());

        jdbcTemplate.update(
                "INSERT INTO refund_record (refund_no, order_id, order_no, user_id, out_trade_no, refund_amount, "
                        + "refund_type, refund_channel, status, apply_time, version, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 'MOCK_PAY', ?, NOW(), 0, NOW(), NOW())",
                outRefundNo, orderId, orderNo, userId, outTradeNo, AMOUNT, RefundStatus.REFUNDING.getCode());
        refundId = jdbcTemplate.queryForObject(
                "SELECT id FROM refund_record WHERE refund_no = ?", Long.class, outRefundNo);
    }

    /** 为「真实注册用户」造：地址 + 「已付款」订单 + 「已成功」支付单（走接口的退款用例需要） */
    private void preparePaidOrderForRegisteredUser(Long realUserId) {
        orderNo = "ORDIT-" + System.nanoTime();
        outTradeNo = "PAYIT-" + System.nanoTime();

        jdbcTemplate.update(
                "INSERT INTO address (user_id, receiver_name, receiver_phone, province, city, district, "
                        + "detail_address, is_default, deleted, create_time, update_time) "
                        + "VALUES (?, 'IT', '13800000000', '江苏省', '南京市', '鼓楼区', '测试路1号', 1, 0, NOW(), NOW())",
                realUserId);
        addressId = jdbcTemplate.queryForObject(
                "SELECT id FROM address WHERE user_id = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
                Long.class, realUserId);

        jdbcTemplate.update(
                "INSERT INTO `order` (order_no, user_id, address_id, total_amount, pay_amount, status, "
                        + "order_type, is_seckill, version, deleted, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, NOW(), NOW())",
                orderNo, realUserId, addressId, AMOUNT, AMOUNT, OrderStatus.PAID.getCode());
        orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM `order` WHERE order_no = ?", Long.class, orderNo);

        jdbcTemplate.update(
                "INSERT INTO pay_record (out_trade_no, order_id, order_no, user_id, amount, pay_channel, "
                        + "status, version, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, 'MOCK_PAY', ?, 0, NOW(), NOW())",
                outTradeNo, orderId, orderNo, realUserId, AMOUNT, PayStatus.SUCCESS.getCode());
    }

    /** 基础四件套：分类 / 商品 / 用户 / 地址 */
    private void prepareBaseFixture() {
        String suffix = Long.toString(System.currentTimeMillis(), 36)
                + ThreadLocalRandom.current().nextInt(100, 1000);

        jdbcTemplate.update(
                "INSERT INTO category (name, icon, sort, status, deleted, create_time, update_time) "
                        + "VALUES (?, 'it', 99, 1, 0, NOW(), NOW())", "IT支付分类" + suffix);
        categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, "IT支付分类" + suffix);

        jdbcTemplate.update(
                "INSERT INTO product (name, description, detail, image, price, stock, seckill_stock, sold_count, "
                        + "category_id, is_seckill, status, version, deleted, create_time, update_time) "
                        + "VALUES (?, 'IT', 'IT', 'it.jpg', 199.00, 100, 0, 0, ?, 0, 1, 0, 0, NOW(), NOW())",
                "IT支付商品" + suffix, categoryId);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ?", Long.class, "IT支付商品" + suffix);

        String username = uniqueUsername();
        jdbcTemplate.update(
                "INSERT INTO user (username, password, email, nickname, status, is_temp_password, deleted, "
                        + "create_time, update_time) VALUES (?, 'x', ?, 'IT', 1, 0, 0, NOW(), NOW())",
                username, username + "@itest.com");
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE username = ?", Long.class, username);

        jdbcTemplate.update(
                "INSERT INTO address (user_id, receiver_name, receiver_phone, province, city, district, "
                        + "detail_address, is_default, deleted, create_time, update_time) "
                        + "VALUES (?, 'IT', '13800000000', '江苏省', '南京市', '鼓楼区', '测试路1号', 1, 0, NOW(), NOW())",
                userId);
        addressId = jdbcTemplate.queryForObject(
                "SELECT id FROM address WHERE user_id = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
                Long.class, userId);
    }

    // 助手：发起回调（构造真实签名报文）

    /**
     * 发起支付回调。
     *
     * <p>注意本方法体现的隐式契约：请求体与签名都用 snake_case。第三方（MockPay）就是按 snake_case
     * 回传的，DTO 侧用 {@code @JsonProperty("out_trade_no")} 接住；若这里发驼峰，字段会反序列化成 null
     * → 验签缺参数 → 回调被拒。</p>
     */
    private String notifyPay(String outTradeNo, BigDecimal amount, boolean validSign) throws Exception {
        long ts = System.currentTimeMillis();

        Map<String, Object> signParams = new LinkedHashMap<>();
        signParams.put("out_trade_no", outTradeNo);
        signParams.put("trade_no", TRADE_NO);
        signParams.put("amount", amount == null ? null : amount.toPlainString());
        signParams.put("status", 1);
        signParams.put("timestamp", ts);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_trade_no", outTradeNo);
        body.put("trade_no", TRADE_NO);
        body.put("amount", amount);
        body.put("status", 1);
        body.put("timestamp", ts);
        body.put("sign", validSign ? PaySignUtil.sign(signParams, mockPayProperties.getSecretKey()) : "FORGED-SIGN");

        var result = postJson("/pay/notify", body);
        assertEquals(200, result.getResponse().getStatus(), "回调接口应返回 HTTP 200");
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8).trim();
    }

    /** 发起退款回调（status：1=成功 0=失败） */
    private String notifyRefund(String outRefundNo, BigDecimal amount, int status, boolean validSign) throws Exception {
        long ts = System.currentTimeMillis();
        String reason = "渠道退回";

        Map<String, Object> signParams = new LinkedHashMap<>();
        signParams.put("out_refund_no", outRefundNo);
        signParams.put("refund_trade_no", REFUND_TRADE_NO);
        signParams.put("out_trade_no", outTradeNo);
        signParams.put("refund_amount", amount == null ? null : amount.toPlainString());
        signParams.put("status", status);
        signParams.put("reason", reason);
        signParams.put("timestamp", ts);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_refund_no", outRefundNo);
        body.put("refund_trade_no", REFUND_TRADE_NO);
        body.put("out_trade_no", outTradeNo);
        body.put("refund_amount", amount);
        body.put("status", status);
        body.put("reason", reason);
        body.put("timestamp", ts);
        body.put("sign", validSign ? PaySignUtil.sign(signParams, mockPayProperties.getSecretKey()) : "FORGED-SIGN");

        var result = postJson("/refund/notify", body);
        assertEquals(200, result.getResponse().getStatus(), "退款回调接口应返回 HTTP 200");
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8).trim();
    }

    // 助手：查库断言

    private int queryPayStatus(String outTradeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM pay_record WHERE out_trade_no = ?", Integer.class, outTradeNo);
    }

    private String queryPayTradeNo(String outTradeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT trade_no FROM pay_record WHERE out_trade_no = ?", String.class, outTradeNo);
    }

    private String queryNotifyContent(String outTradeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT notify_content FROM pay_record WHERE out_trade_no = ?", String.class, outTradeNo);
    }

    private String queryPayUpdateTime(String outTradeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') FROM pay_record WHERE out_trade_no = ?",
                String.class, outTradeNo);
    }

    private int queryOrderStatus(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, id);
    }

    private String queryOrderUpdateTime(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') FROM `order` WHERE id = ?",
                String.class, id);
    }

    private int queryRefundStatus(String refundNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM refund_record WHERE refund_no = ?", Integer.class, refundNo);
    }

    private void safe(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 清理失败不影响判定
        }
    }
}
