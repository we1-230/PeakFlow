package com.zhou.peakflow.dto.product;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static com.zhou.peakflow.constant.RegularPatterns.PRODUCT_NAME;
import static com.zhou.peakflow.constant.RegularPatterns.URL;

/**
 * 秒杀商品修改 DTO（秒杀域专用，与普通商品的 UpdateProductDto 隔离）。
 * 不含 status：上下架统一走 PUT /seckill/status/{id}，那条链路有「有未完成订单不许下架」的校验，
 * 编辑接口若能改状态就绕过了它。seckillStock / version 可选：前者不传则保留库中原值，避免被
 * BeanUtils 覆写成 NULL 导致商品从列表消失；后者带上才做乐观锁，不带回落当前版本，不强制升级调用方。
 */
@Data
public class UpdateSeckillProductDto {

    /** 商品 ID */
    @NotNull(message = "商品ID不能为空")
    @Min(value = 1, message = "商品ID必须大于0")
    private Long id;

    /** 商品名称 */
    @NotBlank(message = "商品名称不能为空")
    @Pattern(regexp = PRODUCT_NAME, message = "商品名称只能包含中文、字母、数字、下划线、短横线，长度2-30位")
    private String name;

    /** 商品描述 */
    @Size(max = 200, message = "商品描述长度不能超过200个字符")
    private String description;

    /** 商品详情 */
    @Size(max = 2000, message = "商品详情长度不能超过2000个字符")
    private String detail;

    /** 商品主图 URL */
    @NotBlank(message = "商品图片不能为空")
    @Pattern(regexp = URL, message = "商品图片必须是合法的http/https链接")
    private String image;

    /** 商品原价 */
    @NotNull(message = "商品原价不能为空")
    @DecimalMin(value = "0.01", message = "商品原价必须大于0")
    private BigDecimal price;

    /** 秒杀价（必须不高于原价） */
    @NotNull(message = "秒杀价格不能为空")
    @DecimalMin(value = "0.01", message = "秒杀价格必须大于0")
    private BigDecimal seckillPrice;

    /** 秒杀库存（可选；传了就调整为该值，并同步 Redis） */
    @Min(value = 0, message = "秒杀库存不能小于0")
    private Integer seckillStock;

    /** 商品分类 ID */
    @NotNull(message = "商品分类ID不能为空")
    @Min(value = 1, message = "商品分类ID必须大于0")
    private Long categoryId;

    /** 秒杀开始时间 */
    @NotNull(message = "秒杀开始时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime seckillStartTime;

    /** 秒杀结束时间 */
    @NotNull(message = "秒杀结束时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime seckillEndTime;

    /** 乐观锁版本号（可选，前端从列表行数据带入） */
    private Integer version;
}

