package com.zhou.peakflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhou.peakflow.enums.UserAccountStatus;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhou.peakflow.dto.user.AdminUserPageDto;
import com.zhou.peakflow.dto.user.UpdateUserPasswordDto;
import com.zhou.peakflow.utils.RedisUtils;
import com.zhou.peakflow.vo.PageVo;
import io.lettuce.core.RedisCommandTimeoutException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.authentication.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhou.peakflow.dto.auth.LoginDto;
import com.zhou.peakflow.dto.auth.LoginResponseDto;
import com.zhou.peakflow.dto.auth.RegisterDto;
import com.zhou.peakflow.dto.auth.UserInfoDto;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.UserMapper;
import com.zhou.peakflow.pojo.User;
import com.zhou.peakflow.service.UserService;
import com.zhou.peakflow.utils.AliOssUtil;
import com.zhou.peakflow.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisTemplate;

import static com.zhou.peakflow.constant.CacheExpireConstants.*;
import static com.zhou.peakflow.constant.RedisKeyConstant.*;
import static com.zhou.peakflow.constant.RegularPatterns.NICKNAME;
import static com.zhou.peakflow.constant.RegularPatterns.USERNAME;

/**
 * 用户服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final AliOssUtil aliOssUtil;

    private final JwtUtil jwtUtil;

    private final AuthenticationManager authenticationManager;

    private final RedisTemplate<String, Object> redisTemplate;
    // 图片最大限制 2MB
    private static final int MAX_AVATAR_SIZE = 2 * 1024 * 1024;
    // 连续登录最大次数
    private static final int MAX_FAIL_COUNT = 5;
    // 非临时密码标记（0-非临时，1-管理员重置后的临时密码）
    private static final int NOT_TEMP_PASSWORD = 0;
    // 默认新用户状态为可用
    private static final int DEFAULT_USER_STATUS = 1;
    // 默认重置随机密码池
    private static final String CHAR_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList("jpg", "jpeg", "png");
    // 用户权限缓存 Key
    public static final String LOGIN_USER_PERMS = "user:perms:";

    // 本地登录态缓存（用于 Redis 故障时的降级）
    private static final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> LOCAL_LOGIN_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final RedisUtils redisUtils;


    // 校验文件类型是否为图片（安全处理 null 与无扩展名，避免 substring(-1) 崩溃）
    private boolean isValidImageType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return false;
        }
        String ext = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        return ALLOWED_IMAGE_TYPES.contains(ext);
    }

    // 用户注册
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResponseDto register(RegisterDto registerDto) {
        log.info("开始注册用户：username={}, email={}", registerDto.getUsername(), registerDto.getEmail());

        try {
            // 1. 前置校验（快速失败，提升体验）
            long usernameCount = userMapper.countByUsername(registerDto.getUsername());
            if (usernameCount > 0) {
                throw new BusinessException("用户名已存在");
            }

            long emailCount = userMapper.countByEmail(registerDto.getEmail());
            if (emailCount > 0) {
                throw new BusinessException("邮箱已被注册");
            }

            // 2. 创建并保存用户
            User user = new User();
            BeanUtils.copyProperties(registerDto, user);

            user.setPassword(passwordEncoder.encode(registerDto.getPassword()));

            user.setNickname(registerDto.getNickname() != null ? registerDto.getNickname() : registerDto.getUsername());
            user.setStatus(DEFAULT_USER_STATUS);
            user.setIsTempPassword(NOT_TEMP_PASSWORD);

            userMapper.customInsert(user);

            userMapper.userRole(user.getUsername());

            // 3. 生成 Token
            List<String> permissionsList = userMapper.selectPermissionsByUsername(user.getUsername());

            String token = jwtUtil.generateToken(user.getId(), user.getUsername(), permissionsList);

            return new LoginResponseDto(token, user);
        } catch (DuplicateKeyException e) {
            // 捕获并发导致的唯一键冲突
            log.warn("注册并发冲突：username={}", registerDto.getUsername());
            throw new BusinessException("用户名或邮箱已被注册，请刷新后重试");
        }
    }


    // 用户登录
    @Override
    public LoginResponseDto login(LoginDto loginDto, String ip) {
        log.info("开始登录：account={}, ip={}", loginDto.getAccount(), ip);

        // 检查有没有被锁
        checkAccountLocked(loginDto.getAccount());

        String failKey = LOGIN_FAIL + loginDto.getAccount();

        // 2、读取失败计数
        Object obj = redisTemplate.opsForValue().get(failKey);
        int failCount = 0;
        if(obj != null){
            failCount = Integer.parseInt(obj.toString());
        }

        // 3、失败 >= 2 次强制验证码
        if(failCount >= 2){
            if(loginDto.getCaptchaUuid() == null || loginDto.getCaptchaCode() == null){
                log.warn("验证码未输入，account={}, ip={}", loginDto.getAccount(), ip);
                throw new BusinessException("请输入图形验证码");
            }
            String captchaKey = "captcha:" + loginDto.getCaptchaUuid();
            String realCode = redisUtils.get(captchaKey);

            redisUtils.delete(captchaKey);

            if(realCode == null || !realCode.equalsIgnoreCase(loginDto.getCaptchaCode())){
                log.warn("验证码错误，account={}, ip={}", loginDto.getAccount(), ip);
                throw new BusinessException("验证码错误");
            }
        }

        // 验证码校验通过，继续走账号密码逻辑
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginDto.getAccount(),
                            loginDto.getPassword()
                    )
            );
            log.debug("认证成功，account={}", loginDto.getAccount());
        } catch (InternalAuthenticationServiceException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BusinessException be) {
                throw be;
            }
            log.error("认证内部未知异常，account={}", loginDto.getAccount(), e);
            throw e;
        } catch (BadCredentialsException e) {
            log.warn("登录失败：密码错误，account={}, ip={}", loginDto.getAccount(), ip);
            recordLoginFail(loginDto.getAccount());
            throw new BusinessException("用户名或密码错误");
        } catch (LockedException e) {
            log.warn("登录失败：账号锁定，account={}, ip={}", loginDto.getAccount(), ip);
            throw new BusinessException("账号已被锁定，请联系管理员");
        } catch (DisabledException e) {
            log.warn("登录失败：账号禁用，account={}, ip={}", loginDto.getAccount(), ip);
            throw new BusinessException("账号已被禁用，请联系管理员");
        }

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        User user = userMapper.selectByUsername(userDetails.getUsername());

        if (user == null) {
            log.warn("登录异常：认证通过但用户不存在，username={}", userDetails.getUsername());
            throw new BusinessException("用户不存在");
        }

        log.info("认证成功：userId={}, username={}", user.getId(), user.getUsername());

        // 5. 登录成功，清除失败次数
        clearLoginFail(loginDto.getAccount());

        // 6. 更新最后登录信息
        userMapper.updateLastLogin(user.getId(), ip);

        // 7. 查询用户权限
        Collection<? extends GrantedAuthority> authorities = userDetails.getAuthorities();
        List<String> permissionsList = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        // 8. 将登录信息存入 Redis（仅用于管理员强制下线，非鉴权核心，可降级）
        Map<String, Object> cacheData = new HashMap<>();
        cacheData.put("status", user.getStatus());
        cacheData.put("permissions", permissionsList);

        try {
            redisTemplate.opsForValue().set(
                    LOGIN_USER + ":" + user.getUsername(),
                    cacheData,
                    LOGIN_USER_HOURS,
                    TimeUnit.HOURS);
        } catch (RedisConnectionFailureException | RedisCommandTimeoutException e) {
            // Redis连接失败，降级：放弃写入Redis，管理员暂时无法强制下线该用户
            log.error("Redis写入登录缓存失败，降级：无法支持管理员强制下线", e);
            sendAlertNotification("Redis 写入失败，管理员暂时无法强制下线", user.getUsername());
        }

        // 9. 生成 token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), permissionsList);
        LoginResponseDto loginResponseDto = new LoginResponseDto(token, user);

        // 10. 记录登录日志（异步执行，失败不影响登录）
        final Long userId = user.getId();
        final String username = user.getUsername();
        CompletableFuture.runAsync(() -> {
            try {
                userMapper.insertLoginLog(userId, username, ip);
            } catch (Exception e) {
                log.error("登录日志记录失败（不影响登录），userId={}, error={}", userId, e.getMessage());
            }
        });

        return loginResponseDto;
    }


    // 发送告警通知（模拟实现）
    private void sendAlertNotification(String message, String username) {
        log.error("【告警】{}，username={}", message, username);
        // 实际项目中可以对接钉钉/企业微信/短信告警
        // alertService.send(message);
    }


    // 用户登出
    @Override
    public void logout(String token) {
        String username = jwtUtil.getUsernameFromToken(token);

        // 2. 将 Token 加入黑名单（失效时间设为 Token 剩余有效期）
        jwtUtil.addToBlacklist(token);

        // 3. 删除 Redis 中的用户权限/状态缓存，辅助操作，失败可降级
        try {
            redisTemplate.delete(LOGIN_USER + ":" + username);
        } catch (RedisConnectionFailureException | RedisCommandTimeoutException e) {
            log.error("登出时删除Redis登录缓存失败，降级处理，用户token已加入黑名单，登出有效", e);

        }
        log.info("用户登出成功，username={}", username);

    }
    @Override
    public LoginResponseDto refreshToken(String token) {
        log.info("开始刷新Token");

        // 去掉 "Bearer " 前缀（AuthController 直接透传了 Authorization 头，未 strip）
        token = jwtUtil.removeTokenPrefix(token);

        try {
            String username = jwtUtil.getUsernameFromToken(token);

            // 2. 核心并发防护：将旧 Token 立即加入黑名单
            // 这样即使有第二个并发请求进来，也会在第一步 validateToken 时被拦截
            jwtUtil.addToBlacklist(token);
            log.debug("旧Token已加入黑名单: {}", username);

            // 3. 查询用户最新信息（确保权限等信息是最新的）
            User user = userMapper.selectByUsername(username);
            if (user == null) {
                throw new BusinessException("用户不存在");
            }

            // 4. 获取最新权限
            List<String> permissionsList = userMapper.selectPermissionsByUsername(user.getUsername());

            // 5. 生成新 Token
            String newToken = jwtUtil.generateToken(user.getId(), user.getUsername(), permissionsList);

            // 6. 更新 Redis 中的登录状态缓存（如果有）
            Map<String, Object> cacheData = new HashMap<>();
            cacheData.put("status", user.getStatus());
            cacheData.put("permissions", permissionsList);
            try {
                redisTemplate.opsForValue().set(LOGIN_USER + ":" + username, cacheData, LOGIN_USER_HOURS, TimeUnit.HOURS);
            } catch (RedisConnectionFailureException | RedisCommandTimeoutException e) {
                log.error("刷新token时更新Redis登录缓存失败", e);
                // 抛出业务异常，不会返回新token给前端
                throw new BusinessException("令牌刷新失败，系统异常，请稍后重试");
            }
            log.info("刷新Token成功，username={}", username);
            return new LoginResponseDto(newToken, user);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("刷新Token异常", e);
            throw new BusinessException("刷新Token失败");
        }
    }

    // 根据ID获取用户信息
    @Override
    public UserInfoDto getUserInfo(Long userId) {
        log.debug("获取用户信息，userId={}", userId);

        User user = userMapper.selectById(userId);
        if (user == null) {
            log.warn("获取用户信息失败：用户不存在，userId={}", userId);
            throw new BusinessException("用户不存在");
        }

        UserInfoDto userInfoDto = new UserInfoDto();
        BeanUtils.copyProperties(user, userInfoDto);

        log.debug("获取用户信息成功，userId={}, username={}", userId, user.getUsername());
        return userInfoDto;
    }

    // 修改密码
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean changePassword(String username, String oldPassword, String newPassword) {
        log.info("开始修改密码，username={}", username);

        // 1. 查询用户
        User user = userMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 2. 验证旧密码
        boolean matchResult = passwordEncoder.matches(oldPassword, user.getPassword());
        if (!matchResult) {
            throw new BusinessException("原密码错误");
        }

        // 使用 CAS 原子更新密码
        // 只有当密码还是旧密码时才更新（防止并发修改覆盖）
        String encodedNewPassword = passwordEncoder.encode(newPassword);

        int rows = userMapper.updatePasswordWithCas(
                user.getId(),
                encodedNewPassword,
                user.getPassword()
        );

        if (rows == 0) {
            log.warn("【并发冲突】修改密码失败，密码已被其他操作修改，username={}", username);
            throw new BusinessException("密码修改失败，请刷新后重试");
        }

        log.info("修改密码成功，username={}", username);
        return true;
    }


    // 修改头像
    @Override

    public String changAvatar(String token, MultipartFile file) {
        log.info("开始修改头像，文件大小={} bytes", file.getSize());

        // 获取相应用户
        String username = jwtUtil.getUsernameFromToken(token);
        log.debug("修改头像，username={}", username);

        // 1. 校验扩展名（放在生成文件名之前，否则无扩展名会 substring(-1) 崩溃）
        String originFilename = file.getOriginalFilename();
        if (originFilename == null || !isValidImageType(originFilename)) {
            log.warn("头像修改失败：文件名缺失或类型不支持，filename={}", originFilename);
            throw new BusinessException("仅支持jpg/jpeg/png格式的头像文件");
        }
        String ext = originFilename.substring(originFilename.lastIndexOf(".") + 1).toLowerCase();

        // 2. 校验文件大小（与 application.yml 的 max-file-size=2MB 保持一致）
        long fileSize = file.getSize();
        if (fileSize > MAX_AVATAR_SIZE) {
            log.warn("头像修改失败：文件大小超限，size={} bytes", fileSize);
            throw new BusinessException("文件大小不能超过2MB");
        }

        // 3. 校验文件内容是否为真实图片（防把任意文件改名为 .jpg 绕过扩展名校验）
        try (InputStream in = file.getInputStream()) {
            if (ImageIO.read(in) == null) {
                log.warn("头像修改失败：文件内容不是有效图片，filename={}", originFilename);
                throw new BusinessException("文件内容不是有效图片，请上传真实图片");
            }
        } catch (IOException e) {
            log.error("头像修改失败：文件读取异常", e);
            throw new BusinessException("文件读取失败");
        }

        // 4. 生成唯一文件名（去掉 UUID 连字符）
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        // 5. 上传文件到OSS
        String url;
        try (InputStream in = file.getInputStream()) {
            url = aliOssUtil.uploadFile(filename, in);
            log.debug("文件上传OSS成功，url={}", url);
        } catch (Exception e) {
            log.error("头像修改失败：文件上传异常", e);
            throw new BusinessException("文件上传失败");
        }

        // 6. 修改数据库用户头像
        userMapper.updateAvatarByUsername(url, username);

        log.info("头像修改成功，username={}, url={}", username, url);
        return url;
    }

    // 修改昵称
    @Override
    public boolean changeNickname(String token, String nickname) {
        log.info("开始修改昵称，nickname={}", nickname);

        // 判断用户昵称格式
        if (!nickname.matches( NICKNAME)) {
            log.warn("修改昵称失败：昵称格式不正确，nickname={}", nickname);
            throw new BusinessException("昵称格式不正确");
        }

        // 获取相应用户
        String username = jwtUtil.getUsernameFromToken(token);
        log.debug("修改昵称，username={}", username);

        // 修改数据库用户昵称
        userMapper.updateNicknameByUsername(nickname, username);

        log.info("昵称修改成功，username={}, nickname={}", username, nickname);

        return true;
    }

    // 查询用户列表（分页）
    @Override
    public PageVo<User> listUsers(AdminUserPageDto adminUserPageDto) {
        log.info("开始分页查询用户列表，pageNum={}, pageSize={}, username={}, status={}",
                adminUserPageDto.getPageNum(),
                adminUserPageDto.getPageSize(),
                adminUserPageDto.getUsername(),
                adminUserPageDto.getStatus());

        // 分页参数
        int pageNum = adminUserPageDto.getPageNum();
        int pageSize = adminUserPageDto.getPageSize();
        Page<User> page = new Page<>(pageNum, pageSize);

        // 构建查询条件
        QueryWrapper<User> wrapper = new QueryWrapper<>();

        // 用户名模糊查询
        String username = adminUserPageDto.getUsername();
        if (StringUtils.hasText(username)) {
            if (!username.matches(USERNAME)) {
                log.warn("查询用户列表失败：用户名格式不正确，username={}", username);
                throw new BusinessException("查询用户名格式只能包括字母数字下划线，一到二十位");
            }
            wrapper.likeRight("username", username);
            log.debug("添加用户名模糊查询条件：username like '{}%'", username);
        }

        // 状态筛选
        Integer status = adminUserPageDto.getStatus();
        if (status != null) {
            if (status != 0 && status != 1) {
                log.warn("查询用户列表失败：状态值无效，status={}", status);
                throw new BusinessException("用户状态只能为0或1");
            }
            wrapper.eq("status", status);
            log.debug("添加状态查询条件：status={}", status);
        }

        // 执行分页查询
        Page<User> p = this.page(page, wrapper);

        log.info("查询用户列表成功，返回{}条记录，总记录数{}",
                p.getRecords().size(),
                p.getTotal());

        return listUsers(p);
    }

    // 封号（并发安全版）
    @Override
    public void banUser(Long userId) {
        log.info("开始封号，targetUserId={}", userId);

        // 1. 查询当前状态
        User user = userMapper.selectStatusById(userId);
        if (user == null) {
            throw new BusinessException("被封用户不存在");
        }

        // 使用 CAS 原子更新
        // 只有当状态还是 ENABLED 时才更新为 DISABLED
        int rows = userMapper.updateStatusWithCas(
                userId,
                UserAccountStatus.DISABLED.getCode(),
                UserAccountStatus.ENABLED.getCode()
        );

        if (rows == 0) {
            log.warn("【并发冲突】封号失败，用户状态已被其他管理员修改或者本身已被封号，userId={}", userId);
            throw new BusinessException("用户状态已被其他管理员修改或者本身已被封号");
        }

        // 4. 清理 Redis 缓存（强制下线）
        redisTemplate.delete(LOGIN_USER + ":" + user.getUsername());

        log.info("封号成功：userId={}, username={}", userId, user.getUsername());
    }

    // 解封（并发安全版）
    @Override
    public void unbanUser(Long userId) {
        log.info("开始解封，targetUserId={}", userId);

        User user = userMapper.selectStatusById(userId);
        if (user == null) {
            throw new BusinessException("解封用户不存在");
        }

        // 核心修复：使用 CAS 原子更新
        // 只有当状态还是 DISABLED 时才更新为 ENABLED
        int rows = userMapper.updateStatusWithCas(
                userId,
                UserAccountStatus.ENABLED.getCode(),
                UserAccountStatus.DISABLED.getCode()
        );

        if (rows == 0) {
            log.warn("【并发冲突】解封失败，用户状态已被其他管理员修改或已经解封，userId={}", userId);
            throw new BusinessException("用户状态已被其他管理员修改或已经解封");
        }

        log.info("解封成功：userId={}, username={}", userId, user.getUsername());
    }

    @Override
    public String resetPassword(Long userId) {
        log.info("开始重置密码，targetUserId={}", userId);

        User user = userMapper.selectStatusById(userId);
        if (user == null) {
            throw new BusinessException("重置用户不存在");
        }

        // 生成随机新密码
        String newPassword = randomPassword();

        String encodedPassword = passwordEncoder.encode(newPassword);

        // CAS原子：更新密码 + 设置临时标记，单条SQL
        int rows = userMapper.resetPasswordCas(userId, encodedPassword);
        if(rows == 0){
            throw new BusinessException("重置密码失败，用户状态发生变化，请重试");
        }

        // 4. 清理 Redis 缓存（强制下线，安全核心步骤，不可降级）
        try {
            redisTemplate.delete(LOGIN_USER + ":" + user.getUsername());
        } catch (RedisConnectionFailureException | RedisCommandTimeoutException e) {
            log.error("重置密码：删除登录缓存失败，无法强制下线用户，userId={},username={}", userId, user.getUsername(), e);
            // 自定义业务异常，返回友好提示给前端
            throw new BusinessException("重置密码失败，当前无法踢除用户在线会话，请稍后重试");
        }

        log.info("重置密码成功：userId={}, username={}", userId, user.getUsername());
        return newPassword;
    }

    // 生成随机明文密码
    private String randomPassword() {
        // 字符池：去掉容易混淆的 O,0,1,l,I
        char[] chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789".toCharArray();
        SecureRandom random = new SecureRandom();
        // 密码长度 8位，你可以改成 10、12
        int pwdLen = 8;
        StringBuilder sb = new StringBuilder(pwdLen);
        for (int i = 0; i < pwdLen; i++) {
            int index = random.nextInt(chars.length);
            sb.append(chars[index]);
        }
        return sb.toString();
    }

    // 强制修改密码
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String forceChangePassword(UpdateUserPasswordDto updateUserPasswordDto) {
        String username = updateUserPasswordDto.getUsername();
        log.info("开始强制修改密码，targetUsername={}", username);

        String oldPassword = updateUserPasswordDto.getOldPassword();
        String newPassword = updateUserPasswordDto.getNewPassword();

        // 获取用户
        User user = userMapper.selectByUsername(username);
        if (user == null) {
            log.warn("强制修改密码失败：用户不存在，username={}", username);
            throw new BusinessException("用户不存在");
        }
        if(user.getStatus()==0){
            log.warn("该用户已被封禁");
            throw new BusinessException("该用户已被封禁");
        }

        // 内存校验：用户传入的旧明文密码是否匹配数据库密文
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            log.warn("强制修改密码失败：旧密码错误，username={}", username);
            throw new BusinessException("旧密码错误");
        }

        String encodedNewPassword = passwordEncoder.encode(newPassword);

        // 原子CAS更新：一条SQL同时修改 password + is_temp_password
        int updateRows = userMapper.updatePasswordAndTempFlagCasById(
                user.getId(),
                encodedNewPassword,
                user.getPassword()
        );

        if (updateRows == 0) {
            log.warn("【并发冲突】强制改密码失败，用户密码已被其他操作修改,username={}",username);
            throw new BusinessException("用户信息已变更，请刷新重试");
        }
        return newPassword;

    }

    // ---- 私有方法 ----

    // 封装分页结果
    private PageVo<User> listUsers(Page<User> p) {
        List<User> userList = p.getRecords();
        long total = p.getTotal();
        long pageNum = p.getCurrent();
        long pages = p.getPages();
        long pageSize = p.getSize();

        return new PageVo<>(userList, total, pageNum, pages, pageSize);
    }


    // 检查账号是否被锁定
    private void checkAccountLocked(String account) {
        String lockKey = LOGIN_LOCK + account;
        // 删除了原来2条轻量redis埋点，减少日志噪音
        Boolean isLocked = redisTemplate.hasKey(lockKey);
        if (Boolean.TRUE.equals(isLocked)) {
            Long expireTime = redisTemplate.getExpire(lockKey, TimeUnit.MINUTES);
            log.info("账号已被锁定，account={}, 剩余锁定时间={}分钟", account, expireTime);
            String message = String.format("账号已被锁定，请%d分钟后重试", expireTime);
            throw new BusinessException(message);
        }
    }

    // 记录登录失败
    // 删掉 @Transactional，没有DB操作不需要事务
    public void recordLoginFail(String account) {
        String failKey = LOGIN_FAIL + account;
        Long failCount = 0L;
        boolean redisAvailable = true;
        try {
            // 原子自增
            failCount = redisTemplate.opsForValue().increment(failKey);
        } catch (RedisConnectionFailureException e) {
            log.warn("登录失败计数Redis宕机，执行降级，放弃失败次数统计", e);
            redisAvailable = false;
        }

        // Redis不可用，直接退出方法，不再执行后面所有Redis操作，也不抛出剩余次数提示
        if (!redisAvailable) {
            return;
        }

        if (failCount == 1) {
            // 第一次失败，设置过期时间
            redisTemplate.expire(failKey, LOGIN_FAIL_MINUTES, TimeUnit.MINUTES);
        }

        if (failCount >= MAX_FAIL_COUNT) {
            // 锁定账号
            String lockKey = LOGIN_LOCK + account;
            redisTemplate.opsForValue().set(lockKey, "locked",
                    LOGIN_LOCK_MINUTES, TimeUnit.MINUTES);
            redisTemplate.delete(failKey);
            throw new BusinessException("密码错误次数过多，账号已被锁定");
        }

        int remaining = MAX_FAIL_COUNT - failCount.intValue();
        throw new BusinessException("密码错误，还剩 " + remaining + " 次尝试机会");
    }

    // 登录成功后清除失败记录
    private void clearLoginFail(String account) {
        String failKey = LOGIN_FAIL + account;
        log.info("登录成功，清除登录失败记录：account={}", account);
        redisTemplate.delete(failKey);
    }
}