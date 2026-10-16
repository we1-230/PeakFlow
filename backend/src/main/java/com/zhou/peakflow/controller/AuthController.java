package com.zhou.peakflow.controller;

import com.zhou.peakflow.dto.user.UpdateUserPasswordDto;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.pojo.CaptchaVO;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.dto.auth.LoginDto;
import com.zhou.peakflow.dto.auth.LoginResponseDto;
import com.zhou.peakflow.dto.auth.RegisterDto;
import com.zhou.peakflow.service.UserService;
import com.zhou.peakflow.utils.IpUtil;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import com.zhou.peakflow.utils.RedisUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.zhou.peakflow.constant.CacheExpireConstants.CAPTCHA_EXPIRE_SECOND;
import static com.zhou.peakflow.constant.RedisKeyConstant.CAPTCHA_REDIS_PREFIX;
import static com.zhou.peakflow.enums.ResultCode.TOKEN_INVALID;
import static com.zhou.peakflow.enums.ResultCode.USER_LOGIN_OUT;
import static java.util.UUID.randomUUID;

/**
 * 认证控制器
 */
@Slf4j
@RequestMapping("/auth")
@RequiredArgsConstructor
@RestController
public class AuthController extends BaseController{

    private final UserService userService;

    @Resource
    private RedisUtils redisUtil;

    // 图片宽高
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    // 验证码字符集
    private static final char[] CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();



    @PostMapping("/register")
    public Result<LoginResponseDto> register(@RequestBody @Valid RegisterDto registerDto, HttpServletRequest request) {
        log.info("收到注册请求，username={}, email={}", registerDto.getUsername(), registerDto.getEmail());

        LoginResponseDto result = userService.register(registerDto);
        return Result.success("注册成功", result);
    }

    @PostMapping("/login")
    public Result<LoginResponseDto> login(@RequestBody @Valid LoginDto loginDto,
                                          HttpServletRequest request) {
        log.info("收到登录请求，account={}", loginDto.getAccount());

        String ip = IpUtil.getClientIp(request);

        LoginResponseDto loginResponseDto = userService.login(loginDto, ip);

        log.info("登录成功，userId={}, username={}",
                loginResponseDto.getUser().getId(),
                loginResponseDto.getUser().getUsername());

        return Result.success("登录成功", loginResponseDto);
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String token) {
        log.info("收到登出请求");

        Long userId = getUserIdFromToken(token);

        try {
            userService.logout(token);
            return Result.success("登出成功");
        } catch (Exception e) {
            log.error("登出异常", e);
            return Result.error(USER_LOGIN_OUT.getCode(),"登出失败");
        }
    }

    @PostMapping("/refresh")
    public Result<LoginResponseDto> refreshToken(@RequestHeader("Authorization") String token) {
        log.info("收到刷新Token请求");

        Long userId = getUserIdFromToken(token);

       

        try {
            LoginResponseDto response = userService.refreshToken(token);
            return Result.success("刷新成功", response);
        } catch (Exception e) {
            log.error("刷新Token异常", e);
            return Result.error(TOKEN_INVALID.getCode(),e.getMessage());
        }
    }

    @PostMapping("/changePassword")
    public Result<String> changePassword(@RequestBody @Valid UpdateUserPasswordDto updateUserPasswordDto) {
        log.info("收到强制修改密码请求，username={}", updateUserPasswordDto.getUsername());

        // 越权防护：该接口按 username 定位账号，必须限定调用者。
        //   1) 本人（登录态用户名 == 目标用户名）—— 重置密码后用户自己改密；
        //   2) 或持有 user:manage 权限的管理员 —— 代用户重置。
        // 否则任意登录用户都能改别人的密码。
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String operator = authentication == null ? null : authentication.getName();
        boolean isSelf = operator != null && operator.equals(updateUserPasswordDto.getUsername());
        boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "user:manage".equals(a.getAuthority()));
        if (!isSelf && !isAdmin) {
            log.warn("强制修改密码越权被拒：operator={}, target={}", operator, updateUserPasswordDto.getUsername());
            return Result.error(ResultCode.FORBIDDEN.getCode(), "无权修改其他用户的密码");
        }

        userService.forceChangePassword(updateUserPasswordDto);

        log.info("强制修改密码成功，username={}", updateUserPasswordDto.getUsername());
        return Result.success("修改成功，请重新登录");
    }

    @GetMapping("/image")
    public Result<CaptchaVO> getCaptcha(HttpServletRequest request) {
        String code = generateCode(4);

        // uuid 拼进 key：同浏览器多次获取互不覆盖，校验时也按 uuid 精确定位
        String captchaUuid = UUID.randomUUID().toString();
        String redisKey = CAPTCHA_REDIS_PREFIX + captchaUuid;

        redisUtil.set(redisKey, code, CAPTCHA_EXPIRE_SECOND, TimeUnit.SECONDS);

        String base64Image = generateImageBase64(code);

        CaptchaVO vo = new CaptchaVO();
        vo.setCaptchaUuid(captchaUuid);
        vo.setImageBase64(base64Image);

        return Result.success(vo);
    }
    
    private String generateImageBase64(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        // 背景
        g.setColor(Color.WHITE);
        g.fillRect(0,0,WIDTH,HEIGHT);

        // 画干扰线
        for (int i = 0; i < 8; i++) {
            g.setColor(getRandomColor());
            int x1 = (int)(Math.random()*WIDTH);
            int y1 = (int)(Math.random()*HEIGHT);
            int x2 = (int)(Math.random()*WIDTH);
            int y2 = (int)(Math.random()*HEIGHT);
            g.drawLine(x1,y1,x2,y2);
        }

        // 绘制验证码文字
        g.setFont(new Font("Arial", Font.BOLD,28));
        for(int i=0;i<code.length();i++){
            g.setColor(getRandomColor());
            g.drawString(String.valueOf(code.charAt(i)), 10 + i*25, 28);
        }
        g.dispose();

        try(ByteArrayOutputStream bos = new ByteArrayOutputStream()){
            ImageIO.write(image,"png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        }catch (Exception e){
            throw new RuntimeException("生成验证码失败");
        }
    }

    private Color getRandomColor(){
        int r = (int)(Math.random()*180);
        int g = (int)(Math.random()*180);
        int b = (int)(Math.random()*180);
        return new Color(r,g,b);
    }

    private String generateCode(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int idx = (int) (Math.random() * CHARS.length);
            sb.append(CHARS[idx]);
        }
        return sb.toString();
    }
}



