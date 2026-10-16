package com.zhou.peakflow.service;

import com.zhou.peakflow.dto.order.SeckillOrderDto;
import com.zhou.peakflow.dto.product.AddSeckillProductDto;
import com.zhou.peakflow.dto.product.SelectProductDto;
import com.zhou.peakflow.dto.product.UpdateSeckillProductDto;
import com.zhou.peakflow.pojo.Product;
import com.zhou.peakflow.vo.PageVo;
import com.zhou.peakflow.vo.SeckillResultVo;

/**
 * 秒杀订单服务接口
 */
public interface SeckillOrderService {

    // 秒杀下单（异步落库，立即返回排队标识）
    String seckill(Long userId, SeckillOrderDto seckillOrderDto);

    // 查询秒杀结果（无结果时返回排队中）
    SeckillResultVo getSeckillResult(String requestId);

    // 查询秒杀商品列表（分页 + 缓存）
    PageVo<Product> getSeckillProducts(SelectProductDto dto);

    // 获取秒杀商品详情（缓存 + Redis 实时库存）
    Product getProductDetail(Long productId);

    // 新增秒杀商品（管理员）—— 用秒杀域专用 DTO，不复用普通商品的 AddProductDto
    void addProduct(AddSeckillProductDto dto);

    // 更新秒杀商品（管理员）—— 用秒杀域专用 DTO，不复用普通商品的 UpdateProductDto
    void updateProduct(UpdateSeckillProductDto dto);

    // 删除秒杀商品（管理员）
    void deleteProduct(Long id);

    // 上下架秒杀商品（管理员）
    void updateStatus(Long id, Integer status);

    // 回补 Redis 秒杀库存预扣（订单取消/超时关单/退款时由订单服务调用）
    void rollbackRedisStock(Long productId, Integer quantity);

    // 秒杀全量缓存预热（列表 + 详情 + 库存）
    void preheatAllSeckillCache();
}
