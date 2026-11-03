package com.zhou.peakflow.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 秒杀抢购结果（含轮询状态码与静态工厂） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResultVo {

    /** 状态：0-排队中，1-成功，2-失败 */
    private Integer status;

    /** 订单 ID（成功时返回） */
    private Long orderId;

    /** 订单号（成功时返回） */
    private String orderNo;

    /** 失败原因 */
    private String failReason;

    public static final int STATUS_WAITING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAIL = 2;

    public static SeckillResultVo waiting() {
        return SeckillResultVo.builder().status(STATUS_WAITING).build();
    }

    public static SeckillResultVo success(Long orderId, String orderNo) {
        return SeckillResultVo.builder()
                .status(STATUS_SUCCESS)
                .orderId(orderId)
                .orderNo(orderNo)
                .build();
    }

    public static SeckillResultVo fail(String reason) {
        return SeckillResultVo.builder()
                .status(STATUS_FAIL)
                .failReason(reason)
                .build();
    }
}