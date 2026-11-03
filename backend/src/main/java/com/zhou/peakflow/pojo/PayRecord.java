package com.zhou.peakflow.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水实体（对应表 {@code pay_record}）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_record")
public class PayRecord extends BaseEntity {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 商户订单号（我方生成）
    private String outTradeNo;

    // 第三方交易号（第三方生成）
    private String tradeNo;

    // 关联的业务订单 ID
    private Long orderId;

    // 关联的业务订单号（冗余，便于对账时直接展示）
    private String orderNo;

    // 付款用户 ID
    private Long userId;

    // 应付金额（下单时快照）
    private BigDecimal amount;

    // 支付渠道，如 MOCK_PAY（模拟支付）、ALIPAY、WECHAT
    private String payChannel;

    // 支付单状态，取值见 {@link com.zhou.peakflow.enums.PayStatus}
    private Integer status;

    // 第三方回调时间
    private LocalDateTime notifyTime;

    // 第三方回调原文（留档，便于事后排查/对账）
    private String notifyContent;

    // 乐观锁版本号
    @Version
    private Integer version;
}
