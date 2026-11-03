package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.pojo.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 商品表 Mapper：普通商品 CRUD 用乐观锁；库存类更新额外带充足性校验，防超卖与并发覆盖
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    // ---- 普通商品（乐观锁） ----

    // 扣减普通库存：WHERE 同时校验版本、库存充足、上架、非秒杀、未删除，一条 SQL 完成并发安全扣减
    @Update("UPDATE product " +
            "SET stock = stock - #{quantity}, version = version + 1 " +
            "WHERE id = #{productId} " +
            "AND version = #{version} " +
            "AND stock >= #{quantity} " +
            "AND status = 1 " +
            "AND is_seckill = 0 " +
            "AND deleted = 0")
    int deductStockWithVersion(@Param("productId") Long productId,
                               @Param("quantity") Integer quantity,
                               @Param("version") Integer version);

    // 管理员编辑商品：乐观锁更新全字段
    // 注意这里必须覆盖 seckill_start_time / seckill_end_time，否则秒杀商品改了活动时间不会生效；
    // 同理 detail 之前漏了，导致「改了商品详情保存后没变」。
    @Update("UPDATE product SET name=#{name}, detail=#{detail}, price=#{price}, " +
            "seckill_price=#{seckillPrice}, stock=#{stock}, seckill_stock=#{seckillStock}, " +
            "seckill_start_time=#{seckillStartTime}, seckill_end_time=#{seckillEndTime}, " +
            "description=#{description}, image=#{image}, category_id=#{categoryId}, " +
            "status=#{status}, version=version+1, update_time=NOW() " +
            "WHERE id=#{id} AND version=#{version}")
    int updateProductWithOptimisticLock(Product product);

    // 上下架：乐观锁 + 旧状态条件，且不允许操作秒杀商品
    @Update("UPDATE product " +
            "SET status = #{newStatus}, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} " +
            "AND status = #{oldStatus} " +
            "AND version = #{version} " +
            "AND is_seckill = 0 " +
            "AND deleted = 0")
    int updateStatusWithVersion(@Param("id") Long id,
                                @Param("oldStatus") Integer oldStatus,
                                @Param("newStatus") Integer newStatus,
                                @Param("version") Integer version);

    // 逻辑删除：乐观锁，且仅允许删除已下架、非秒杀的商品
    @Update("UPDATE product " +
            "SET deleted = 1, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} " +
            "AND version = #{version} " +
            "AND status = 0 " +
            "AND is_seckill = 0 " +
            "AND deleted = 0")
    int deleteProductWithVersion(@Param("id") Long id, @Param("version") Integer version);

    // 回补普通库存（订单取消 / 超时关单 / 退款时调用）
    @Update("UPDATE product SET stock = stock + #{quantity} WHERE id = #{productId}")
    int rollbackStock(@Param("productId") Long productId,
                      @Param("quantity") Integer quantity);

    // ---- 秒杀库存 ----

    // 秒杀库存扣减：并发由 Redis 预扣 + MQ 单消费者保证，DB 侧用 seckill_stock > 0 做最终兜底
    @Update("UPDATE product SET seckill_stock = seckill_stock - 1, " +
            "sold_count = sold_count + 1 " +
            "WHERE id = #{productId} AND seckill_stock > 0 AND deleted = 0")
    int decreaseSeckillStockDirect(@Param("productId") Long productId);

    // 秒杀库存 +1、销量 -1（单条异常补偿）
    @Update("UPDATE product SET seckill_stock = seckill_stock + 1, " +
            "sold_count = sold_count - 1 " +
            "WHERE id = #{productId} AND deleted = 0")
    int rollbackSeckillStockDb(@Param("productId") Long productId);

    // 按数量回滚秒杀库存；必须同步回退销量，否则"已售"数字只会越积越高、再也降不下来
    @Update("UPDATE product SET seckill_stock = seckill_stock + #{quantity}, " +
            "sold_count = GREATEST(sold_count - #{quantity}, 0) " +
            "WHERE id = #{productId} AND deleted = 0")
    int rollbackSeckillStockByQuantity(@Param("productId") Long productId,
                                       @Param("quantity") Integer quantity);

    // ---- 后台统计 ----

    // 商品总数（未删除，含秒杀商品）
    @Select("SELECT COUNT(*) FROM product WHERE deleted = 0")
    Long countAllProducts();

    // 秒杀商品数
    @Select("SELECT COUNT(*) FROM product WHERE is_seckill = 1 AND deleted = 0")
    Long countSeckillProducts();
}
