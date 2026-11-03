package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.pojo.OrderOperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单操作日志 Mapper（管理员对订单的操作留痕，仅用 MP 基础能力）
 */
@Mapper
public interface OrderOperationLogMapper extends BaseMapper<OrderOperationLog> {
}