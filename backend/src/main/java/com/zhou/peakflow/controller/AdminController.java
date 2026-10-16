package com.zhou.peakflow.controller;

import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.dto.user.AdminUserPageDto;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.CategoryMapper;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.ProductMapper;
import com.zhou.peakflow.mapper.UserMapper;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.pojo.User;
import com.zhou.peakflow.service.UserService;
import com.zhou.peakflow.vo.DashboardStatsVo;
import com.zhou.peakflow.vo.PageVo;
import com.zhou.peakflow.utils.JwtUtil;
import javax.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理员控制器
 */
@Slf4j
@Data
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController extends BaseController{

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final OrderMapper orderMapper;

    // 查询用户列表（分页）
    @PreAuthorize("hasAuthority('user:manage')")
    @PostMapping("/list")
    public Result<PageVo<User>> listUser(@RequestBody @Valid AdminUserPageDto adminUserPageDto) {
        log.info("管理员查询用户列表，pageNum={}, pageSize={}",
                adminUserPageDto.getPageNum(), adminUserPageDto.getPageSize());

        PageVo<User> userPageVo = userService.listUsers(adminUserPageDto);
        return Result.success(userPageVo);
    }

    @PreAuthorize("hasAuthority('user:manage')")
    @PostMapping("/ban")
    public Result<Void> banUser(@RequestHeader("Authorization") String token,
                                @RequestParam long userId) {
        log.info("管理员封号请求，userId={}", userId);

        if (userId <= 0) {
            throw new BusinessException("被封用户不能为空");
        }

        userService.banUser(userId);
        return Result.success("封号成功");
    }

    @PreAuthorize("hasAuthority('user:manage')")
    @PostMapping("/unban")
    public Result<Void> unbanUser(@RequestHeader("Authorization") String token,
                                  @RequestParam long userId) {
        log.info("管理员解封请求，userId={}", userId);

        if (userId <= 0) {
            throw new BusinessException("解封用户不能为空");
        }

        userService.unbanUser(userId);
        return Result.success("解封成功");
    }

    @PreAuthorize("hasAuthority('user:manage')")
    @PostMapping("/resetPassword")
    public Result<String> resetPassword(@RequestHeader("Authorization") String token,
                                        @RequestParam long userId) {
        log.info("管理员重置密码请求，userId={}", userId);

        if (userId <= 0) {
            throw new BusinessException("用户ID无效");
        }

        String newPassword = userService.resetPassword(userId);
        // 注意：必须用 success(message, data) 重载，否则 Result.success(String) 会把密码当 message 返回
        return Result.success("密码重置成功，请尽快告知用户", newPassword);
    }

    // 后台仪表盘统计（总览数据）
    @PreAuthorize("hasAuthority('stats:view')")
    @GetMapping("/dashboard/stats")
    public Result<DashboardStatsVo> dashboardStats() {
        DashboardStatsVo vo = new DashboardStatsVo();

        Long userTotal = userMapper.countAllUsers();
        Long userActive = userMapper.countActiveUsers();
        vo.setUserTotal(userTotal);
        vo.setUserActive(userActive);
        vo.setUserBanned(userTotal == null ? 0L : userTotal - (userActive == null ? 0L : userActive));

        vo.setProductTotal(productMapper.countAllProducts());
        vo.setSeckillTotal(productMapper.countSeckillProducts());
        vo.setCategoryTotal(categoryMapper.countAllCategories());

        vo.setOrderTotal(orderMapper.countAllOrders());
        vo.setGmv(orderMapper.sumGmv());
        vo.setTodayOrders(orderMapper.countTodayOrders());
        vo.setTodayGmv(orderMapper.sumTodayGmv());

        // 订单状态分布
        List<Map<String, Object>> dist = orderMapper.selectOrderStatusDist();
        List<DashboardStatsVo.OrderStatusDist> distList = new ArrayList<>();
        if (dist != null) {
            for (Map<String, Object> row : dist) {
                DashboardStatsVo.OrderStatusDist item = new DashboardStatsVo.OrderStatusDist();
                Integer st = ((Number) row.get("status")).intValue();
                item.setStatus(st);
                OrderStatus os = OrderStatus.fromCode(st);
                item.setStatusDesc(os == null ? "未知" : os.getDesc());
                item.setCount(((Number) row.get("cnt")).longValue());
                distList.add(item);
            }
        }
        vo.setOrderStatusDist(distList);

        // 近7日趋势
        List<Map<String, Object>> trend = orderMapper.selectTrend7();
        List<DashboardStatsVo.TrendItem> trendList = new ArrayList<>();
        if (trend != null) {
            for (Map<String, Object> row : trend) {
                DashboardStatsVo.TrendItem item = new DashboardStatsVo.TrendItem();
                Object d = row.get("d");
                item.setDate(d == null ? "" : d.toString());
                item.setCount(((Number) row.get("cnt")).longValue());
                Object amt = row.get("amt");
                item.setAmount(amt == null ? BigDecimal.ZERO : new BigDecimal(amt.toString()));
                trendList.add(item);
            }
        }
        vo.setTrend7(trendList);

        return Result.success(vo);
    }

    // 管理员限流检查
    private void checkAdminRateLimit(String token) {
        // 从 Token 中获取管理员 ID（先去掉 "Bearer " 前缀，避免 parseToken 报 token无效）
        Long adminId = jwtUtil.getUserIdFromToken(jwtUtil.removeTokenPrefix(token));

    }
}
