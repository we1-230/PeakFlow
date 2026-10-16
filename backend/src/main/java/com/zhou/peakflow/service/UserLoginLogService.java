package com.zhou.peakflow.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhou.peakflow.dto.user.AdminUserPageDto;
import com.zhou.peakflow.pojo.UserLoginLog;
import com.zhou.peakflow.vo.PageVo;

/**
 * 用户登录日志服务
 */
public interface UserLoginLogService extends IService<UserLoginLog> {

    // 查询登录日志：管理员可查任意用户，普通用户只能查自己的
    PageVo<UserLoginLog> getUserLoginLog(String token, AdminUserPageDto adminUserPageDto);
}
