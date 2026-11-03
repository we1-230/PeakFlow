package com.zhou.peakflow.dto.order;

import com.zhou.peakflow.dto.page.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单查询DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderQueryDto extends PageRequest {

    // 订单号（精确）
    private String orderNo;

    // 用户ID（精确）
    private Long userId;

    // 用户名/手机号（模糊）
    private String userKeyword;

    // 订单状态：0-待付款，1-已付款，2-已发货，3-已完成，4-已取消，5-已关闭，6-已退款
    private Integer status;

    // 订单类型：0-普通订单，1-秒杀订单
    private Integer orderType;

    // 是否秒杀订单：0-否，1-是
    private Integer isSeckill;

    // 创建时间起（含）
    private LocalDateTime createTimeStart;

    // 创建时间止（含）
    private LocalDateTime createTimeEnd;

    // 金额起（含）
    private BigDecimal amountStart;

    // 金额止（含）
    private BigDecimal amountEnd;
}