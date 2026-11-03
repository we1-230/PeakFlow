package com.zhou.peakflow.dto.order;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新订单DTO（管理员用）
 */
@Data
public class UpdateOrderDto {

    // 订单ID
    @NotNull(message = "订单ID不能为空")
    @Min(value = 1, message = "订单ID无效")
    private Long id;

    // 订单状态
    private Integer status;

    // 物流单号
    private String trackingNo;

    // 物流公司
    private String logisticsCompany;

    // 备注
    private String remark;
}