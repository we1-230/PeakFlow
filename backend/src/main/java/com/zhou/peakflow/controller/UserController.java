package com.zhou.peakflow.controller;

import com.zhou.peakflow.dto.user.AdminUserPageDto;
import com.zhou.peakflow.dto.user.ChangePasswordDto;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.dto.auth.UserInfoDto;
import com.zhou.peakflow.pojo.UserLoginLog;
import com.zhou.peakflow.service.UserLoginLogService;
import com.zhou.peakflow.service.UserService;
import com.zhou.peakflow.utils.JwtUtil;
import com.zhou.peakflow.vo.PageVo;
import javax.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.zhou.peakflow.constant.CommonConstant.TOKEN_PREFIX;
import static com.zhou.peakflow.enums.ResultCode.UNAUTHORIZED;

/**
 * 用户信息控制器
 */
@Slf4j
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController extends BaseController {

    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final UserLoginLogService userLoginLogService;


    // 读接口，不需要严格限流
    @GetMapping("/info")
    public Result<UserInfoDto> getUserInfo(@RequestHeader("Authorization") String token) {
        log.info("获取用户信息请求");

        if (token != null && token.startsWith(TOKEN_PREFIX)) {
            token = jwtUtil.removeTokenPrefix(token);
            Long userId = jwtUtil.getUserIdFromToken(token);

            UserInfoDto userInfo = userService.getUserInfo(userId);
            return Result.success(userInfo);
        }

        return Result.error(ResultCode.USER_NOT_LOGIN);
    }

    @PostMapping("/password")
    public Result<Void> changePassword(
            @RequestHeader("Authorization") String token,
            @Valid @RequestBody ChangePasswordDto changePasswordDto) {

        log.info("修改密码请求");

        if (token != null && token.startsWith(TOKEN_PREFIX)) {
            token = jwtUtil.removeTokenPrefix(token);
            Long userId = jwtUtil.getUserIdFromToken(token);
            String username = jwtUtil.getUsernameFromToken(token);

            boolean success = userService.changePassword(username,
                    changePasswordDto.getOldPassword(),
                    changePasswordDto.getNewPassword());

            if (success) {
                userService.logout(token);
                log.info("密码修改成功，username={}，已登出", username);
                return Result.success("密码修改成功，请重新登录");
            }

            return Result.error(ResultCode.USER_PASSWORD_ERROR);
        }

        throw new BusinessException("未登录");
    }

    @PostMapping("/avatar")
    public Result<String> changeAvatar(@RequestHeader("Authorization") String token,
                                       @RequestParam("file") MultipartFile file) {
        log.info("修改头像请求，文件大小={} bytes", file.getSize());

        if (token != null && token.startsWith(TOKEN_PREFIX)) {
            token = jwtUtil.removeTokenPrefix(token);
            Long userId = jwtUtil.getUserIdFromToken(token);

            String url = userService.changAvatar(token, file);
            return Result.success("上传头像成功", url);
        }

        throw new BusinessException(UNAUTHORIZED);
    }

    @PostMapping("/nickname")
    public Result<Void> changeNickname(@RequestHeader("Authorization") String token,
                                       @RequestParam("nickname") String nickname) {
        log.info("修改昵称请求，nickname={}", nickname);

        if (!StringUtils.hasText(nickname)) {
            throw new BusinessException("昵称不能为空");
        }

        if (token != null && token.startsWith(TOKEN_PREFIX)) {
            token = jwtUtil.removeTokenPrefix(token);
            Long userId = jwtUtil.getUserIdFromToken(token);

            userService.changeNickname(token, nickname);
            return Result.success("昵称修改成功");
        }

        throw new BusinessException(UNAUTHORIZED);
    }

    @PostMapping("/loginLog")
    public Result<PageVo<UserLoginLog>> getLoginLog(@RequestHeader("Authorization") String token,
                                                    @RequestBody @Valid AdminUserPageDto adminUserPageDto) {
        log.info("获取登录日志请求，pageNum={}, pageSize={}",
                adminUserPageDto.getPageNum(), adminUserPageDto.getPageSize());

        if (token != null && token.startsWith(TOKEN_PREFIX)) {
            token = jwtUtil.removeTokenPrefix(token);
            Long userId = jwtUtil.getUserIdFromToken(token);

            PageVo<UserLoginLog> userLoginLogs = userLoginLogService.getUserLoginLog(token, adminUserPageDto);
            return Result.success(userLoginLogs);
        }

        return Result.error(UNAUTHORIZED);
    }
}
