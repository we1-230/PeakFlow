package com.zhou.peakflow.integration.crud;

import com.zhou.peakflow.integration.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口验收走查 <b>集成测试</b>：权限矩阵 + CRUD 域 + 管理端。
 *
 * <p>前面的集成测试覆盖了资金链路 / 认证 / 秒杀 / 数据库约束，本类补齐 address / cart / category /
 * product / admin / user 资料等 CRUD 与管理端接口。</p>
 *
 * <p>三条主线：① 权限矩阵（4 个角色：role1 无权限、role2 全权限、role3 商品运营缺 user:manage、
 * role4 订单客服缺 category:manage，正好验证"不是管理员就该被拦"）；② 每个写操作后回查 DB 确认落库；
 * ③ 购物车存在 Redis（key = cart + userId），写操作后查 Redis。</p>
 *
 * <p>环境依赖：MySQL 3306 / <b>Redis 6380</b> / RocketMQ 9876（上下文启动需要）。</p>
 */
@DisplayName("权限矩阵与 CRUD 域验收走查")
class CrudAndAdminWalkthroughTest extends IntegrationTestBase {

    private static final String CART_KEY_PREFIX = "cart";          // 无尾冒号（历史不一致）
    private static final int ROLE_USER = 1;
    private static final int ROLE_ADMIN = 2;
    private static final int ROLE_PRODUCT_OPS = 3;                 // 商品运营
    private static final int ROLE_ORDER_SERVICE = 4;               // 订单客服

    /** 本类造出来、需要在结束时清理的 id */
    private final List<Long> createdCategoryIds = new java.util.ArrayList<>();
    private final List<Long> createdProductIds = new java.util.ArrayList<>();
    /** 按名字登记的分类：固定名字会跨轮次残留，导致下一轮报「分类名称已存在」 */
    private final List<String> createdCategoryNames = new java.util.ArrayList<>();
    // 这里不要再声明 createdUsernames：基类已有同名 protected 字段，
    //    子类重复声明会「遮蔽」它 → 基类 @AfterEach 永远清不到本类造的用户（历史数据泄漏）

    // 一、权限矩阵（最小权限原则）—— 本类最有价值的部分

    @Test
    @DisplayName("[权限·未登录] 不带 token 调管理端接口 → 401，且【没入库】")
    void perm_anonymous_shouldBeUnauthorizedAndNotPersist() throws Exception {
        MvcResult resp = postJson("/category/add", categoryBody("匿名不该成功"), null, uniqueIp());

        assertEquals(CODE_UNAUTHORIZED, bizCode(resp), "未登录应返回 401，实际：" + bizMsg(resp));
        // 路径断言：被拦在入口，不能有任何写操作
        assertEquals(0, countCategoryNamed("匿名不该成功"), "被 401 拦下时不应有任何数据入库");
    }

    @Test
    @DisplayName("[权限·最小权限] 普通用户(user) 无 category:manage → 403；管理员(admin) → 200 且入库")
    void perm_normalUserDenied_adminAllowed_shouldPersist() throws Exception {
        // ① 普通用户（role 1，【无任何权限】）
        String userToken = tokenOfRole(ROLE_USER);
        String deniedName = uniqueCategoryName("普通用户不该建");
        MvcResult denied = postJson("/category/add", categoryBody(deniedName), userToken, uniqueIp());
        assertEquals(CODE_FORBIDDEN, bizCode(denied),
                "普通用户没有 category:manage，应 403。实际：" + bizMsg(denied));
        assertEquals(0, countCategoryNamed(deniedName), "被 403 拦下时不应有数据入库");

        // ② 管理员（role 2，全权限）
        String adminToken = tokenOfRole(ROLE_ADMIN);
        String allowedName = uniqueCategoryName("管理员建的");
        MvcResult allowed = postJson("/category/add", categoryBody(allowedName), adminToken, uniqueIp());
        assertSuccess(allowed);
        assertTrue(countCategoryNamed(allowedName) == 1, "管理员成功后应真的有数据入库");
    }

    @Test
    @DisplayName("[权限·角色边界] 商品运营(product_ops) 有商品权但【无 user:manage】→ 调 admin 接口应 403")
    void perm_productOps_cannotAccessAdmin_shouldBeForbidden() throws Exception {
        // 商品运营：有 category:manage / product:manage / seckill:manage / stats:view
        //          但【没有 user:manage】
        String opsToken = tokenOfRole(ROLE_PRODUCT_OPS);

        // ① 允许的：建商品类目（有 category:manage）
        MvcResult ok = postJson("/category/add", categoryBody(uniqueCategoryName("商品运营建的")), opsToken, uniqueIp());
        assertSuccess(ok, "商品运营应有 category:manage");

        // ② 禁止的：封禁用户（要 user:manage）
        String victim = uniqueUsername();
        registerAndGetUsername(victim, DEFAULT_PASSWORD);
        Long victimId = queryUserId(victim);
        MvcResult forbidden = postForm("/admin/ban",
                paramsOf("userId", String.valueOf(victimId)), opsToken, uniqueIp());

        assertEquals(CODE_FORBIDDEN, bizCode(forbidden),
                "商品运营没有 user:manage，不该能封禁用户。实际：" + bizMsg(forbidden));
    }

    @Test
    @DisplayName("[权限·角色边界] 订单客服(order_service) 有退款审核但【无 category:manage】→ 建类目应 403")
    void perm_orderService_cannotManageCategory_shouldBeForbidden() throws Exception {
        String svcToken = tokenOfRole(ROLE_ORDER_SERVICE);

        MvcResult forbidden = postJson("/category/add", categoryBody("客服不该建"), svcToken, uniqueIp());

        assertEquals(CODE_FORBIDDEN, bizCode(forbidden),
                "订单客服没有 category:manage，应 403。实际：" + bizMsg(forbidden));
        assertEquals(0, countCategoryNamed("客服不该建"));
    }

    // 二、分类 CRUD（每步查 DB）

    @Test
    @DisplayName("[CRUD·分类] 增 → 列表(公开) → 改 → 删，每步都回查数据库")
    void category_crud_shouldPersistEveryStep() throws Exception {
        String adminToken = tokenOfRole(ROLE_ADMIN);
        String name = "IT类目" + System.currentTimeMillis();

        // ① 增 → 查 DB
        assertSuccess(postJson("/category/add", categoryBody(name), adminToken, uniqueIp()));
        assertEquals(1, countCategoryNamed(name), "新增后 DB 里应有且只有 1 条");

        // ② 改 → 查 DB
        // /category/update 是 PUT + @RequestBody，且 UpdateCategoryDto 的 icon/sort/status 都是 @NotNull/@NotBlank
        Long id = firstCategoryIdNamed(name);
        createdCategoryIds.add(id);   // 登记清理：/category/delete 是逻辑删除，不登记会残留 deleted=1 的行
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("id", id);
        upd.put("name", name + "-改");
        upd.put("icon", "https://it-test.peakflow.local/icon/" + Math.abs(name.hashCode()) + ".png");
        upd.put("sort", 99);
        upd.put("status", 1);
        assertSuccess(putJsonWithBody("/category/update", upd, adminToken, uniqueIp()));
        assertEquals(1, countCategoryNamed(name + "-改"), "改名后 DB 里应是新名字");
        assertEquals(0, countCategoryNamed(name), "旧名字不应残留");

        // ③ 列表是【公开接口】：不带 token 也能查，并且能查到刚建的
        MvcResult list = getJson("/category/list", null, uniqueIp());
        assertSuccess(list, "分类列表是公开接口（无 @PreAuthorize）");
        assertTrue(bizData(list).toString().contains(name + "-改"),
                "公开列表应能查到刚建的类目");

        // ④ 删 → 查 DB（/category/delete/{id} 是 DELETE，不是 POST）
        MvcResult del = deleteJson("/category/delete/" + id, adminToken, uniqueIp());
        assertSuccess(del);
        assertEquals(0, countCategoryNamed(name + "-改"), "删除后 DB 里不应残留");

        // ⑤【F5 回归守卫】逻辑删除后，「公开列表」必须不再返回它
        //   背景：Category 实体曾漏标 @TableLogic，导致软删的分类仍被 this.list() 查出来、列表照旧显示；
        //   控制器删除后已 evict 缓存（product:category），所以这里直接读到的就是重建后的新列表。
        MvcResult listAfterDel = getJson("/category/list", null, uniqueIp());
        assertSuccess(listAfterDel, "分类列表是公开接口");
        assertFalse(bizData(listAfterDel).toString().contains(name + "-改"),
                "分类被逻辑删除后，公开列表不应再返回它（若 Category 漏 @TableLogic，本断言即会失败）");
    }

    // 三、地址（含原子切换默认地址）

    @Test
    @DisplayName("[CRUD·地址] 增两条 → 设默认 → 【DB 里必须恰好一个 is_default=1】（原子切换）")
    void address_setDefault_shouldBeAtomic() throws Exception {
        String token = registerAndLogin();
        Long userId = currentUserId();

        long id1 = addAddress(token, "地址A");
        long id2 = addAddress(token, "地址B");
        assertEquals(2, countAddressOf(userId), "应新增 2 条地址");

        // 把 id2 设为默认
        MvcResult resp = putJson("/address/default/" + id2, token);
        assertSuccess(resp);

        // 原子性的真正验证：整个表里【恰好一个】is_default = 1
        assertEquals(1, countDefaultAddressOf(userId),
                "切换默认地址后必须恰好只有一个默认（这条 SQL 用 CASE WHEN 一次改完，避免中间态）");
        assertEquals(1, isDefaultOf(userId, id2), "目标地址应成为默认");
        assertEquals(0, isDefaultOf(userId, id1), "旧默认应被取消");

        // 再切回 id1，仍然应恰好一个
        assertSuccess(putJson("/address/default/" + id1, token));
        assertEquals(1, countDefaultAddressOf(userId), "反复切换后依然恰好一个默认");
        assertEquals(1, isDefaultOf(userId, id1));
    }

    @Test
    @DisplayName("[越权] 改【别人】的默认地址 → 403/业务错误，且我的默认地址不受影响")
    void address_setOthersAddress_shouldBeRejected() throws Exception {
        String tokenA = registerAndLogin();
        Long userA = currentUserId();
        long mineId = addAddress(tokenA, "A的地址");

        // 另造一个用户 B 和他的地址
        String usernameB = uniqueUsername();
        registerAndGetUsername(usernameB, DEFAULT_PASSWORD);
        String tokenB = loginAndGetToken(usernameB, DEFAULT_PASSWORD);
        Long userB = queryUserId(usernameB);
        long bId = addAddress(tokenB, "B的地址");

        // A 尝试把 B 的地址设为自己的默认
        MvcResult resp = putJson("/address/default/" + bId, tokenA);

        assertTrue(bizCode(resp) != CODE_SUCCESS,
                "不能把别人的地址设为自己的默认。实际：" + bizMsg(resp));
        assertEquals(0, countDefaultAddressOf(userA), "A 的默认地址不该被这次越权操作改变");
    }

    // 四、购物车（存在 Redis —— 本类唯一查 Redis 的域）

    @Test
    @DisplayName("[CRUD·购物车] 加购 → 【Redis 应有 cart key】 → 列表 → 改数量 → 删 → 清空")
    void cart_shouldPersistInRedis() throws Exception {
        String token = registerAndLogin();
        Long userId = currentUserId();
        String cartKey = CART_KEY_PREFIX + userId;          // 无尾冒号（历史不一致）

        // 造一个上架商品
        String adminToken = tokenOfRole(ROLE_ADMIN);
        Long categoryId = createCategory(adminToken);
        Long productId = createProduct(adminToken, categoryId);

        // ① 加购
        MvcResult add = postForm("/cart/add",
                paramsOf("productId", String.valueOf(productId), "quantity", "2"), token, uniqueIp());
        assertSuccess(add);
        // Redis 校验：key 必须存在（购物车完全存 Redis，不落 MySQL）
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(cartKey)),
                "加购后 Redis 应存在购物车 key：" + cartKey);

        // ② 列表应能看到刚加的商品
        MvcResult list = getJson("/cart/list", token, uniqueIp());
        assertSuccess(list);
        String body = bizData(list).toString();
        assertTrue(body.contains(String.valueOf(productId)), "购物车列表应包含刚加的商品：" + body);

        // ③ 改数量
        MvcResult upd = postForm("/cart/updateQuantity",
                paramsOf("productId", String.valueOf(productId), "quantity", "5"), token, uniqueIp());
        assertSuccess(upd);
        assertTrue(bizData(getJson("/cart/list", token, uniqueIp())).toString().contains("5"),
                "改数量后列表里的数量应更新为 5");

        // ④ 删单品
        MvcResult del = deleteJson("/cart/delete/" + productId, token, uniqueIp());
        assertSuccess(del);

        // ⑤ 清空（/cart/clear 是 DELETE）
        MvcResult clear = deleteJson("/cart/clear", token, uniqueIp());
        assertSuccess(clear);
        assertFalseOrEmpty(cartKey, "清空后 Redis 的购物车不应再有商品");
    }

    // 五、商品上下架 + 六、管理端封号 + 七、用户资料

    @Test
    @DisplayName("[CRUD·商品] 新增 → 【DB 校验】→ 下架 → DB 校验 status → 上下架 → 删除")
    void product_lifecycle_shouldPersistEveryStep() throws Exception {
        String adminToken = tokenOfRole(ROLE_ADMIN);
        Long categoryId = createCategory(adminToken);
        Long productId = createProduct(adminToken, categoryId);

        // ① 上架状态
        assertEquals(1, queryInt("SELECT status FROM product WHERE id = ?", productId),
                "新建商品应默认上架（status=1）");

        // ② 下架 → 查 DB（ProductShelfStatus：0=下架，1=上架，没有 2）
        MvcResult off = putJson("/product/status/" + productId + "?status=0", adminToken);
        assertSuccess(off);
        assertEquals(0, queryInt("SELECT status FROM product WHERE id = ?", productId),
                "下架后 DB 里 status 应为 0");

        // ③ 再上架
        assertSuccess(putJson("/product/status/" + productId + "?status=1", adminToken));
        assertEquals(1, queryInt("SELECT status FROM product WHERE id = ?", productId));

        // ④ 删除：必须先「下架」——业务规则与 SQL 双重限制（deleteProductWithVersion 带 status = 0），
        //    而且是【逻辑删除】（deleted = 1），所以断言 deleted 标记，不能断言物理行数为 0
        assertSuccess(putJson("/product/status/" + productId + "?status=0", adminToken));
        assertSuccess(deleteJson("/product/delete/" + productId, adminToken, uniqueIp()));
        assertEquals(1, queryInt("SELECT deleted FROM product WHERE id = ?", productId),
                "删除是逻辑删除：deleted 应被置为 1");
    }

    @Test
    @DisplayName("[管理端] 封号 → 【DB 校验 user.status】→ 被封用户登录应被拒 → 解封 → 登录恢复")
    void admin_banUnban_shouldAffectLoginAndDb() throws Exception {
        String adminToken = tokenOfRole(ROLE_ADMIN);
        String username = uniqueUsername();
        registerAndGetUsername(username, DEFAULT_PASSWORD);
        Long userId = queryUserId(username);

        // ① 封号 → 查 DB
        MvcResult ban = postForm("/admin/ban",
                paramsOf("userId", String.valueOf(userId)), adminToken, uniqueIp());
        assertSuccess(ban);
        assertEquals(0, queryInt("SELECT status FROM user WHERE id = ?", userId),
                "封号后 DB 里 user.status 应变为 0");

        // ② 被封的用户登录应被拒（跨接口：管理端操作 → 认证接口受影响）
        MvcResult login = postJson("/auth/login",
                loginBody(username, DEFAULT_PASSWORD, null, null), null, uniqueIp());
        assertTrue(bizCode(login) != CODE_SUCCESS, "被封禁的账号不该登录成功，实际：" + bizMsg(login));

        // ③ 解封 → 查 DB → 登录恢复
        assertSuccess(postForm("/admin/unban",
                paramsOf("userId", String.valueOf(userId)), adminToken, uniqueIp()));
        assertEquals(1, queryInt("SELECT status FROM user WHERE id = ?", userId));

        assertSuccess(postJson("/auth/login",
                loginBody(username, DEFAULT_PASSWORD, null, null), null, uniqueIp()),
                "解封后应能重新登录");
    }

    @Test
    @DisplayName("[用户资料] 改昵称 → 【DB 校验 nickname】→ 改密码 → 【DB 校验 is_temp_password】")
    void user_updateNicknameAndPassword_shouldPersistToDb() throws Exception {
        String username = uniqueUsername();
        registerAndGetUsername(username, DEFAULT_PASSWORD);
        String token = loginAndGetToken(username, DEFAULT_PASSWORD);
        Long userId = queryUserId(username);

        // ① 改昵称（NICKNAME 正则上限 10 字符，不能直接拼 13 位时间戳）
        String newNick = "昵" + ThreadLocalRandom.current().nextInt(10000000, 99999999);
        MvcResult nick = postForm("/user/nickname",
                paramsOf("nickname", newNick), token, uniqueIp());
        assertSuccess(nick);
        assertEquals(newNick, queryString("SELECT nickname FROM user WHERE id = ?", userId),
                "昵称应真的落到 DB");

        // ② 改密码 → 验证：旧密码不能登、新密码能登、临时密码标记已清
        MvcResult pwd = postJson("/user/password",
                passwordBody(DEFAULT_PASSWORD, NEW_PASSWORD), token, uniqueIp());
        assertSuccess(pwd);
        assertEquals(0, queryInt("SELECT is_temp_password FROM user WHERE id = ?", userId),
                "改完密码后不应再是临时密码");

        assertTrue(bizCode(postJson("/auth/login",
                loginBody(username, DEFAULT_PASSWORD, null, null), null, uniqueIp())) != CODE_SUCCESS,
                "旧密码应已失效");
        assertSuccess(postJson("/auth/login",
                loginBody(username, NEW_PASSWORD, null, null), null, uniqueIp()),
                "新密码应能登录");
    }

    // 助手：造数

    /** 造一个指定角色的账号并返回其 token */
    private String tokenOfRole(int roleId) throws Exception {
        String username = uniqueUsername();
        registerAndGetUsername(username, DEFAULT_PASSWORD);
        if (roleId != ROLE_USER) {
            // 基类的 grantAdmin 写死 role_id=2，这里按需指定角色
            jdbcTemplate.update(
                    "INSERT INTO user_role (user_id, role_id) SELECT id, ? FROM user WHERE username = ? AND deleted = 0",
                    roleId, username);
        }
        createdUsernames.add(username);
        return loginAndGetToken(username, DEFAULT_PASSWORD);
    }

    /** 取当前 token 对应的 userId（借助一个"当前用户信息"接口拿不到，这里用 SQL 反查最新注册） */
    private Long currentUserId() {
        // registerAndLogin 内部注册的用户名不可得，改用"最近一个 it 开头的未删除用户"
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE username LIKE 'it%' AND deleted = 0 ORDER BY id DESC LIMIT 1", Long.class);
        assertNotNull(id, "应能反查到刚注册的用户");
        return id;
    }

    private Map<String, Object> categoryBody(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        // icon 需满足 @Pattern(URL)：^(http|https)://.*$；status 是 @NotNull，都不能省
        m.put("icon", "https://it-test.peakflow.local/icon/" + Math.abs(name.hashCode()) + ".png");
        m.put("sort", 99);
        m.put("status", 1);
        return m;
    }

    private Map<String, Object> passwordBody(String oldPwd, String newPwd) {
        Map<String, Object> m = new HashMap<>();
        m.put("oldPassword", oldPwd);
        m.put("newPassword", newPwd);
        return m;
    }

    private MultiValueMapLike paramsOf(String... kv) {
        MultiValueMapLike m = new MultiValueMapLike();
        for (int i = 0; i < kv.length; i += 2) {
            m.add(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 极简 MultiValueMap 替身（基类 postForm 需要 MultiValueMap<String,String>） */
    static class MultiValueMapLike extends org.springframework.util.LinkedMultiValueMap<String, String> {
    }

    private Long createCategory(String adminToken) throws Exception {
        String name = "IT类目" + ThreadLocalRandom.current().nextInt(100000, 999999);
        postJson("/category/add", categoryBody(name), adminToken, uniqueIp());
        Long id = firstCategoryIdNamed(name);
        createdCategoryIds.add(id);
        return id;
    }

    private Long createProduct(String adminToken, Long categoryId) throws Exception {
        String name = "IT商品" + ThreadLocalRandom.current().nextInt(100000, 999999);
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        // image 要满足 @Pattern(URL)：^(http|https)://.*$，随便写文件名会被 1003 拦掉
        body.put("image", "https://it-test.peakflow.local/product/" + Math.abs(name.hashCode()) + ".jpg");
        body.put("price", 99.00);
        body.put("stock", 50);
        body.put("categoryId", categoryId);
        assertSuccess(postJson("/product/add", body, adminToken, uniqueIp()), "商品新增应成功");
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ?", Long.class, name);
        createdProductIds.add(id);
        return id;
    }

    private long addAddress(String token, String receiver) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("receiverName", receiver);
        body.put("receiverPhone", "13800000000");
        body.put("province", "江苏省");
        body.put("city", "南京市");
        body.put("district", "鼓楼区");
        body.put("detailAddress", receiver + "路1号");
        postJson("/address/add", body, token, uniqueIp());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM address WHERE receiver_name = ? ORDER BY id DESC LIMIT 1", Long.class, receiver);
    }

    // 助手：查库

    private int countCategoryNamed(String name) {
        return countRows("SELECT COUNT(*) FROM category WHERE name = ? AND deleted = 0", name);
    }

    private Long firstCategoryIdNamed(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
                Long.class, name);
    }

    private int countAddressOf(long userId) {
        return countRows("SELECT COUNT(*) FROM address WHERE user_id = ? AND deleted = 0", userId);
    }

    private int countDefaultAddressOf(long userId) {
        return countRows(
                "SELECT COUNT(*) FROM address WHERE user_id = ? AND deleted = 0 AND is_default = 1", userId);
    }

    private int isDefaultOf(long userId, long addressId) {
        return countRows(
                "SELECT COUNT(*) FROM address WHERE id = ? AND user_id = ? AND is_default = 1 AND deleted = 0",
                addressId, userId);
    }

    private int countRows(String sql, Object... args) {
        Integer v = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return v == null ? 0 : v;
    }

    private int queryInt(String sql, Object... args) {
        return countRows(sql, args);
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private void assertFalseOrEmpty(String cartKey, String message) {
        // 购物车存在 Redis，可能是 Hash（删完 key 仍在但字段为空），所以两种都算通过
        Boolean exists = stringRedisTemplate.hasKey(cartKey);
        Object raw = exists != null && exists
                ? stringRedisTemplate.opsForValue().get(cartKey)
                : null;
        assertTrue(raw == null || raw.toString().isEmpty() || raw.toString().equals("{}")
                || raw.toString().equals("[]"), message + "（实际：" + raw + "）");
    }

    /** 便捷 PUT（基类只封装了 POST/GET，这里补一个带 token 的 PUT） */
    private MvcResult putJson(String url, String token) throws Exception {
        MockHttpServletRequestBuilder req = put(url);
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(req).andReturn();
    }

    /** PUT + JSON body（/category/update 这类 @RequestBody 的 PUT 接口） */
    private MvcResult putJsonWithBody(String url, Object body, String token, String ip) throws Exception {
        MockHttpServletRequestBuilder req = put(url)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8.name())
                .content(objectMapper.writeValueAsString(body));
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        if (ip != null) {
            req = req.header("X-Forwarded-For", ip);
        }
        return mockMvc.perform(req).andReturn();
    }

    /** DELETE（/category/delete/{id} 这类接口） */
    private MvcResult deleteJson(String url, String token, String ip) throws Exception {
        MockHttpServletRequestBuilder req = delete(url);
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        if (ip != null) {
            req = req.header("X-Forwarded-For", ip);
        }
        return mockMvc.perform(req).andReturn();
    }

    // 助手：测试数据清理

    /**
     * 清理本类造出来的商品 / 分类。
     *
     * <p>本类此前<b>完全没有 @AfterEach</b>，而权限用例用的是固定分类名（"管理员建的"等），
     * 第一轮建进去、第二轮再跑就报「商品分类名称已存在」——
     * 这不是业务缺陷，是测试数据没擦干净导致的跨轮次污染。</p>
     */
    @AfterEach
    void cleanupCrudData() {
        for (Long productId : createdProductIds) {
            quietly(() -> jdbcTemplate.update("DELETE FROM product WHERE id = ?", productId));
        }
        for (Long categoryId : createdCategoryIds) {
            quietly(() -> jdbcTemplate.update("DELETE FROM category WHERE id = ?", categoryId));
        }
        for (String name : createdCategoryNames) {
            quietly(() -> jdbcTemplate.update("DELETE FROM category WHERE name = ?", name));
        }
        createdProductIds.clear();
        createdCategoryIds.clear();
        createdCategoryNames.clear();
    }

    /** 造唯一分类名并登记清理（固定名字会跨轮次残留） */
    private String uniqueCategoryName(String prefix) {
        String name = prefix + ThreadLocalRandom.current().nextInt(100000, 999999);
        createdCategoryNames.add(name);
        return name;
    }

    private void quietly(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 清理失败不影响用例结果判定
        }
    }
}
