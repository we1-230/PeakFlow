package com.zhou.peakflow.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试公共基类：HTTP 层端到端测试（SpringBootTest + MockMvc），与 service 层 Mockito 单测目录分离。
 *
 * <p>环境依赖（test profile，application-test.yml）：</p>
 * <ul>
 *   <li>MySQL  ：127.0.0.1:3307 / peakflow_test（root/1234，Docker 容器 peakflow-mysql）</li>
 *   <li>Redis  ：127.0.0.1:6380 / db1（Docker 容器 peakflow-redis）</li>
 *   <li>RocketMQ：127.0.0.1:9876（Docker 容器 peakflow-rmq-namesrv + broker）</li>
 * </ul>
 * <p>一键拉起依赖：{@code docker compose up -d mysql redis namesrv broker}</p>
 *
 * <p>核心约定：</p>
 * <ul>
 *   <li>每个测试方法使用唯一用户名（it 前缀）+ 唯一伪造 IP（X-Forwarded-For），
 *       隔离按账号/来源的 Redis 状态（如登录失败计数），避免用例间互相污染；</li>
 *   <li>响应统一为 Result{code,message,data,timestamp}，业务码见断言助手；</li>
 *   <li>Spring Security 对“无 token 访问受保护接口”返回 HTTP 403 空 body，
 *       对“非法 token”由 JwtAuthenticationFilter 写 JSON（HTTP 200 + code=401）；</li>
 *   <li>@AfterEach 自动清理本测试创建的用户（DB 三表 + Redis 四类 key）。</li>
 * </ul>
 *
 * <p>RBAC 自举：注册用户 role_id=1（普通用户，无 user:manage 权限）；
 * admin 接口测试通过 grantAdmin() 直接写 user_role(role_id=2) 后重新登录获得管理员 token，
 * 不依赖任何预置账号。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // 注入
    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    /** 与 RedisUtils 同款（验证码 captcha:{uuid} 走 String 序列化） */
    @Autowired
    protected StringRedisTemplate stringRedisTemplate;
    /** 与业务代码同款（login:user / login:fail / token:blacklist 等 Jackson 序列化 key） */
    @Autowired
    protected RedisTemplate<String, Object> redisTemplate;

    // 常量
    /** 测试默认密码：满足 PASSWORD 正则（2-20 位字母数字） */
    protected static final String DEFAULT_PASSWORD = "It123456";
    /** 改密/重置后的新密码 */
    protected static final String NEW_PASSWORD = "New888888";

    protected static final int CODE_SUCCESS = 200;
    protected static final int CODE_PARAM_VALID_FAIL = 1003;
    protected static final int CODE_PARAM_MISSING = 1001;
    protected static final int CODE_PARAM_FORMAT = 1002;
    protected static final int CODE_UNAUTHORIZED = 401;
    protected static final int CODE_FORBIDDEN = 403;
    protected static final int CODE_METHOD_NOT_ALLOWED = 405;
    protected static final int CODE_PASSWORD_ERROR = 2002;
    protected static final int CODE_LOGOUT_FAIL = 2008;
    /**
     * 业务异常默认码：{@code new BusinessException(String)} 使用 ResultCode.BUSINESS_ERROR(3005)
     * <p>历史说明：改造前业务异常默认复用系统错误码 5000，导致"业务错误"与"系统错误"无法区分，现已细分。</p>
     */
    protected static final int CODE_BUSINESS_ERROR = 3005;
    /** 原生系统错误码（未预期异常兜底，ResultCode.SYSTEM_ERROR） */
    protected static final int CODE_SYSTEM_ERROR = 5000;
    /**
     * 通用业务错误码 3005：Controller 走 {@code Result.error(String message)} 返回该码。
     * <p>历史缺陷：改造前该重载硬编码复用 SECKILL_FAIL=3002，现统一收敛为通用业务码。</p>
     */
    protected static final int CODE_RATE_LIMIT = 3005;

    /** Redis key 前缀（与 RedisKeyConstant 对齐；LOGIN_LOCK 拼接时无冒号，是历史缺陷） */
    protected static final String KEY_LOGIN_USER = "login:user:";
    /**
     * 注意这里是<b>无尾冒号</b>的：生产代码 {@code RedisKeyConstant.LOGIN_FAIL = "login:fail"}
     * 且用法是 {@code LOGIN_FAIL + account}，拼出来是 {@code login:fail{account}}（历史缺陷）。
     * 若写成 {@code "login:fail:"} 就会与真实 key 不匹配，导致断言恒失败。
     * <p>（对照：{@code LOGIN_USER} 的用法是 {@code LOGIN_USER + ":" + username}，所以它<b>带</b>冒号）</p>
     */
    protected static final String KEY_LOGIN_FAIL = "login:fail";
    protected static final String KEY_LOGIN_LOCK = "login:lock";
    protected static final String KEY_TOKEN_BLACKLIST = "token:blacklist";
    protected static final String KEY_CAPTCHA = "captcha:";

    /** 本测试创建的用户名，@AfterEach 统一清理（子类可直接登记特殊命名的测试数据） */
    protected final List<String> createdUsernames = new ArrayList<>();

    // 数据构造

    /** 生成唯一测试用户名：it 前缀 + 36 进制时间戳 + 3 位随机，满足 USERNAME 正则（1-20 位字母数字下划线） */
    protected String uniqueUsername() {
        String name = "it" + Long.toString(System.currentTimeMillis(), 36)
                + ThreadLocalRandom.current().nextInt(100, 1000);
        createdUsernames.add(name);
        return name;
    }

    /** 生成唯一伪造 IP（X-Forwarded-For），避免请求来源重复 */
    protected String uniqueIp() {
        return "10." + ThreadLocalRandom.current().nextInt(160, 250)
                + "." + ThreadLocalRandom.current().nextInt(1, 250)
                + "." + ThreadLocalRandom.current().nextInt(1, 250);
    }

    /** 注册请求体（null 字段不放入 JSON，用于测试缺省字段场景） */
    protected Map<String, Object> registerBody(String username, String password, String email,
                                               String phone, String nickname) {
        Map<String, Object> m = new HashMap<>();
        m.put("username", username);
        m.put("password", password);
        if (email != null) {
            m.put("email", email);
        }
        if (phone != null) {
            m.put("phone", phone);
        }
        if (nickname != null) {
            m.put("nickname", nickname);
        }
        return m;
    }

    /** 登录请求体（验证码字段可为 null） */
    protected Map<String, Object> loginBody(String account, String password,
                                            String captchaUuid, String captchaCode) {
        Map<String, Object> m = new HashMap<>();
        m.put("account", account);
        m.put("password", password);
        if (captchaUuid != null) {
            m.put("captchaUuid", captchaUuid);
        }
        if (captchaCode != null) {
            m.put("captchaCode", captchaCode);
        }
        return m;
    }

    // 请求封装（统一伪造 X-Forwarded-For）

    protected MvcResult postJson(String url, Object body, String token, String ip) throws Exception {
        MockHttpServletRequestBuilder builder = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8.name());
        if (body != null) {
            builder.content(objectMapper.writeValueAsString(body));
        }
        return withCommon(builder, token, ip).andReturn();
    }

    protected MvcResult postJson(String url, Object body) throws Exception {
        return postJson(url, body, null, null);
    }

    protected MvcResult getJson(String url, String token, String ip) throws Exception {
        return withCommon(get(url), token, ip).andReturn();
    }

    /** POST 表单参数（@RequestParam 场景：/user/nickname、/admin/ban 等） */
    protected MvcResult postForm(String url, MultiValueMap<String, String> params,
                                 String token, String ip) throws Exception {
        MockHttpServletRequestBuilder builder = post(url).params(params);
        return withCommon(builder, token, ip).andReturn();
    }

    /** POST multipart 文件上传（/user/avatar） */
    protected MvcResult postMultipartFile(String url, String fileFieldName, String filename,
                                          byte[] content, String contentType,
                                          String token, String ip) throws Exception {
        MockHttpServletRequestBuilder builder = multipart(url)
                .file(new MockMultipartFile(fileFieldName, filename, contentType, content));
        return withCommon(builder, token, ip).andReturn();
    }

    private ResultActions withCommon(MockHttpServletRequestBuilder builder, String token, String ip) throws Exception {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (ip != null) {
            builder.header("X-Forwarded-For", ip);
        }
        return mockMvc.perform(builder);
    }

    // 响应解析与断言

    /** 解析响应 body 为 JsonNode */
    protected JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNotNull(content, "响应体为空");
        return objectMapper.readTree(content);
    }

    /** 业务码 $.code */
    protected int bizCode(MvcResult result) throws Exception {
        return body(result).path("code").asInt(-999);
    }

    /** 业务消息 $.message */
    protected String bizMsg(MvcResult result) throws Exception {
        return body(result).path("message").asText("");
    }

    /** 业务数据 $.data */
    protected JsonNode bizData(MvcResult result) throws Exception {
        return body(result).path("data");
    }

    /** 断言：HTTP 200 且业务码 200（成功） */
    protected void assertSuccess(MvcResult result) throws Exception {
        assertEquals(200, result.getResponse().getStatus(), "HTTP 状态码应为 200");
        assertEquals(CODE_SUCCESS, bizCode(result),
                "业务码应为 200，实际响应：" + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 断言：HTTP 200 且业务码 200（成功），支持自定义失败消息 */
    protected void assertSuccess(MvcResult result, String message) throws Exception {
        assertEquals(200, result.getResponse().getStatus(), message);
        assertEquals(CODE_SUCCESS, bizCode(result),
                message + "，实际响应：" + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 断言：HTTP 200 且业务码为指定值（本项目错误统一 HTTP 200 + 业务码区分） */
    protected void assertBizCode(MvcResult result, int expectedCode) throws Exception {
        assertEquals(200, result.getResponse().getStatus(),
                "HTTP 状态码应为 200，实际响应：" + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(expectedCode, bizCode(result),
                "业务码不符，实际响应：" + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 断言：HTTP 403（Spring Security 拦截无 token 请求，body 为空） */
    protected void assertHttp403(MvcResult result) {
        assertEquals(403, result.getResponse().getStatus(),
                "无 token 访问受保护接口应返回 HTTP 403");
    }

    // 业务链路助手

    /**
     * 注册并返回 token（注册响应自带 token，但注意：注册后 login:user 缓存未写入，
     * validateToken 会判“请重新登录”，所以受保护接口统一用 loginAndGetToken 的 token）
     */
    protected String registerAndGetUsername(String username, String password) throws Exception {
        MvcResult r = postJson("/auth/register",
                registerBody(username, password, username + "@itest.com", null, null), null, uniqueIp());
        assertSuccess(r);
        assertTrue(bizData(r).hasNonNull("token"), "注册响应应包含 token");
        return username;
    }

    /** 注册默认密码用户（自动生成唯一用户名并登记清理） */
    protected String registerNewUser() throws Exception {
        String username = uniqueUsername();
        return registerAndGetUsername(username, DEFAULT_PASSWORD);
    }

    /** 登录成功并返回 token */
    protected String loginAndGetToken(String account, String password) throws Exception {
        MvcResult r = postJson("/auth/login", loginBody(account, password, null, null), null, uniqueIp());
        assertSuccess(r);
        String token = bizData(r).path("token").asText(null);
        assertNotNull(token, "登录响应应包含 token");
        return token;
    }

    /** 注册 + 登录 一条龙 */
    protected String registerAndLogin() throws Exception {
        String username = uniqueUsername();
        registerAndGetUsername(username, DEFAULT_PASSWORD);
        return loginAndGetToken(username, DEFAULT_PASSWORD);
    }

    /**
     * 管理员自举：注册（role_id=1）→ 直接写 user_role(role_id=2) → 重新登录。
     * 不依赖预置账号；role_id=2 在 peakflow_test 库拥有 user:manage 权限。
     */
    protected String createAdminAndLogin() throws Exception {
        String username = uniqueUsername();
        registerAndGetUsername(username, DEFAULT_PASSWORD);
        grantAdmin(username);
        return loginAndGetToken(username, DEFAULT_PASSWORD);
    }

    /** 将用户升级为管理员（role_id=2），并登记清理 */
    protected void grantAdmin(String username) {
        jdbcTemplate.update(
                "INSERT INTO user_role (user_id, role_id) SELECT id, 2 FROM user WHERE username = ? AND deleted = 0",
                username);
    }

    /** 获取图形验证码并读出真值（测试直接读 Redis，绕过图片识别） */
    protected String[] getCaptchaUuidAndCode() throws Exception {
        MvcResult r = getJson("/auth/image", null, uniqueIp());
        assertSuccess(r);
        String uuid = bizData(r).path("captchaUuid").asText(null);
        assertNotNull(uuid, "验证码响应应包含 captchaUuid");
        String code = stringRedisTemplate.opsForValue().get(KEY_CAPTCHA + uuid);
        assertNotNull(code, "Redis 中应存在验证码 captcha:" + uuid);
        return new String[]{uuid, code};
    }

    /** 登录失败 N 次（自动处理“失败≥2 次需验证码”逻辑，注入真验证码） */
    protected void failLoginTimes(String account, int times) throws Exception {
        for (int i = 1; i <= times; i++) {
            if (i >= 3) {
                // 第 3 次起 failCount>=2，服务端强制要求验证码 → 注入真值
                String[] captcha = getCaptchaUuidAndCode();
                MvcResult r = postJson("/auth/login",
                        loginBody(account, "WrongPwd" + i, captcha[0], captcha[1]), null, uniqueIp());
                assertTrue(bizCode(r) != CODE_SUCCESS, "错误密码登录不应成功");
            } else {
                MvcResult r = postJson("/auth/login",
                        loginBody(account, "WrongPwd" + i, null, null), null, uniqueIp());
                assertTrue(bizCode(r) != CODE_SUCCESS, "错误密码登录不应成功");
            }
        }
    }

    /** 查询用户 userId（按用户名） */
    protected Long queryUserId(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE username = ? AND deleted = 0", Long.class, username);
    }

    /** 等待异步任务落库（登录日志 insertLoginLog 是 CompletableFuture 异步写） */
    protected Integer waitForLoginLog(String username, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM login_log WHERE username = ?", Integer.class, username);
            if (count != null && count > 0) {
                return count;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        return 0;
    }

    // 清理

    /** 清理本测试创建的用户：DB 三表 + Redis 三类 key（黑名单 key 带 TTL 自然过期，不主动清） */
    @AfterEach
    void cleanupCreatedUsers() {
        for (String username : createdUsernames) {
            try {
                jdbcTemplate.update(
                        "DELETE ur FROM user_role ur JOIN user u ON ur.user_id = u.id WHERE u.username = ?",
                        username);
                jdbcTemplate.update("DELETE FROM login_log WHERE username = ?", username);
                jdbcTemplate.update("DELETE FROM user WHERE username = ?", username);
                redisTemplate.delete(KEY_LOGIN_USER + username);
                redisTemplate.delete(KEY_LOGIN_FAIL + username);
                redisTemplate.delete(KEY_LOGIN_LOCK + username);
            } catch (Exception ignored) {
                // 清理失败不影响测试结果判定
            }
        }
        createdUsernames.clear();
    }
}
