package com.zhou.peakflow.pojo;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户登录日志实体（表 login_log，无逻辑删除字段）
 */
@Data
@TableName("login_log")
public class UserLoginLog {

    // 主键
    private Long id;

    // 用户 ID
    private Long userId;

    // 用户名（冗余，便于按用户名直接查询）
    private String username;

    // 登录 IP
    private String ip;

    // 登录时间
    private String loginTime;

    // 备注（如"登录成功"）
    private String remark;
}
