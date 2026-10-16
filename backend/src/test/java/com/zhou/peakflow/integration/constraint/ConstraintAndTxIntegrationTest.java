package com.zhou.peakflow.integration.constraint;

import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.integration.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库约束与乐观锁 <b>集成测试</b>。
 *
 * <p>业务代码的"最后一道防线"在数据库 DDL 里，单元测试里 Mapper 是 mock 的，这些约束不存在，
 * 只有连真实数据库跑才能证明兜底生效：{@code uk_seckill_user_product}（秒杀一人一单，靠生成列
 * {@code seckill_slot} + 唯一索引实现"部分唯一索引"）、{@code uk_open_order}（同一订单只能有一条
 * 未完结退款单）、{@code version} 乐观锁（状态流转的 CAS 闸门）。</p>
 *
 * <p>MySQL 不支持带 WHERE 条件的唯一索引，本项目把条件编码进生成列，而唯一索引不比较含 NULL 的行，
 * 不满足条件的记录自动不参与约束。本类专门用一个用例验证这个"NULL 免约束"行为。</p>
 *
 * <p>环境依赖：MySQL 3306（必需）；Redis 6380 / RocketMQ 9876（@SpringBootTest 启动上下文需要）。</p>
 */
@DisplayName("数据库约束与乐观锁集成测试")
class ConstraintAndTxIntegrationTest extends IntegrationTestBase {

    private Long orderId;
    private Long userId;
    private Long productId;
    private Long categoryId;
    private Long addressId;

    @AfterEach
    void cleanup() {
        // 逆序清理造出来的数据（order_item 有 FK CASCADE，会跟着订单一起删）
        safe(() -> { if (orderId != null) jdbcTemplate.update("DELETE FROM refund_record WHERE order_id = ?", orderId); });
        safe(() -> { if (orderId != null) jdbcTemplate.update("DELETE FROM `order` WHERE id = ?", orderId); });
        safe(() -> { if (addressId != null) jdbcTemplate.update("DELETE FROM address WHERE id = ?", addressId); });
        safe(() -> { if (productId != null) jdbcTemplate.update("DELETE FROM product WHERE id = ?", productId); });
        safe(() -> { if (categoryId != null) jdbcTemplate.update("DELETE FROM category WHERE id = ?", categoryId); });
        orderId = userId = productId = categoryId = addressId = null;
    }

    // 一、uk_seckill_user_product —— 秒杀「一人一单」的数据库兜底

    @Test
    @DisplayName("[约束] 同一用户在同一秒杀商品上插第二条有效订单 → 被 uk_seckill_user_product 拦下")
    void uniqueIndex_seckillOneUserOneOrder_shouldRejectSecondInsert() {
        prepareSeckillFixture();

        // 第一条：正常插入（seckill_slot 由生成列自动算成 "userId:productId"）
        insertSeckillOrder();

        // 第二条：同一个 user + 同一个 seckill_product ⇒ 生成列的值相同 ⇒ 唯一索引冲突
        DuplicateKeyException ex = assertThrows(DuplicateKeyException.class,
                this::insertSeckillOrder,
                "同一用户在同一秒杀商品上只能有一条有效订单 —— 这是入口门闩之外的最后一道防线");

        assertTrue(ex.getMessage().contains("uk_seckill_user_product"),
                "冲突应来自 uk_seckill_user_product，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("[约束·生成列语义] 普通订单 / 已取消的秒杀订单 → seckill_slot 为 NULL（不占名额）")
    void generatedColumn_seckillSlot_shouldBeNullForNonOccupyingRows() {
        prepareSeckillFixture();

        // ① 普通订单（order_type = 0）⇒ 生成列应为 NULL
        insertNormalOrder();
        assertNull(querySeckillSlot(orderId), "普通订单不该占用秒杀名额");

        // ② 秒杀订单但已取消（status = 4）⇒ 也应为 NULL（否则用户取消后就不能再抢了）
        jdbcTemplate.update("UPDATE `order` SET order_type = 1, seckill_product_id = ?, status = ? WHERE id = ?",
                productId, OrderStatus.CANCELLED.getCode(), orderId);
        assertNull(querySeckillSlot(orderId),
                "已取消的秒杀订单必须释放名额（生成列条件里 status NOT IN (4,5) 就是为此）");

        // ③ 秒杀订单 + 待付款 ⇒ 生成列有值（"userId:productId"），此时才占名额
        jdbcTemplate.update("UPDATE `order` SET status = ? WHERE id = ?",
                OrderStatus.WAIT_PAY.getCode(), orderId);
        assertEquals(userId + ":" + productId, querySeckillSlot(orderId),
                "有效的秒杀订单应生成 userId:productId 占位值");
    }

    // 二、uk_open_order —— 同一订单只能有一条未完结退款单

    @Test
    @DisplayName("[约束] 同一订单插第二条【未完结】退款单 → 被 uk_open_order 拦下")
    void uniqueIndex_openOrder_shouldRejectSecondOpenRefund() {
        prepareSeckillFixture();
        insertSeckillOrder();

        // 第一条未完结退款单
        insertRefund("REF-IT-1", RefundStatus.APPLYING.getCode());

        // 第二条未完结（待审核/退款中/退款失败都属于未完结）⇒ 生成列值相同 ⇒ 冲突
        DuplicateKeyException ex = assertThrows(DuplicateKeyException.class,
                () -> insertRefund("REF-IT-2", RefundStatus.REFUNDING.getCode()),
                "同一订单不允许并存两条未完结退款单");

        assertTrue(ex.getMessage().contains("uk_open_order"),
                "冲突应来自 uk_open_order，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("[约束·生成列语义] 终态退款单不占名额 → 同一订单可以有多条【终态】退款单")
    void generatedColumn_openOrder_shouldBeNullForTerminalRefunds() {
        prepareSeckillFixture();
        insertSeckillOrder();

        // 两条终态退款单（撤销 5 / 已驳回 4）应都能插入
        insertRefund("REF-IT-T1", RefundStatus.CANCELLED.getCode());
        insertRefund("REF-IT-T2", RefundStatus.REJECTED.getCode());

        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_record WHERE order_id = ?", Integer.class, orderId);
        assertEquals(2, cnt, "终态退款单不占位，同一订单可以留有多条历史记录（展示退款痕迹）");

        // 且它们的 open_order_id 都是 NULL
        Integer nullCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_record WHERE order_id = ? AND open_order_id IS NULL",
                Integer.class, orderId);
        assertEquals(2, nullCnt, "终态退款单的 open_order_id 必须为 NULL");
    }

    // 三、乐观锁 CAS —— 所有状态流转的并发闸门

    @Test
    @DisplayName("[并发闸门] 乐观锁：用【过期 version】更新 → 影响 0 行；用正确 version → 影响 1 行")
    void optimisticLock_staleVersion_shouldAffectZeroRows() {
        prepareSeckillFixture();
        insertSeckillOrder();
        int currentVersion = queryOrderVersion(orderId);

        // ① 用过期 version（currentVersion - 1）执行"待付款 → 已付款"的 CAS ⇒ 0 行
        int staleRows = jdbcTemplate.update(
                "UPDATE `order` SET status = ?, version = version + 1 "
                        + "WHERE id = ? AND status = ? AND version = ? AND deleted = 0",
                OrderStatus.PAID.getCode(), orderId, OrderStatus.WAIT_PAY.getCode(), currentVersion - 1);
        assertEquals(0, staleRows, "version 不匹配时 CAS 必须影响 0 行（并发被挡住）");
        assertEquals(OrderStatus.WAIT_PAY.getCode(), queryOrderStatus(orderId),
                "CAS 未命中 ⇒ 状态必须保持不变");

        // ② 用正确 version ⇒ 1 行，且 version 自增
        int rows = jdbcTemplate.update(
                "UPDATE `order` SET status = ?, version = version + 1 "
                        + "WHERE id = ? AND status = ? AND version = ? AND deleted = 0",
                OrderStatus.PAID.getCode(), orderId, OrderStatus.WAIT_PAY.getCode(), currentVersion);
        assertEquals(1, rows, "version 匹配时应成功更新");
        assertEquals(OrderStatus.PAID.getCode(), queryOrderStatus(orderId));
        assertEquals(currentVersion + 1, queryOrderVersion(orderId), "version 应自增 1");
    }

    // 助手

    /** 造一套最小数据：分类 / 商品 / 用户 / 地址（用 jdbcTemplate 直插，快且可控） */
    private void prepareSeckillFixture() {
        String suffix = Long.toString(System.currentTimeMillis(), 36)
                + ThreadLocalRandom.current().nextInt(100, 1000);

        jdbcTemplate.update(
                "INSERT INTO category (name, icon, sort, status, deleted, create_time, update_time) "
                        + "VALUES (?, 'it', 99, 1, 0, NOW(), NOW())", "IT约束分类" + suffix);
        categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, "IT约束分类" + suffix);

        jdbcTemplate.update(
                "INSERT INTO product (name, description, detail, image, price, stock, seckill_stock, sold_count, "
                        + "category_id, is_seckill, status, version, deleted, create_time, update_time) "
                        + "VALUES (?, 'IT', 'IT', 'it.jpg', 199.00, 0, 10, 0, ?, 1, 1, 0, 0, NOW(), NOW())",
                "IT约束商品" + suffix, categoryId);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ?", Long.class, "IT约束商品" + suffix);

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

    /**
     * 插一条【秒杀订单】：{@code order_type = 1} 且 {@code seckill_product_id} 有值。
     *
     * <p>只有这种订单才会"占名额"。生成列定义为
     * {@code seckill_slot = IF(order_type = 1 AND status NOT IN (4,5), CONCAT(user_id,':',seckill_product_id), NULL)}，
     * 因此 {@code uk_seckill_user_product} 只对"有效的秒杀订单"生效。</p>
     */
    private void insertSeckillOrder() {
        insertOrder(1, productId);
    }

    /** 插一条【普通订单】：{@code order_type = 0} ⇒ {@code seckill_slot} 为 NULL（不占秒杀名额） */
    private void insertNormalOrder() {
        insertOrder(0, null);
    }

    private void insertOrder(int orderType, Long seckillProductId) {
        jdbcTemplate.update(
                "INSERT INTO `order` (order_no, user_id, address_id, total_amount, pay_amount, status, "
                        + "order_type, seckill_product_id, is_seckill, version, deleted, create_time, update_time) "
                        + "VALUES (?, ?, ?, 199.00, 199.00, ?, ?, ?, 0, 0, 0, NOW(), NOW())",
                "ORDIT-C-" + System.nanoTime(), userId, addressId, OrderStatus.WAIT_PAY.getCode(),
                orderType, seckillProductId);
        orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM `order` WHERE user_id = ? AND address_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, userId, addressId);
    }

    private void insertRefund(String refundNo, int status) {
        jdbcTemplate.update(
                "INSERT INTO refund_record (refund_no, order_id, order_no, user_id, refund_amount, "
                        + "refund_type, refund_channel, status, apply_time, version, create_time, update_time) "
                        + "VALUES (?, ?, 'ORD-IT', ?, 199.00, 1, 'MOCK_PAY', ?, NOW(), 0, NOW(), NOW())",
                refundNo, orderId, userId, status);
    }

    private String querySeckillSlot(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT seckill_slot FROM `order` WHERE id = ?", String.class, id);
    }

    private int queryOrderStatus(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, id);
    }

    private int queryOrderVersion(Long id) {
        return jdbcTemplate.queryForObject("SELECT version FROM `order` WHERE id = ?", Integer.class, id);
    }

    /** 清理时的容错包装：清理失败不影响测试判定 */
    private void safe(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 清理失败不影响测试结果
        }
    }
}
