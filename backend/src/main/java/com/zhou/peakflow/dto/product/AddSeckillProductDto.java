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
 * 秒杀商品新增 DTO（秒杀域专用，与普通商品的 AddProductDto 隔离）。
 * 必须单独一个 DTO 而非复用普通商品 DTO：秒杀价 / 秒杀库存 / 秒杀起止时间三个字段普通 DTO 里没有，
 * 复用会收不到值（历史上就出现过秒杀价与时间写入 NULL）；而普通库存秒杀又不需要，字段集合本就不同。
 * 一个 DTO 被两个域共用时，任何一方加字段都会污染另一方，是典型的高耦合温床。
 */
@Data
public class AddSeckillProductDto {

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

    /** 商品原价（用于展示划线价与折扣） */
    @NotNull(message = "商品原价不能为空")
    @DecimalMin(value = "0.01", message = "商品原价必须大于0")
    private BigDecimal price;

    /** 秒杀价（实际成交价，必须不高于原价） */
    @NotNull(message = "秒杀价格不能为空")
    @DecimalMin(value = "0.01", message = "秒杀价格必须大于0")
    private BigDecimal seckillPrice;

    /** 秒杀库存（秒杀可售数量，上架时会同步初始化到 Redis） */
    @NotNull(message = "秒杀库存不能为空")
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
}

