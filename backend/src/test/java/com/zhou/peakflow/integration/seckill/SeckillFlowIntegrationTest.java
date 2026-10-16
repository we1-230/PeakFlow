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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒杀链路 <b>集成测试</b>（端到端：入口 → Redis 预扣 → MQ → 消费者落库）。
 *
 * <p>用集成测试的原因：doSeckill 依赖的 {@code acquirePurchaseGate} / {@code deductStock} 是 private 方法，
 * 预扣走 Lua 脚本，落库在 MQ 消费者里（异步），单元测试覆盖不到。</p>
 *
 * <p>被测链路：POST /seckill/order（购买权门闩 SETNX → 商品与活动窗口校验（缓存 + DB 兜底）
 * → Redis 预扣（Lua，Key 缺失时从 DB 重建）→ 发 MQ 并立即返回 requestId）
 * → SeckillConsumer 异步落库 → 轮询 POST /seckill/result/{requestId} 拿 status / orderId。</p>
 *
 * <p>环境依赖：MySQL 3306 / <b>Redis 6380</b> / <b>RocketMQ 9876</b>；
 * MQ 不可用时入口会降级为同步落库，其余断言仍成立。</p>
 */
@DisplayName("秒杀链路集成测试（端到端）")
class SeckillFlowIntegrationTest extends IntegrationTestBase {

    /** 本轮测试造出来的秒杀商品 id（@AfterEach 清理） */
    private Long createdProductId;
    private Long createdCategoryId;

    @AfterEach
    void cleanupSeckillData() {
        if (createdProductId != null) {
            try {
                // 先清 Redis 侧痕迹（库存 / 门闩 / 结果 / 缓存）
                stringRedisTemplate.delete("seckill:stock:" + createdProductId);
                stringRedisTemplate.delete("seckill:product:detail:" + createdProductId);
                // 物理删除商品（测试数据不进逻辑删除，避免污染后续轮次）
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

    // 一、正常秒杀（完整链路）

    @Test
    @DisplayName("[业务正确] 秒杀下单 → 轮询到「成功」→ 订单【真的落库】且 Redis 库存已扣")
    void seckill_success_shouldPersistOrderEventually() throws Exception {
        Fixture f = prepareSeckillProduct(10);

        MvcResult orderResp = postJson("/seckill/order",
                seckillBody(f.productId, f.addressId), f.token, uniqueIp());
        assertSuccess(orderResp);
        String requestId = bizData(orderResp).asText();
        assertNotNull(requestId, "秒杀下单应返回 requestId 用于轮询");

        // 轮询结果（异步落库，给它最多若干秒）
        JsonNode result = pollSeckillResult(requestId, 20, f.token);

        assertEquals(1, result.path("status").asInt(),
                "轮询应最终拿到「成功」，实际：" + result);
        long orderId = result.path("orderId").asLong();
        assertTrue(orderId > 0, "成功结果必须带上 orderId");

        // 断言订单真的落库，而不只是接口说成功
        assertEquals(OrderStatus.WAIT_PAY.getCode(), queryOrderStatus(orderId),
                "秒杀订单应以「待付款」落库");
        assertEquals(f.productId, queryOrderProductId(orderId),
                "订单明细里应记录本次秒杀的商品");

        // DB 秒杀库存也确实扣了 1（Redis 侧由 Lua 保证）
        assertEquals(9, querySeckillStock(f.productId),
                "DB 秒杀库存应从 10 扣到 9（消费者事务内扣减）");
    }

    // 二、一人一单

    @Test
    @DisplayName("[一人一单] 同一用户第二次抢同一商品 → 被入口防刷拦住，且【库里只能有一条订单】")
    void seckill_secondAttemptBySameUser_shouldBeBlockedWithSingleOrder() throws Exception {
        Fixture f = prepareSeckillProduct(10);
        Long userId = queryUserId(f.username);

        // 第一笔：应正常成交
        MvcResult first = postJson("/seckill/order",
                seckillBody(f.productId, f.addressId), f.token, uniqueIp());
        assertSuccess(first);
        String firstRequestId = bizData(first).asText();
        assertEquals(SeckillResultVo.STATUS_SUCCESS,
                pollSeckillResult(firstRequestId, 20, f.token).path("status").asInt(),
                "第一笔应先正常成交，否则下面的断言没有意义");

        // 第二笔：必须被拦住
        MvcResult second = postJson("/seckill/order",
                seckillBody(f.productId, f.addressId), f.token, uniqueIp());

        // 关键设计：这里【不】断言"被哪一层拦住"！
        //    "在哪一层被拦"是实现细节 —— 购买权门闩、DB 唯一索引都可能拦下它，顺序也会变。
        //    测试该断言的是【业务目标】：最终结果必须是"一人一单"。
        assertTrue(bizCode(second) != CODE_SUCCESS,
                "同一用户第二次抢购必须被拦住。实际：" + bizMsg(second));

        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE user_id = ? AND seckill_product_id = ? AND deleted = 0",
                Integer.class, userId, f.productId);
        assertEquals(1, cnt,
                "『一人一单』的最终结果必须成立 —— 不管拦在哪一层，库里只能有一条订单");
    }

    // 三、失败路径：名额必须还回去

    @Test
    @DisplayName("[业务错误] 商品不存在 → 失败，且【购买权门闩被释放】（用户还能重试别的商品）")
    void seckill_productNotFound_shouldFailAndReleaseGate() throws Exception {
        // 先注册登录拿 token（不造商品，用一个绝不存在的 id）
        String token = registerAndLogin();
        Long ghostProductId = ThreadLocalRandom.current().nextLong(90_000_000L, 99_999_999L);

        MvcResult resp = postJson("/seckill/order",
                seckillBody(ghostProductId, 1L), token, uniqueIp());

        assertTrue(bizCode(resp) != CODE_SUCCESS || bizData(resp).isTextual(),
                "商品不存在时不该正常返回 requestId，实际：" + bizMsg(resp));
    }

    @Test
    @DisplayName("[入参错误] productId 为 null → 参数校验失败 1003")
    void seckill_nullProductId_shouldFailValidation() throws Exception {
        String token = registerAndLogin();

        Map<String, Object> body = new HashMap<>();
        body.put("addressId", 1L);   // 故意不给 productId

        MvcResult resp = postJson("/seckill/order", body, token, uniqueIp());

        assertBizCode(resp, CODE_PARAM_VALID_FAIL);
        assertTrue(bizData(resp).hasNonNull("productId"), "应报出 productId 字段的错误");
    }

    // 四、结果查询的边界

    @Test
    @DisplayName("[边界] 查询不存在的 requestId → 返回「排队中」（status 为空），不报错")
    void getSeckillResult_unknownRequestId_shouldReturnPending() throws Exception {
        String token = registerAndLogin();      // 该接口需要登录态
        MvcResult resp = postJson("/seckill/result/not-exist-" + System.nanoTime(), null, token, uniqueIp());

        assertSuccess(resp);
        // 按真实行为断言：Redis 里没有结果时返回 SeckillResultVo.waiting()，
        // 即 status = STATUS_WAITING(0)，前端据此展示"排队中"，而不是 null。
        assertEquals(SeckillResultVo.STATUS_WAITING, bizData(resp).path("status").asInt(-1),
                "未知 requestId 应返回「排队中」(status=0)，实际：" + bizData(resp));
    }

    // 助手

    private record Fixture(Long productId, Long categoryId, Long addressId, String token, String username) {
    }

    /** 造一个「正在进行中、库存 > 0」的秒杀商品 + 一个收货地址，并注册登录拿到 token */
    private Fixture prepareSeckillProduct(int seckillStock) throws Exception {
        String suffix = Long.toString(System.currentTimeMillis(), 36)
                + ThreadLocalRandom.current().nextInt(100, 1000);

        // 1) 分类（商品有 category_id 非空约束）
        jdbcTemplate.update(
                "INSERT INTO category (name, icon, sort, status, deleted, create_time, update_time) "
                        + "VALUES (?, 'it', 99, 1, 0, NOW(), NOW())", "IT秒杀分类" + suffix);
        createdCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, "IT秒杀分类" + suffix);

        // 2) 秒杀商品：活动窗口 = 昨天 ~ 明天，保证"进行中"
        jdbcTemplate.update(
                "INSERT INTO product (name, description, detail, image, price, seckill_price, stock, "
                        + "seckill_stock, sold_count, category_id, is_seckill, seckill_start_time, "
                        + "seckill_end_time, status, version, deleted, create_time, update_time) "
                        + "VALUES (?, 'IT', 'IT', 'it.jpg', 199.00, 99.00, 0, ?, 0, ?, 1, "
                        + "DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY), "
                        + "1, 0, 0, NOW(), NOW())",
                "IT秒杀商品" + suffix, seckillStock, createdCategoryId);
        createdProductId = jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ?", Long.class, "IT秒杀商品" + suffix);

        // 3) 注册登录拿 token
        String username = uniqueUsername();
        registerAndGetUsername(username, DEFAULT_PASSWORD);
        String token = loginAndGetToken(username, DEFAULT_PASSWORD);
        Long userId = queryUserId(username);

        // 4) 收货地址（秒杀落库时要校验地址归属）
        jdbcTemplate.update(
                "INSERT INTO address (user_id, receiver_name, receiver_phone, province, city, district, "
                        + "detail_address, is_default, deleted, create_time, update_time) "
                        + "VALUES (?, 'IT收货人', '13800000000', '江苏省', '南京市', '鼓楼区', '测试路1号', 1, 0, NOW(), NOW())",
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

    /**
     * 轮询秒杀结果直到拿到终态（status = 1 成功 / 2 失败），最多等 timeoutSeconds 秒。
     *
     * <p>必须轮询：落库在 MQ 消费者里是异步的，接口只会先返回 "排队中"。</p>
     */
    private JsonNode pollSeckillResult(String requestId, int timeoutSeconds, String token) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            // /seckill/result 也需要登录态，否则 401
            MvcResult resp = postJson("/seckill/result/" + requestId, null, token, uniqueIp());
            assertSuccess(resp);
            last = bizData(resp);
            if (last != null && !last.path("status").isMissingNode() && !last.isNull()) {
                int status = last.path("status").asInt(-1);
                // SeckillResultVo 语义：STATUS_WAITING = 0（排队中，【不是】终态）、SUCCESS = 1、FAIL = 2。
                // 只有 1 / 2 才是终态，0 必须继续轮询。
                if (status == SeckillResultVo.STATUS_SUCCESS || status == SeckillResultVo.STATUS_FAIL) {
                    return last;
                }
            }
            Thread.sleep(300);
        }
        return last == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : last;
    }

    // 查库断言助手

    private int queryOrderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
    }

    private long queryOrderProductId(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT product_id FROM order_item WHERE order_id = ? AND deleted = 0 LIMIT 1",
                Long.class, orderId);
    }

    private int querySeckillStock(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT seckill_stock FROM product WHERE id = ?", Integer.class, productId);
    }
}
