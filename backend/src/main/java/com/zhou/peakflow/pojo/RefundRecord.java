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
 * 退款单实体（表 refund_record）：与订单解耦，独立承载退款 / 售后的状态流转与对账
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("refund_record")
public class RefundRecord extends BaseEntity {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 商户退款单号（我方生成），唯一索引 uk_refund_no，是幂等与重试的主键
    private String refundNo;

    // 关联的业务订单 ID
    private Long orderId;

    // 关联的业务订单号（冗余，便于列表直接展示）
    private String orderNo;

    // 申请退款的用户 ID
    private Long userId;

    // 原支付单的商户订单号；退款必须能追溯到"哪一笔收款"，对账链路不能断
    private String outTradeNo;

    // 第三方退款流水号（退款成功后才会有值，是对账的凭据）
    private String refundTradeNo;

    // 退款金额（本实现为全额退款，取原支付单金额）
    private BigDecimal refundAmount;

    // 退款类型：1-仅退款 2-退货退款，见 RefundType；由服务端按订单状态推导，客户端不可指定
    private Integer refundType;

    // 退款渠道，与支付渠道对应（MOCK_PAY / ALIPAY / WECHAT）
    private String refundChannel;

    // 用户填写的退款原因
    private String reason;

    // ---- 退货信息（仅退货退款有值） ----

    // 退货物流公司（退货退款必填）
    private String returnLogisticsCompany;

    // 退货物流单号（退货退款必填）
    private String returnTrackingNo;

    // 商家签收退货的时间：退货退款的放款闸门，非空才允许放款并回补库存
    private LocalDateTime returnReceivedTime;

    // 退款单状态，见 RefundStatus
    private Integer status;

    // ---- 审核留痕 ----

    // 审核人 ID（自动审核时为空）
    private Long auditorId;

    // 审核人姓名
    private String auditorName;

    // 审核时间
    private LocalDateTime auditTime;

    // 审核意见（驳回时必填，直接展示给用户）
    private String auditRemark;

    // 退款失败原因
    private String failReason;

    // 申请时间
    private LocalDateTime applyTime;

    // 退款完成时间（第三方确认成功的时间）
    private LocalDateTime refundTime;

    // 第三方退款回调报文原文（留档，便于排查与对账）
    private String notifyContent;

    // 乐观锁版本号
    @Version
    private Integer version;
}
