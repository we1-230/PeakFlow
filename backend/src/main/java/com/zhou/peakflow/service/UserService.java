package com.zhou.peakflow.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhou.peakflow.dto.auth.LoginDto;
import com.zhou.peakflow.dto.auth.LoginResponseDto;
import com.zhou.peakflow.dto.auth.RegisterDto;
import com.zhou.peakflow.dto.auth.UserInfoDto;
import com.zhou.peakflow.dto.user.AdminUserPageDto;
import com.zhou.peakflow.dto.user.UpdateUserPasswordDto;
import com.zhou.peakflow.pojo.User;
import com.zhou.peakflow.vo.PageVo;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户服务：注册登录、Token 管理、资料修改、管理员用户管控
 */
public interface UserService extends IService<User> {

    // 注册：唯一性校验通过后落库并直接签发 Token
    LoginResponseDto register(RegisterDto registerDto);

    // 登录：失败计数达上限锁号，失败 2 次起强制图形验证码
    LoginResponseDto login(LoginDto loginDto, String ip);

    // 登出：Token 加入黑名单并清除登录态缓存
    void logout(String token);

    // 刷新 Token：旧 Token 立即拉黑，防止并发复用
    LoginResponseDto refreshToken(String refreshToken);

    // 根据 ID 获取用户信息
    UserInfoDto getUserInfo(Long userId);

    // 校验旧密码后用 CAS 更新，防并发覆盖
    boolean changePassword(String username, String oldPassword, String newPassword);

    // 修改头像（multipart 上传到 OSS）
    String changAvatar(String token, MultipartFile file);

    boolean changeNickname(String token, String nickname);

    // 管理员：分页查询用户列表
    PageVo<User> listUsers(AdminUserPageDto adminUserPageDto);

    // 管理员：封号（CAS 更新状态并强制下线）
    void banUser(Long userId);

    // 管理员：解封
    void unbanUser(Long userId);

    // 管理员：重置为随机临时密码，并强制该用户下线
    String resetPassword(Long userId);

    // 用户：首登强制改密（清除临时密码标记）
    String forceChangePassword(UpdateUserPasswordDto updateUserPasswordDto);

    // 记录登录失败次数（达到上限则锁定账号）
    void recordLoginFail(String account);
}
