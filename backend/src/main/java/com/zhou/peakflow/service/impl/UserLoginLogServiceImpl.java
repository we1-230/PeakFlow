package com.zhou.peakflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhou.peakflow.dto.user.AdminUserPageDto;
import com.zhou.peakflow.mapper.UserLoginLogMapper;
import com.zhou.peakflow.pojo.UserLoginLog;
import com.zhou.peakflow.utils.JwtUtil;
import com.zhou.peakflow.vo.PageVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 登录日志服务实现：查询口径固定为「当前 token 所属用户」，不提供跨用户查询
 */
@Service
@RequiredArgsConstructor
public class UserLoginLogServiceImpl extends ServiceImpl<UserLoginLogMapper, UserLoginLog> implements com.zhou.peakflow.service.UserLoginLogService {

    private final JwtUtil jwtUtil;

    @Override
    public PageVo<UserLoginLog> getUserLoginLog(String token, AdminUserPageDto adminUserPageDto) {
        // 只按 token 里的 userId 过滤，忽略入参中的用户维度，避免越权查看他人日志
        long userid = jwtUtil.getUserIdFromToken(token);
        int pageNum = adminUserPageDto.getPageNum();
        int pageSize = adminUserPageDto.getPageSize();
        Page<UserLoginLog> page = new Page<>(pageNum, pageSize);
        QueryWrapper<UserLoginLog> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userid);
        queryWrapper.orderByDesc("login_time");
        Page<UserLoginLog> p = this.page(page, queryWrapper);
        List<UserLoginLog> list = p.getRecords();
        PageVo<UserLoginLog> pageVo = listUsers(p);
        return pageVo;
    }

    public <T> PageVo<T> listUsers(Page<T> p) {
        List<T> userList = p.getRecords();
        long total = p.getTotal();
        long pageNum = p.getCurrent();
        long pages = p.getPages();
        long pageSize = p.getSize();
        if (total == 0) {
            PageVo<T> pageVo = new PageVo<>(null, total, pageNum, pages, pageSize);
            return pageVo;
        }
        PageVo<T> pageVo = new PageVo<>(userList, total, pageNum, pages, pageSize);
        return pageVo;
    }
}


