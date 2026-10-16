package com.zhou.peakflow.integration.auth;

import com.zhou.peakflow.integration.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证链路 <b>集成测试</b>（注册 → 登录 → 鉴权）。
 *
 * <p>用集成测试的原因：失败计数/账号锁定依赖真实 Redis 的原子自增与 TTL；验证码"用后即删"
 * 需要真实 Redis 观察 key；密码是否真的 BCrypt 加密要看库里存的到底是什么；注册重名依赖真实
 * 唯一索引；Spring Security + JWT 鉴权链是框架行为，只能在真实上下文里验。</p>
 *
 * <p>环境依赖：MySQL 3306 / <b>Redis 6380</b>（失败计数、验证码、锁定）/ RocketMQ 9876（上下文启动需要）。</p>
 */
@DisplayName("认证链路集成测试（注册→登录→鉴权）")
class AuthFlowIntegrationTest extends IntegrationTestBase {

    // 一、完整链路：注册 → 登录 → 访问受保护接口

    @Test
    @DisplayName("[业务正确] 注册 → 登录拿 token → 用 token 访问受保护接口 /user/info 成功")
    void authFlow_registerLoginThenAccessProtected_shouldSucceed() throws Exception {
        String token = registerAndLogin();
        assertNotNull(token, "登录应返回 token");

        MvcResult resp = getJson("/user/info", token, uniqueIp());

        assertSuccess(resp);
        assertTrue(bizData(resp).path("id").asLong() > 0, "应返回当前用户信息");
    }

    @Test
    @DisplayName("[安全边界] 不带 token 访问受保护接口 → 业务码 401（Security 链生效）")
    void accessProtectedWithoutToken_shouldBeUnauthorized() throws Exception {
        MvcResult resp = getJson("/user/info", null, uniqueIp());

        // 实际口径是「HTTP 200 + 业务码 401」（统一由 JwtAuthenticationFilter / 异常处理器写 JSON），
        //    而不是 HTTP 403 —— 这条断言按【真实行为】写。
        assertEquals(CODE_UNAUTHORIZED, bizCode(resp),
                "无 token 访问受保护接口应返回业务码 401，实际：" + bizMsg(resp));
    }

    // 二、密码存储（真实 BCrypt）

    @Test
    @DisplayName("[安全] 注册后 DB 里存的是 BCrypt 密文（$2a$/$2b$ 前缀），【绝不是明文】")
    void register_passwordShouldBeStoredAsBcryptHash() throws Exception {
        String username = registerNewUser();

        String stored = jdbcTemplate.queryForObject(
                "SELECT password FROM user WHERE username = ?", String.class, username);

        assertNotNull(stored);
        assertNotEquals(DEFAULT_PASSWORD, stored, "绝不能明文存密码");
        assertTrue(stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$"),
                "BCrypt 密文应以 $2a$/$2b$/$2y$ 开头，实际：" + stored);
        assertTrue(stored.length() >= 59, "BCrypt 密文长度应 >= 59，实际：" + stored.length());
    }

    @Test
    @DisplayName("[业务错误] 用已存在的用户名注册 → 报「用户名已存在」（真实唯一索引/查重）")
    void register_duplicateUsername_shouldFail() throws Exception {
        String username = registerNewUser();   // 已占用

        MvcResult resp = postJson("/auth/register",
                registerBody(username, DEFAULT_PASSWORD, username + "-dup@itest.com", null, null),
                null, uniqueIp());

        assertTrue(bizCode(resp) != CODE_SUCCESS, "重复用户名不该注册成功");
        assertTrue(bizMsg(resp).contains("用户名已存在"), "实际：" + bizMsg(resp));
    }

    // 三、失败计数与账号锁定（真实 Redis 行为）

    @Test
    @DisplayName("[防爆破] 连续失败 2 次后 → 第 3 次【强制图形验证码】（真实 Redis 计数）")
    void login_afterTwoFailures_shouldRequireCaptcha() throws Exception {
        String username = registerNewUser();
        failLoginTimes(username, 2);      // 基类助手：会正确处理"失败>=2 需验证码"的推进

        // 第 3 次：不带验证码 ⇒ 应被"请先输入验证码"挡住
        MvcResult resp = postJson("/auth/login",
                loginBody(username, "WrongPwd3", null, null), null, uniqueIp());

        assertBizCode(resp, CODE_BUSINESS_ERROR);
        assertEquals("请输入图形验证码", bizMsg(resp),
                "失败 >= 2 次后必须强制验证码，不能只用密码硬试");
    }

    @Test
    @DisplayName("[防爆破] 连续失败 5 次 → 账号被锁定，且 Redis 里留下带 TTL 的锁定 key")
    void login_fiveFailures_shouldLockAccountInRedis() throws Exception {
        String username = registerNewUser();
        failLoginTimes(username, 4);      // 已到 4 次

        // 第 5 次失败：注入真实验证码（失败>=2 强制验证码）
        String[] captcha = getCaptchaUuidAndCode();
        MvcResult fifth = postJson("/auth/login",
                loginBody(username, "WrongPwd5", captcha[0], captcha[1]), null, uniqueIp());

        assertEquals("密码错误次数过多，账号已被锁定", bizMsg(fifth));

        // 真实 Redis：锁定 key 必须存在，且 TTL 不超过 15 分钟（到期自动解锁）
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(KEY_LOGIN_LOCK + username)),
                "锁定后 Redis 应存在 " + KEY_LOGIN_LOCK + username);
        Long ttlMinutes = redisTemplate.getExpire(KEY_LOGIN_LOCK + username, TimeUnit.MINUTES);
        assertTrue(ttlMinutes != null && ttlMinutes > 0 && ttlMinutes <= 15,
                "锁定 TTL 应为正且不超过 15 分钟，实际：" + ttlMinutes);

        // 锁定期内即使密码正确也进不去
        MvcResult withCorrectPwd = postJson("/auth/login",
                loginBody(username, DEFAULT_PASSWORD, null, null), null, uniqueIp());
        assertTrue(bizMsg(withCorrectPwd).contains("账号已被锁定"),
                "锁定期间即使密码正确也应被拒，实际：" + bizMsg(withCorrectPwd));
    }

    @Test
    @DisplayName("[抗复用] 验证码【用后即删】—— 无论校验对错，Redis 里的 key 都应消失")
    void captcha_shouldBeDeletedAfterUse() throws Exception {
        String username = registerNewUser();
        failLoginTimes(username, 2);

        String[] captcha = getCaptchaUuidAndCode();
        assertNotNull(stringRedisTemplate.opsForValue().get(KEY_CAPTCHA + captcha[0]),
                "取验证码时 Redis 里应该有值");

        // 故意用【错误】验证码走一次（服务端会先取出再比对，然后删除）
        postJson("/auth/login",
                loginBody(username, "WrongPwd99", captcha[0], "XXXX"), null, uniqueIp());

        // 关键断言：验证码必须已被删除，否则同一个验证码可以被反复尝试
        assertNull(stringRedisTemplate.opsForValue().get(KEY_CAPTCHA + captcha[0]),
                "验证码用后必须立即删除（无论校验是否通过），防止被反复尝试");

        // 且同一个 uuid 再用一次也不该通过
        MvcResult reuse = postJson("/auth/login",
                loginBody(username, "WrongPwd99", captcha[0], captcha[1]), null, uniqueIp());
        assertTrue(bizMsg(reuse).contains("验证码错误"),
                "已删除的验证码不应还能用，实际：" + bizMsg(reuse));
    }

    @Test
    @DisplayName("[业务正确] 登录成功后 → 清除失败计数（否则下次登录还要输验证码）")
    void login_success_shouldClearFailCount() throws Exception {
        String username = registerNewUser();

        // 先失败 1 次制造计数（不会触发验证码门槛）
        MvcResult fail = postJson("/auth/login",
                loginBody(username, "WrongPwd1", null, null), null, uniqueIp());
        assertTrue(bizCode(fail) != CODE_SUCCESS);
        assertNotNull(stringRedisTemplate.opsForValue().get(KEY_LOGIN_FAIL + username),
                "失败 1 次后 Redis 应有失败计数");

        // 正常登录
        loginAndGetToken(username, DEFAULT_PASSWORD);

        // 登录成功必须清掉失败计数
        assertNull(stringRedisTemplate.opsForValue().get(KEY_LOGIN_FAIL + username),
                "登录成功后失败计数必须被清除");
    }

    // 四、越权 / 非法输入的边界

    @Test
    @DisplayName("[越权] 用【伪造的 token】访问受保护接口 → 业务码 401（JwtAuthenticationFilter 写 JSON）")
    void accessWithForgedToken_shouldReturn401() throws Exception {
        MvcResult resp = getJson("/user/info", "forged.jwt.token.value", uniqueIp());

        // 基类约定：非法 token 由 JwtAuthenticationFilter 写 JSON（HTTP 200 + code=401）
        assertEquals(CODE_UNAUTHORIZED, bizCode(resp),
                "伪造 token 应返回业务码 401，实际：" + bizMsg(resp));
    }

    @Test
    @DisplayName("[非法输入] 登录参数缺失（账号为空）→ 参数校验失败 1003，且【不改动失败计数】")
    void login_blankAccount_shouldFailValidationWithoutTouchingFailCount() throws Exception {
        String username = registerNewUser();

        MvcResult resp = postJson("/auth/login",
                loginBody("", DEFAULT_PASSWORD, null, null), null, uniqueIp());

        assertBizCode(resp, CODE_PARAM_VALID_FAIL);
        // 路径断言：连账号都没传，不该去动 Redis 的失败计数
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(KEY_LOGIN_FAIL + username)),
                "参数校验就没过，不该记失败次数");
    }

    // 五、登出：验证旧 token 真正失效（此前是「登出成功但 token 照样能用」的历史缺陷）

    @Test
    @DisplayName("[登出] 登出后旧 token 访问 → 业务码 401；且 login:user:{username} 缓存被删、裸 token 进黑名单")
    void logout_shouldInvalidateOldTokenAndClearLoginCache() throws Exception {
        String username = uniqueUsername();
        registerAndGetUsername(username, DEFAULT_PASSWORD);
        String token = loginAndGetToken(username, DEFAULT_PASSWORD);

        // 前置：登录后缓存确实存在（key 必须是带冒号的 login:user:{username}）
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(KEY_LOGIN_USER + username)),
                "登录后应写入 " + KEY_LOGIN_USER + username + " 缓存");

        // ① 登出
        assertSuccess(postJson("/auth/logout", null, token, uniqueIp()));

        // ② 旧 token 必须失效（非法/失效 token 由 JwtAuthenticationFilter 写 HTTP 200 + code=401）
        MvcResult after = getJson("/user/info", token, uniqueIp());
        assertEquals(CODE_UNAUTHORIZED, bizCode(after),
                "登出后旧 token 必须失效，实际：" + bizMsg(after));

        // ③ 真实副作用一：登录缓存被删除。历史 bug 是 key 少冒号（login:user{username}），
        //    删不掉真 key，导致 validateToken 依旧查得到缓存、继续放行 → 登出形同虚设。
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(KEY_LOGIN_USER + username)),
                "登出应删除 " + KEY_LOGIN_USER + username + " 缓存");

        // ④ 真实副作用二：黑名单 key 必须是「裸 token」（与 parseToken 的检查同一形态），
        //    历史 bug 是写入时带了 "Bearer " 前缀，写 key 与查 key 对不上。
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(KEY_TOKEN_BLACKLIST + token)),
                "登出应把裸 token 写入 " + KEY_TOKEN_BLACKLIST + " 黑名单");
    }
}
