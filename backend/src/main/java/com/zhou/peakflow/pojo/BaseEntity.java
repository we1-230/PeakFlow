package com.zhou.peakflow.pojo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实体基类：统一维护创建时间与更新时间（由 MyMetaObjectHandler 自动填充）
 */
@Data
public abstract class BaseEntity {

    // 创建时间（INSERT 时自动填充）
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    // 更新时间（INSERT / UPDATE 时自动填充）
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

}