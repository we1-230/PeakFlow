package com.zhou.peakflow.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单操作日志实体
 */
@Data
@TableName("order_operation_log")
public class OrderOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 订单ID
    private Long orderId;

    // 订单号
    private String orderNo;

    // 操作人ID
    private Long operatorId;

    // 操作人姓名
    private String operatorName;

    // 操作类型：发货、取消、退款等
    private String operation;

    // 变更前状态
    private Integer oldStatus;

    // 变更后状态
    private Integer newStatus;

    // 备注（如物流单号）
    private String remark;

    // 创建时间
    private LocalDateTime createTime;
}