package com.zhou.peakflow.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 收货地址实体类
 */
@Data
@TableName("address")
public class Address extends BaseEntity{

    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID（关联user表）
    private Long userId;

    // 收货人姓名
    private String receiverName;

    // 收货人电话
    private String receiverPhone;

    // 省份
    private String province;

    // 城市
    private String city;

    // 区/县
    private String district;

    // 详细地址
    private String detailAddress;

    // 是否默认地址（0-否，1-是）
    private Integer isDefault;

    /**
     * 逻辑删除标记（0-未删除，1-已删除）。
     *
     * 这个字段必须声明，否则 MyBatis-Plus 的逻辑删除不生效 ——
     * {@code AddressServiceImpl.deleteAddress} 用的是 {@code removeById}，
     * 实体没有该字段时会退化成物理 DELETE；而 {@code order.address_id}
     * 上有外键 {@code fk_order_address_id}（NO ACTION），
     * 一旦该地址被任何一笔历史订单引用，删除就会抛外键约束异常，
     * 并被全局异常处理器翻译成「操作失败，该数据已存在，请勿重复操作」——提示完全误导。
     *
     * 数据库侧早就为逻辑删除准备好了：{@code address} 表有 {@code deleted} 列，
     * 唯一索引 {@code uk_user_address} 也把 {@code deleted} 包含在内
     * （这样"删掉旧地址后可以再建同名地址"才成立）。
     * 是实体漏了这个字段，不是表设计有问题。
     */
    @TableLogic
    private Integer deleted;

}