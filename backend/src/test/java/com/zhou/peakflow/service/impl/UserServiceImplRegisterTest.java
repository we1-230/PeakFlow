package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.dto.auth.RegisterDto;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户注册 <b>单元测试</b>：{@code UserServiceImpl#register(registerDto)}（@Transactional）。
 *
 * <p>流程：前置查重（countByUsername / countByEmail 快速失败）→ 组装 User（密码走 PasswordEncoder 加密、
 * 昵称为空回落用户名、status = 1、isTempPassword = 0）→ customInsert 落库 → userRole(username) 分配普通
 * 角色（SQL 写死 role_id=1）→ 查权限、生成 JWT → 捕获 DuplicateKeyException（并发插入撞唯一键）。</p>
 *
 * <p>关键设计：第 1 步查重是 check-then-act，两个请求可能同时通过查重再都去 INSERT，真正拦住第二次的是
 * 数据库唯一索引（uk_username_del / uk_email_del），捕获 DuplicateKeyException 就是把这道闸门的报错
 * 翻译成用户能看懂的话 —— 应用层负责体验，数据库层负责正确性。</p>
 *
 * <p>关于"用户名 + deleted 联合唯一"：唯一索引带 deleted 列，意味着逻辑删除过的用户名可被再次注册，
 * 这是刻意设计（否则用户注销后名字永久占用）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户注册单元测试 UserServiceImpl#register")
class UserServiceImplRegisterTest {

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

    @InjectMocks
    private UserServiceImpl userService;

    // 常量

    private static final String USERNAME = "newuser001";
    private static final String RAW_PASSWORD = "Ab123456";
    private static final String ENCODED_PASSWORD = "$2a$10$encodedHashValue";
    private static final String EMAIL = "newuser001@itest.com";
    private static final String PHONE = "13800000001";
    private static final String NICKNAME = "新用户";
    private static final String TOKEN = "mocked.jwt.token";
    private static final long USER_ID = 1001L;

    // 一、查重（快速失败）

    /**
     * <p><b>为什么测它：</b>快速失败：用户名已冲突就该立刻返回，不必再花一次 DB 查询去查邮箱。</p>
     * <p><b>关键断言：</b>抛「用户名已存在」；<b>never</b> 查邮箱、<b>never</b> 落库。</p>
     */

    @Test
    @DisplayName("[业务错误] 用户名已存在 → 抛「用户名已存在」，且【不再查邮箱、不落库】")
    void register_duplicateUsername_shouldThrowWithoutFurtherSteps() {
        when(userMapper.countByUsername(USERNAME)).thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register(dtoOf(NICKNAME)));

        assertTrue(ex.getMessage().contains("用户名已存在"), "实际：" + ex.getMessage());
        // 路径断言：用户名已冲突就该立刻返回，不必再查邮箱消耗一次 DB 查询
        verify(userMapper, never()).countByEmail(anyString());
        verify(userMapper, never()).customInsert(any(User.class));
    }

    /**
     * <p><b>为什么测它：</b>邮箱是登录/找回密码的凭据，重复注册会带来账号归属混乱。</p>
     * <p><b>关键断言：</b>抛「邮箱已被注册」；且 <b>verify 查用户名已走过</b>（证明失败在第二道查重）；<b>never</b> 落库。</p>
     */

    @Test
    @DisplayName("[业务错误] 邮箱已被注册 → 抛「邮箱已被注册」，且不落库")
    void register_duplicateEmail_shouldThrow() {
        when(userMapper.countByUsername(USERNAME)).thenReturn(0);
        when(userMapper.countByEmail(EMAIL)).thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register(dtoOf(NICKNAME)));

        assertTrue(ex.getMessage().contains("邮箱已被注册"), "实际：" + ex.getMessage());
        // 路径断言：查用户名 → 查邮箱 都走过了，说明失败发生在第二道查重
        verify(userMapper).countByUsername(USERNAME);
        verify(userMapper, never()).customInsert(any(User.class));
    }

    // 二、正常注册

    /**
     * <p><b>为什么测它：</b>注册的默认值语义：密码必须加密、昵称为空回落用户名、status=1、非临时密码。任何一个写错都会导致用户登不上或显示异常。</p>
     * <p><b>关键断言：</b>落库对象：password = encode 后的值（<b>绝不是明文</b>）；nickname 回落用户名；status=1；isTempPassword=0。</p>
     */

    @Test
    @DisplayName("[业务正确] 正常注册 → 密码加密入库 + 默认昵称回落用户名 + status=1 + 非临时密码")
    void register_valid_shouldEncryptPasswordAndApplyDefaults() {
        givenRegistrationSucceeds();

        userService.register(dtoOf(null));   // 昵称传 null，验证回落到用户名

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).customInsert(captor.capture());
        User inserted = captor.getValue();

        assertEquals(USERNAME, inserted.getUsername());
        assertEquals(EMAIL, inserted.getEmail());
        assertEquals(PHONE, inserted.getPhone());
        // 绝对不能明文入库：必须断言存的是 encode 之后的值
        assertEquals(ENCODED_PASSWORD, inserted.getPassword(),
                "密码必须经过 PasswordEncoder 加密后再入库");
        // 昵称为空时回落到用户名
        assertEquals(USERNAME, inserted.getNickname(),
                "昵称未填时应回落成用户名（否则前端展示会是空白）");
        // 默认状态与临时密码标记
        assertEquals(1, inserted.getStatus(), "新用户默认启用(status=1)");
        assertEquals(0, inserted.getIsTempPassword(), "注册的密码不是临时密码");
    }

    /**
     * <p><b>为什么测它：</b>对照：填了昵称就用填的，不该被回落逻辑覆盖。</p>
     * <p><b>关键断言：</b>落库 nickname = 传入的昵称。</p>
     */

    @Test
    @DisplayName("[业务正确] 填了昵称就用填的，不回落")
    void register_withNickname_shouldKeepProvidedNickname() {
        givenRegistrationSucceeds();

        userService.register(dtoOf(NICKNAME));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).customInsert(captor.capture());
        assertEquals(NICKNAME, captor.getValue().getNickname());
    }

    /**
     * <p><b>为什么测它：</b>注册即登录：要分配普通角色并生成带权限的 token。角色 id 写错（比如填 2）会让所有新用户直接变管理员。</p>
     * <p><b>关键断言：</b>userRole 被调用；token = mock 值；<b>generateToken 收到的权限列表正确</b>。</p>
     */

    @Test
    @DisplayName("[业务正确] 注册成功后 → 分配普通角色 + 生成 token + 返回用户信息")
    void register_success_shouldAssignRoleAndReturnToken() {
        givenRegistrationSucceeds();

        LoginResponseDto resp = userService.register(dtoOf(NICKNAME));

        assertNotNull(resp, "应返回登录响应（注册即登录）");
        assertEquals(TOKEN, resp.getToken());
        assertNotNull(resp.getUser(), "应回带用户信息");

        // 给新用户分配角色 —— 内部 SQL 明确写死 role_id = 1（普通用户）
        //    若这里写错成 2，所有新注册用户都会直接变成管理员
        verify(userMapper).userRole(USERNAME);

        // token 里带上权限列表
        ArgumentCaptor<List<String>> permsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jwtUtil).generateToken(eq(USER_ID), eq(USERNAME), permsCaptor.capture());
        assertEquals(List.of("order:read"), permsCaptor.getValue(),
                "token 应携带该用户的权限码");
    }

    /**
     * <p><b>为什么测它：</b>密码只应加密一次 —— 重复加密会导致用户用原密码登不上。</p>
     * <p><b>关键断言：</b>不抛异常；<b>verify encode 只被调用 1 次且入参是明文</b>。</p>
     */

    @Test
    @DisplayName("[业务正确] 密码加密用的是注册时传来的明文，且只加密一次")
    void register_shouldEncodeRawPasswordExactlyOnce() {
        givenRegistrationSucceeds();

        assertDoesNotThrow(() -> userService.register(dtoOf(NICKNAME)));

        verify(passwordEncoder).encode(RAW_PASSWORD);
    }

    // 三、并发冲突（数据库唯一索引兜底）

    /**
     * <p><b>为什么测它：</b>并发场景：查重是 check-then-act，两个请求可能同时通过查重；真正拦住的是数据库唯一索引。捕获 DuplicateKeyException 就是把底层报错翻译成用户能懂的话。</p>
     * <p><b>关键断言：</b>抛异常且消息含「已被注册」「刷新」；<b>verify 落库被调用过</b>（证明前两道查重都放行了）；<b>never</b> 分配角色。</p>
     */

    @Test
    @DisplayName("[并发防护] 插入撞唯一键（DuplicateKeyException）→ 转成友好提示，而不是抛原始异常")
    void register_duplicateKeyOnInsert_shouldTranslateToFriendlyMessage() {
        when(userMapper.countByUsername(USERNAME)).thenReturn(0);
        when(userMapper.countByEmail(EMAIL)).thenReturn(0);
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        // 模拟并发：查重时还没有，插入时另一个请求已经抢先落库
        when(userMapper.customInsert(any(User.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry '" + USERNAME + "' for key 'uk_username_del'"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register(dtoOf(NICKNAME)));

        assertTrue(ex.getMessage().contains("已被注册"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("刷新"),
                "并发场景要提示用户刷新后重试，实际：" + ex.getMessage());
        // 路径断言：确实走到了插入这一步（说明前两道查重都放行了）
        verify(userMapper).customInsert(any(User.class));
        // 插入失败后不该继续分配角色
        verify(userMapper, never()).userRole(anyString());
    }

    // 助手

    private RegisterDto dtoOf(String nickname) {
        RegisterDto dto = new RegisterDto();
        dto.setUsername(USERNAME);
        dto.setPassword(RAW_PASSWORD);
        dto.setEmail(EMAIL);
        dto.setPhone(PHONE);
        dto.setNickname(nickname);
        return dto;
    }

    /** stub：一次成功的注册（查重通过 + 加密 + 插入 + 角色 + 权限 + token） */
    private void givenRegistrationSucceeds() {
        when(userMapper.countByUsername(USERNAME)).thenReturn(0);
        when(userMapper.countByEmail(EMAIL)).thenReturn(0);
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userMapper.customInsert(any(User.class))).thenAnswer(inv -> {
            // 模拟自增主键回填（生产代码用 @Options(useGeneratedKeys=true)）
            inv.getArgument(0, User.class).setId(USER_ID);
            return 1;
        });
        when(userMapper.selectPermissionsByUsername(USERNAME)).thenReturn(List.of("order:read"));
        when(jwtUtil.generateToken(USER_ID, USERNAME, List.of("order:read"))).thenReturn(TOKEN);
    }
}
