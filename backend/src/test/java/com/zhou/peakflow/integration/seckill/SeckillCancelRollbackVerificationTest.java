package com.zhou.peakflow.integration.seckill;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.integration.support.IntegrationTestBase;
import com.zhou.peakflow.vo.SeckillResultVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒杀取消回滚专项 <b>集成测试</b>：订单取消时 Redis 预扣库存是否被正确回补。
 *
 * <p>秒杀库存在两处各存一份，取消时必须两边都回补：DB {@code product.seckill_stock}（权威）
 * 与 Redis {@code seckill:stock:{productId}}（入口预扣的可抢名额）。只回补 DB 而不回补 Redis
 * 会造成库存漂移 —— 可抢名额越用越少（少卖）。</p>
 *
 * <p>被测链路：POST /seckill/order（门闩 + Redis 预扣(-1) + 发 MQ）
 * → 消费者异步落库（DB -1）；POST /order/cancel/{id} → rollbackStock 分两支：
 * DB rollbackSeckillStockByQuantity(+1) 与 Redis rollbackRedisStock(+1)。</p>
 *
 * <p>回补 Lua 先判 exists，Key 不存在则返回 -1 不回补 —— 避免凭空 INCR 造出幽灵库存。
 * Redis 侧宁可少回补，也不能凭空造。</p>
 *
 * <p>环境依赖：MySQL 3306 / <b>Redis 6380</b> / <b>RocketMQ 9876</b>。</p>
 */
@DisplayName("秒杀取消 → Redis 库存回滚专项验证")
class SeckillCancelRollbackVerificationTest extends IntegrationTestBase {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String GATE_KEY_PREFIX = "seckill:order:success:";

    private Long createdProductId;
    private Long createdCategoryId;

    @AfterEach
    void cleanup() {
        if (createdProductId != null) {
            try {
                // 清 Redis 痕迹：库存 key + 商品缓存
                stringRedisTemplate.delete(STOCK_KEY_PREFIX + createdProductId);
                stringRedisTemplate.delete("seckill:product:detail:" + createdProductId);
                jdbcTemplate.update("DELETE FROM product WHERE id = ?", createdProductId);
            } catch (Exception ignored) {
                // 清理失败不影响判定
            }
            createdProductId = null;
        }
        if (createdCategoryId != null) {
            try {
                jdbcTemplate.update("DELETE FROM category WHERE id = ?", createdCategoryId);
            } catch (Exception ignored) {
                // ignore
            }
            createdCategoryId = null;
        }
    }

    // 核心用例：取消订单 → Redis 与 DB 库存都必须回滚

    @Test
    @DisplayName("[核心] 秒杀下单(扣减) → 取消订单 → Redis 预扣库存【回滚到原值】，DB 同步回滚")
    void cancelSeckillOrder_shouldRollbackRedisStockAndDbStock() throws Exception {
        int initialStock = 2;
        Fixture f = prepareSeckillProduct(initialStock);
        String stockKey = STOCK_KEY_PREFIX + f.productId;

        // ① 秒杀下单，等待异步落库
        MvcResult orderResp = postJson("/seckill/order",
                seckillBody(f.productId, f.addressId), f.token, uniqueIp());
        assertSuccess(orderResp);
        String requestId = bizData(orderResp).asText();

        JsonNode result = pollSeckillResult(requestId, 20, f.token);
        assertEquals(SeckillResultVo.STATUS_SUCCESS, result.path("status").asInt(),
                "秒杀应成交，实际：" + result);
        long orderId = result.path("orderId").asLong();
        assertTrue(orderId > 0);

        // ② 断言下单后两边都扣了
        String redisAfterDeduct = stringRedisTemplate.opsForValue().get(stockKey);
        assertNotNull(redisAfterDeduct,
                "Redis 预扣 key 必须存在（说明入口真的扣了）：" + stockKey);
        assertEquals(String.valueOf(initialStock - 1), redisAfterDeduct,
                "Redis 预扣库存应为 " + (initialStock - 1));
        assertEquals(initialStock - 1, queryDbSeckillStock(f.productId),
                "DB 秒杀库存也应为 " + (initialStock - 1));

        // ③ 取消订单
        MvcResult cancelResp = postJson("/order/cancel/" + orderId, null, f.token, uniqueIp());
        assertSuccess(cancelResp);

        // ④ 核心断言：两边都回滚
        String redisAfterCancel = stringRedisTemplate.opsForValue().get(stockKey);
        assertEquals(String.valueOf(initialStock), redisAfterCancel,
                "取消订单后 Redis 预扣库存必须回滚到 " + initialStock
                        + "（否则出现库存漂移 ⇒ 可抢名额越用越少）");
        assertEquals(initialStock, queryDbSeckillStock(f.productId),
                "DB 秒杀库存也必须回滚到 " + initialStock);
        assertEquals(OrderStatus.CANCELLED.getCode(), queryOrderStatus(orderId),
                "订单状态应为「已取消」");

        // ⑤ 副作用确认：秒杀标记为已取消，不再占"一人一单"名额
        assertNull(querySeckillSlot(orderId),
                "已取消的秒杀订单，生成列 seckill_slot 应为 NULL（否则用户无法再抢）");
    }

    // 边界：Redis Key 不存在时，绝不能凭空回补（防幽灵库存）

    @Test
    @DisplayName("[边界] 取消前 Redis 库存 Key 已消失 → 【不凭空回补】（防幽灵库存），但 DB 仍回补")
    void cancelWhenRedisKeyMissing_shouldNotCreateGhostStock() throws Exception {
        int initialStock = 3;
        Fixture f = prepareSeckillProduct(initialStock);
        String stockKey = STOCK_KEY_PREFIX + f.productId;

        // 下单并等落库
        MvcResult orderResp = postJson("/seckill/order",
                seckillBody(f.productId, f.addressId), f.token, uniqueIp());
        assertSuccess(orderResp);
        JsonNode result = pollSeckillResult(bizData(orderResp).asText(), 20, f.token);
        assertEquals(SeckillResultVo.STATUS_SUCCESS, result.path("status").asInt());
        long orderId = result.path("orderId").asLong();

        // 人为模拟"Key 已过期/被清"（生产上 TTL 到了就会这样）
        stringRedisTemplate.delete(stockKey);
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey)),
                "前置条件：Redis 库存 key 已被清掉");

        // 取消订单
        MvcResult cancelResp = postJson("/order/cancel/" + orderId, null, f.token, uniqueIp());
        assertSuccess(cancelResp);

        // 核心断言：Redis 侧绝不能凭空创建 key（Lua 的 exists 判断就是为此）
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey)),
                "Key 不存在时回补会制造『幽灵库存』—— Redis 里凭空多出可抢名额，"
                        + "用户抢到却会在下单时失败。Lua 的 exists 判断必须拦住它");

        // DB 侧是权威，照常回补
        assertEquals(initialStock, queryDbSeckillStock(f.productId),
                "DB 是权威库存，取消后应正常回补到 " + initialStock);
    }

    // 业务规则观察：取消后能否再抢（验证购买权门闩是否释放）

    @Test
    @DisplayName("[业务规则] 取消订单后 Redis 回滚了库存 —— 但该用户【还能不能再抢】？验证真实行为")
    void afterCancel_observeWhetherUserCanSeckillAgain() throws Exception {
        int initialStock = 5;
        Fixture f = prepareSeckillProduct(initialStock);
        Long userId = queryUserId(f.username);
        String stockKey = STOCK_KEY_PREFIX + f.productId;
        String gateKey = GATE_KEY_PREFIX + userId + ":" + f.productId;

        // 第一次抢购并成交
        MvcResult first = postJson("/seckill/order",
                seckillBody(f.productId, f.addressId), f.token, uniqueIp());
        assertSuccess(first);
        JsonNode r1 = pollSeckillResult(bizData(first).asText(), 20, f.token);
        assertEquals(SeckillResultVo.STATUS_SUCCESS, r1.path("status").asInt());
        long orderId = r1.path("orderId").asLong();

        // 取消订单（库存已回滚）
        assertSuccess(postJson("/order/cancel/" + orderId, null, f.token, uniqueIp()));
        assertEquals(String.valueOf(initialStock), stringRedisTemplate.opsForValue().get(stockKey),
                "库存已回滚（前置条件成立）");

        // 观察：购买权门闩还在不在？
        Boolean gateExists = stringRedisTemplate.hasKey(gateKey);

        // 尝试第二次抢购
        MvcResult second = postJson("/seckill/order",
                seckillBody(f.productId, f.addressId), f.token, uniqueIp());

        System.out.println("════════ [业务规则观察] ════════");
        System.out.println("  取消后购买权门闩是否仍存在 : " + gateExists + "  (key=" + gateKey + ")");
        System.out.println("  第二次抢购响应码          : " + bizCode(second));
        System.out.println("  第二次抢购消息            : " + bizMsg(second));
        System.out.println("  当前 Redis 库存           : "
                + stringRedisTemplate.opsForValue().get(stockKey));
        System.out.println("════════════════════════════════");

        // 关键实验：隔 1.5s 再抢一次，与上面紧邻的第二次抢购形成对照，
        //    用于确认门闩到底有没有释放（门闩未释放时两次都会被拦）。
        Thread.sleep(1500);
        MvcResult third = postJson("/seckill/order",
                seckillBody(f.productId, f.addressId), f.token, uniqueIp());
        System.out.println("  ── 等限流窗口(1.5s)后第三次抢购 ──");
        System.out.println("  响应码 : " + bizCode(third));
        System.out.println("  消息   : " + bizMsg(third));
        System.out.println("  库存   : " + stringRedisTemplate.opsForValue().get(stockKey));
        System.out.println("  ⇒ 若消息是『已参与过』则证明【门闩确实没释放】(TTL=24h)");

        if (Boolean.TRUE.equals(gateExists)) {
            // 门闩未释放：一人一单规则下"参与过就不能再抢"（即便取消）
            assertTrue(bizCode(second) != CODE_SUCCESS || bizMsg(second).contains("已参与过")
                            || bizMsg(second).contains("频繁"),
                    "门闩仍存在时，第二次抢购应被拦（一人一单 / 限流）。实际：" + bizMsg(second));
        }
        // 无论哪种结果，本用例的目的都是【记录真实行为】并暴露这条规则
    }

    // 助手

    private record Fixture(Long productId, Long categoryId, Long addressId, String token, String username) {
    }

    /** 造「进行中 + 指定库存」的秒杀商品 + 收货地址，并注册登录拿 token */
    private Fixture prepareSeckillProduct(int seckillStock) throws Exception {
        String suffix = Long.toString(System.currentTimeMillis(), 36)
                + ThreadLocalRandom.current().nextInt(100, 1000);

        jdbcTemplate.update(
                "INSERT INTO category (name, icon, sort, status, deleted, create_time, update_time) "
                        + "VALUES (?, 'it', 99, 1, 0, NOW(), NOW())", "IT回滚分类" + suffix);
        createdCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, "IT回滚分类" + suffix);

        jdbcTemplate.update(
                "INSERT INTO product (name, description, detail, image, price, seckill_price, stock, "
                        + "seckill_stock, sold_count, category_id, is_seckill, seckill_start_time, "
                        + "seckill_end_time, status, version, deleted, create_time, update_time) "
                        + "VALUES (?, 'IT', 'IT', 'it.jpg', 199.00, 99.00, 0, ?, 0, ?, 1, "
                        + "DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY), "
                        + "1, 0, 0, NOW(), NOW())",
                "IT回滚商品" + suffix, seckillStock, createdCategoryId);
        createdProductId = jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ?", Long.class, "IT回滚商品" + suffix);

        String username = uniqueUsername();
        registerAndGetUsername(username, DEFAULT_PASSWORD);
        String token = loginAndGetToken(username, DEFAULT_PASSWORD);
        Long userId = queryUserId(username);

        jdbcTemplate.update(
                "INSERT INTO address (user_id, receiver_name, receiver_phone, province, city, district, "
                        + "detail_address, is_default, deleted, create_time, update_time) "
                        + "VALUES (?, 'IT收货人', '13800000000', '江苏省', '南京市', '鼓楼区', '回滚测试路1号', 1, 0, NOW(), NOW())",
                userId);
        Long addressId = jdbcTemplate.queryForObject(
                "SELECT id FROM address WHERE user_id = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
                Long.class, userId);

        return new Fixture(createdProductId, createdCategoryId, addressId, token, username);
    }

    private Map<String, Object> seckillBody(Long productId, Long addressId) {
        Map<String, Object> body = new HashMap<>();
        body.put("productId", productId);
        body.put("addressId", addressId);
        return body;
    }

    /** 轮询秒杀结果直到终态（1=成功 / 2=失败），0=排队中需继续等 */
    private JsonNode pollSeckillResult(String requestId, int timeoutSeconds, String token) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            MvcResult resp = postJson("/seckill/result/" + requestId, null, token, uniqueIp());
            assertSuccess(resp);
            last = bizData(resp);
            if (last != null && !last.isNull()) {
                int status = last.path("status").asInt(-1);
                if (status == SeckillResultVo.STATUS_SUCCESS || status == SeckillResultVo.STATUS_FAIL) {
                    return last;
                }
            }
            Thread.sleep(300);
        }
        return last == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : last;
    }

    private int queryDbSeckillStock(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT seckill_stock FROM product WHERE id = ?", Integer.class, productId);
    }

    private int queryOrderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
    }

    private String querySeckillSlot(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT seckill_slot FROM `order` WHERE id = ?", String.class, orderId);
    }

    /** 局部使用的 assertNull（避免与基类断言助手命名冲突） */
    private static void assertNull(Object actual, String message) {
        org.junit.jupiter.api.Assertions.assertNull(actual, message);
    }
}
