package com.zhou.peakflow.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("`order`")
public class Order extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 订单号（唯一）
    private String orderNo;

    // 用户ID
    private Long userId;

    // 地址ID（引用，用于关联查询）
    private Long addressId;

    // ---- 地址快照 ----

    // 收货人姓名（快照）
    private String receiverName;

    // 收货人电话（快照）
    private String receiverPhone;

    // 省份（快照）
    private String receiverProvince;

    // 城市（快照）
    private String receiverCity;

    // 区/县（快照）
    private String receiverDistrict;

    // 详细地址（快照）
    private String receiverDetailAddress;

    // ---- 订单信息 ----

    // 订单总金额
    private BigDecimal totalAmount;

    // 实际支付金额（优惠后）
    private BigDecimal payAmount;

    // 订单状态
    private Integer status;

    // 支付时间
    private LocalDateTime payTime;

    // 发货时间
    private LocalDateTime shippingTime;

    // 完成时间
    private LocalDateTime completeTime;

    // 取消时间
    private LocalDateTime cancelTime;

    // 退款时间
    private LocalDateTime refundTime;

    // 退款金额
    private BigDecimal refundAmount;

    // 物流单号
    private String trackingNo;

    // 物流公司
    private String logisticsCompany;

    // 用户备注
    private String remark;

    // 订单类型：0-普通订单，1-秒杀订单
    private Integer orderType;

    // 是否秒杀订单（冗余字段，便于查询）
    private Integer isSeckill;

    /**
     * 秒杀商品 ID（仅秒杀订单填充；普通订单为 NULL）。
     *
     * 用途：配合 {@code seckill_slot} 生成列与唯一索引 {@code uk_seckill_user_product}，
     * 在数据库层兜住「一人一单」——不依赖 Redis 的限购 Key
     * （Key 会因 Redis 重启 / 被清空 / 24 小时 TTL 过期而失效）。
     * 普通订单该列为 NULL，而不含 NULL 的唯一索引不参与判重，所以互不影响。
     *
     * DDL 见 {@code sql/seckill_limit_per_user_uk.sql}。
     */
    private Long seckillProductId;

    // 乐观锁版本号
    @Version
    private Integer version;
}