package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.pojo.UserLoginLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户登录日志 Mapper（仅用 MP 基础能力；写入走 UserMapper.insertLoginLog）
 */
@Mapper
public interface UserLoginLogMapper extends BaseMapper<UserLoginLog> {
}
