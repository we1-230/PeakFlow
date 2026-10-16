package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.dto.auth.LoginDto;
import com.zhou.peakflow.dto.auth.LoginResponseDto;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.UserMapper;
import com.zhou.peakflow.pojo.User;
import com.zhou.peakflow.utils.AliOssUtil;
import com.zhou.peakflow.utils.JwtUtil;
import com.zhou.peakflow.utils.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户登录 <b>单元测试</b>：{@code UserServiceImpl#login(loginDto, ip)}（本项目分支最多的方法）。
 *
 * <p>判定链：checkAccountLocked（Redis 有 {@code login:lock{account}} 即在认证前拦下）→ 读失败计数
 * {@code login:fail{account}} → 失败 ≥ 2 次强制图形验证码（取 Redis 真值忽略大小写比对后立即删除）→
 * authenticationManager.authenticate，按异常类型分别处理 → 查 User → 成功收尾（清失败计数、更新
 * last_login、查权限、写登录缓存、生成 token、异步写日志）。</p>
 *
 * <p>三个要点：① recordLoginFail 用 Redis increment 原子自增（不是"读出 +1 再写回"，否则并发丢计数），
 * 第 1 次才设过期、满 5 次写锁定 key；② 验证码用后即删；③"不存在账号"与"密码错误"返回同一提示，防账号枚举。</p>
 *
 * <p>一个反直觉细节（本类专门覆盖）：密码错误分支 catch 里先调 recordLoginFail，而 recordLoginFail 自己
 * 也会抛异常（带剩余次数 / 已锁定），所以那句"用户名或密码错误"实际永远执行不到。本类按真实行为断言。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户登录单元测试 UserServiceImpl#login")
class UserServiceImplLoginTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AliOssUtil aliOssUtil;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private RedisUtils redisUtils;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private Authentication authentication;

    @InjectMocks
    private UserServiceImpl userService;

    // 常量

    private static final String ACCOUNT = "zhangsan";
    private static final String RAW_PASSWORD = "Ab123456";
    private static final String IP = "10.1.2.3";
    private static final String CAPTCHA_UUID = "captcha-uuid-001";
    private static final String CAPTCHA_CODE = "AbCd";
    private static final String TOKEN = "mocked.jwt.token";
    private static final long USER_ID = 1001L;
    /** 与 UserServiceImpl.MAX_FAIL_COUNT 保持一致 */
    private static final int MAX_FAIL_COUNT = 5;

    // 一、锁定检查（认证之前）

    /**
     * <p><b>为什么测它：</b>已锁定的账号在认证之前就拦下，省一次昂贵的 BCrypt 计算。</p>
     * <p><b>关键断言：</b>抛「账号已被锁定」且带剩余分钟数；<b>never 调 authenticate</b>（路径断言）。</p>
     */

    @Test
    @DisplayName("[业务错误] 账号已被锁定 → 抛「账号已被锁定，请N分钟后重试」，且【不做认证】")
    void login_accountLocked_shouldRejectBeforeAuthentication() {
        // checkAccountLocked：lock key 存在 + 剩余 12 分钟
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.MINUTES))).thenReturn(12L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(dtoOf(null, null), IP));

        assertTrue(ex.getMessage().contains("账号已被锁定"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("12"), "提示应带剩余分钟数，实际：" + ex.getMessage());
        // 路径断言：锁定的账号连密码都不该去校验（省一次 BCrypt）
        verify(authenticationManager, never()).authenticate(any());
    }

    // 二、强制图形验证码（失败 ≥ 2 次）

    /**
     * <p><b>为什么测它：</b>防爆破：失败 ≥2 次后强制图形验证码，不给脚本无限重试的机会。</p>
     * <p><b>关键断言：</b>抛「请输入图形验证码」；<b>never</b> 认证。</p>
     */

    @Test
    @DisplayName("[业务错误] 已失败 2 次但未带验证码 → 抛「请输入图形验证码」，不做认证")
    void login_captchaRequiredButMissing_shouldReject() {
        givenNotLocked();
        givenFailCount(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(dtoOf(null, null), IP));

        assertTrue(ex.getMessage().contains("请输入图形验证码"), "实际：" + ex.getMessage());
        verify(authenticationManager, never()).authenticate(any());
    }

    /**
     * <p><b>为什么测它：</b>验证码用后即删：即使校验失败也要删，否则同一个验证码能被反复尝试。</p>
     * <p><b>关键断言：</b>抛「验证码错误」；<b>verify delete 被调用</b>；<b>never</b> 认证。</p>
     */

    @Test
    @DisplayName("[业务错误] 验证码错误 → 抛「验证码错误」，且验证码【用完即删】")
    void login_wrongCaptcha_shouldRejectAndDeleteCaptcha() {
        givenNotLocked();
        givenFailCount(2);
        when(redisUtils.get("captcha:" + CAPTCHA_UUID)).thenReturn("ZZZZ");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(dtoOf(CAPTCHA_UUID, "WRONG"), IP));

        assertTrue(ex.getMessage().contains("验证码错误"), "实际：" + ex.getMessage());
        // 即使校验失败也要删 —— 防止同一个验证码被反复尝试
        verify(redisUtils).delete("captcha:" + CAPTCHA_UUID);
        verify(authenticationManager, never()).authenticate(any());
    }

    /**
     * <p><b>为什么测它：</b>边界：验证码应<b>忽略大小写</b>（用户体验），传小写也要通过。</p>
     * <p><b>关键断言：</b>不抛异常；<b>verify delete 被调用</b>；<b>verify authenticate 被调用</b>（说明确实往下走了）。</p>
     */

    @Test
    @DisplayName("[业务正确] 验证码正确但【大小写不同】→ 忽略大小写，校验通过并继续认证")
    void login_captchaShouldIgnoreCase() throws Exception {
        givenNotLocked();
        givenFailCount(2);
        givenCaptchaPasses();
        givenAuthenticationSucceeds();
        givenTokenGenerated();

        assertDoesNotThrow(() -> userService.login(dtoOf(CAPTCHA_UUID, "abcd"), IP),
                "验证码应忽略大小写：传小写也应通过并继续认证");

        verify(redisUtils).delete("captcha:" + CAPTCHA_UUID);
        verify(authenticationManager).authenticate(any());
    }

    // 三、失败计数与锁定（防爆破）

    /**
     * <p><b>为什么测它：</b>失败计数必须用 Redis increment 原子自增 —— 读-改-写在并发下会丢计数，防爆破就形同虚设。</p>
     * <p><b>关键断言：</b>抛「还剩 4 次尝试机会」；<b>verify increment 被调用</b>；第 1 次失败时设置过期；<b>never</b> 更新 last_login。</p>
     */

    @Test
    @DisplayName("[业务正确] 密码错误（首次）→ 失败计数【原子自增】+ 抛「还剩 N 次尝试机会」")
    void login_wrongPassword_shouldIncrementFailCountAndReportRemaining() {
        givenNotLocked();
        givenFailCount(0);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);   // 第 1 次失败
        givenAuthenticationThrows(new BadCredentialsException("bad credentials"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(dtoOf(null, null), IP));

        // 按真实行为断言：recordLoginFail 自己会抛，所以看到的是"还剩 N 次"，而不是"用户名或密码错误"
        assertTrue(ex.getMessage().contains("还剩 " + (MAX_FAIL_COUNT - 1) + " 次"),
                "实际：" + ex.getMessage());
        // 必须用 increment 原子自增（读-改-写会在并发下丢计数）
        verify(valueOperations).increment(anyString());
        // 第 1 次失败才设过期，避免每次都 expire
        verify(redisTemplate).expire(anyString(), anyLong(), eq(TimeUnit.MINUTES));
        // 失败不该更新 last_login
        verify(userMapper, never()).updateLastLogin(anyLong(), anyString());
    }

    /**
     * <p><b>为什么测它：</b>达到 5 次就锁定账号（写带 TTL 的 lock key，到期自动解锁）并清除失败计数，避免解锁后立刻又被锁。</p>
     * <p><b>关键断言：</b>抛「次数过多…已锁定」；<b>verify 写 lock key</b>；<b>verify delete 失败计数</b>。</p>
     */

    @Test
    @DisplayName("[业务正确] 失败达到 5 次 → 写锁定 key + 清除失败计数 + 抛「次数过多，账号已被锁定」")
    void login_failCountReachesMax_shouldLockAccount() {
        givenNotLocked();
        givenFailCount(4);
        givenCaptchaPasses();          // 失败≥2 后必须带验证码，否则会被挡在认证之前
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(Long.valueOf(MAX_FAIL_COUNT));
        givenAuthenticationThrows(new BadCredentialsException("bad credentials"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(dtoOf(CAPTCHA_UUID, CAPTCHA_CODE), IP));

        assertTrue(ex.getMessage().contains("次数过多"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("锁定"), "实际：" + ex.getMessage());
        // 写锁定 key（带过期时间，到期自动解锁）
        verify(valueOperations).set(anyString(), eq("locked"), anyLong(), eq(TimeUnit.MINUTES));
        // 锁定后清除失败计数，避免解锁后立刻又被锁
        verify(redisTemplate).delete(anyString());
    }

    /**
     * <p><b>为什么测它：</b>防御：未达上限时<b>绝不能</b>误锁账号（否则正常用户打错几次就被锁死）。</p>
     * <p><b>关键断言：</b>抛异常；<b>never 写 lock key</b>。</p>
     */

    @Test
    @DisplayName("[防御] 未达到最大次数时 → 【不】写锁定 key")
    void login_belowMaxFails_shouldNotLock() {
        givenNotLocked();
        givenFailCount(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(2L);   // 第 2 次失败
        givenAuthenticationThrows(new BadCredentialsException("bad credentials"));

        assertThrows(BusinessException.class, () -> userService.login(dtoOf(null, null), IP));

        verify(valueOperations, never()).set(anyString(), eq("locked"), anyLong(), eq(TimeUnit.MINUTES));
    }

    // 四、认证异常的分类处理

    /**
     * <p><b>为什么测它：</b>认证层抛 LockedException（管理员锁定）时，提示要与「密码错误」区分开。</p>
     * <p><b>关键断言：</b>抛异常且消息含「请联系管理员」。</p>
     */

    @Test
    @DisplayName("[业务错误] 认证抛 LockedException → 提示「账号已被锁定，请联系管理员」")
    void login_lockedException_shouldReportAdminLock() {
        givenNotLocked();
        givenFailCount(0);
        givenAuthenticationThrows(new LockedException("locked"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(dtoOf(null, null), IP));

        assertTrue(ex.getMessage().contains("请联系管理员"), "实际：" + ex.getMessage());
    }

    /**
     * <p><b>为什么测它：</b>被封禁的账号要给明确提示，不能只是笼统的「登录失败」。</p>
     * <p><b>关键断言：</b>抛异常且消息含「已被禁用」。</p>
     */

    @Test
    @DisplayName("[业务错误] 认证抛 DisabledException → 提示「账号已被禁用，请联系管理员」")
    void login_disabledException_shouldReportDisabled() {
        givenNotLocked();
        givenFailCount(0);
        givenAuthenticationThrows(new DisabledException("disabled"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(dtoOf(null, null), IP));

        assertTrue(ex.getMessage().contains("已被禁用"), "实际：" + ex.getMessage());
    }

    /**
     * <p><b>为什么测它：</b>业务异常穿透通道：临时密码未重置、账号封禁等提示，就是靠"内层 BusinessException 原样抛出"传达到用户面前的。</p>
     * <p><b>关键断言：</b>抛出的异常消息与内层 BusinessException <b>逐字相同</b>。</p>
     */

    @Test
    @DisplayName("[业务异常穿透] InternalAuthenticationServiceException 内含 BusinessException → 原样抛出")
    void login_internalAuthExceptionWithBusinessCause_shouldRethrowBusinessException() {
        givenNotLocked();
        givenFailCount(0);
        // 这是"临时密码未重置 / 账号封禁"等提示能传到用户面前的唯一通道
        BusinessException inner = new BusinessException("请自己通过临时密码重置自己的密码再登陆");
        givenAuthenticationThrows(
                new InternalAuthenticationServiceException("wrapped", inner));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(dtoOf(null, null), IP));

        assertEquals("请自己通过临时密码重置自己的密码再登陆", ex.getMessage(),
                "内层业务异常必须原样抛出，否则用户看不到真正原因");
    }

    // 五、认证通过后的收尾

    /**
     * <p><b>为什么测它：</b>数据异常场景：认证过了却查不到用户，必须报错而不是继续往下（否则后面会 NPE）。</p>
     * <p><b>关键断言：</b>抛「用户不存在」；<b>never</b> 更新 last_login。</p>
     */

    @Test
    @DisplayName("[异常场景] 认证通过但按用户名查不到用户 → 抛「用户不存在」")
    void login_authenticatedButUserMissing_shouldThrow() throws Exception {
        givenNotLocked();
        givenFailCount(0);
        givenAuthenticationSucceeds();
        when(userMapper.selectByUsername(ACCOUNT)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(dtoOf(null, null), IP));

        assertTrue(ex.getMessage().contains("用户不存在"), "实际：" + ex.getMessage());
        verify(userMapper, never()).updateLastLogin(anyLong(), anyString());
    }

    /**
     * <p><b>为什么测它：</b>成功收尾的三件事：清失败计数（否则下次还要输验证码）、更新最后登录（用于异常登录排查）、生成 token。</p>
     * <p><b>关键断言：</b>返回 token 正确；<b>verify delete 失败计数</b>；<b>verify updateLastLogin</b>；<b>verify generateToken</b>。</p>
     */

    @Test
    @DisplayName("[业务正确] 登录成功 → 清除失败计数 + 更新最后登录 + 生成 token")
    void login_success_shouldClearFailCountAndUpdateLastLogin() throws Exception {
        givenNotLocked();
        givenFailCount(3);
        givenCaptchaPasses();          // 失败≥2 后必须带验证码
        givenAuthenticationSucceeds();
        givenTokenGenerated();

        LoginResponseDto resp = userService.login(dtoOf(CAPTCHA_UUID, CAPTCHA_CODE), IP);

        assertNotNull(resp);
        assertEquals(TOKEN, resp.getToken());
        // 登录成功必须清失败计数，否则用户下次还要输验证码
        verify(redisTemplate).delete(anyString());
        // 记录最后登录信息（用于"异常登录"排查）
        verify(userMapper).updateLastLogin(USER_ID, IP);
        verify(jwtUtil).generateToken(eq(USER_ID), eq(ACCOUNT), any());
    }

    /**
     * <p><b>为什么测它：</b>降级设计：写登录缓存只是为了支持管理员强制下线，不是鉴权核心 —— Redis 挂了也必须能登录。</p>
     * <p><b>关键断言：</b>不抛异常；返回 token 正常。</p>
     */

    @Test
    @DisplayName("[容错] 写登录缓存失败 → 降级，登录仍然成功（管理员暂时无法强下线）")
    void login_redisCacheFailure_shouldDegradeGracefully() throws Exception {
        givenNotLocked();
        givenFailCount(0);
        givenAuthenticationSucceeds();
        givenTokenGenerated();
        // ValueOperations#set 返回 void —— void 方法不能用 when()，必须用 doThrow().when()
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOperations).set(anyString(), any(), anyLong(), eq(TimeUnit.HOURS));

        LoginResponseDto resp = userService.login(dtoOf(null, null), IP);

        assertNotNull(resp, "Redis 挂了也要能登录 —— 登录缓存只是为了让管理员强制下线，不是鉴权核心");
        assertEquals(TOKEN, resp.getToken());
    }

    // 助手

    private LoginDto dtoOf(String captchaUuid, String captchaCode) {
        LoginDto dto = new LoginDto();
        dto.setAccount(ACCOUNT);
        dto.setPassword(RAW_PASSWORD);
        dto.setCaptchaUuid(captchaUuid);
        dto.setCaptchaCode(captchaCode);
        return dto;
    }

    /** stub：账号未被锁定 */
    private void givenNotLocked() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
    }

    /** stub：当前失败计数为 n */
    private void givenFailCount(int n) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(n == 0 ? null : n);
    }

    /** stub：认证抛指定异常 */
    private void givenAuthenticationThrows(RuntimeException ex) {
        when(authenticationManager.authenticate(any())).thenThrow(ex);
    }

    /** stub：认证通过，且能查到用户 */
    private void givenAuthenticationSucceeds() {
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername(ACCOUNT)
                .password("ignored")
                .authorities(List.of(new SimpleGrantedAuthority("order:read")))
                .build();

        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any())).thenReturn(authentication);

        User user = new User();
        user.setId(USER_ID);
        user.setUsername(ACCOUNT);
        user.setStatus(1);
        when(userMapper.selectByUsername(ACCOUNT)).thenReturn(user);

    }

    /** stub：token 生成（只有真正走到成功收尾的用例才需要 —— 拆开是为了让严格模式查出路径） */
    private void givenTokenGenerated() {
        when(jwtUtil.generateToken(eq(USER_ID), eq(ACCOUNT), any())).thenReturn(TOKEN);
    }

    /** stub：图形验证码校验通过 */
    private void givenCaptchaPasses() {
        when(redisUtils.get("captcha:" + CAPTCHA_UUID)).thenReturn(CAPTCHA_CODE);
    }
}
