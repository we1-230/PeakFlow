package com.zhou.peakflow.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhou.peakflow.dto.product.AddProductDto;
import com.zhou.peakflow.dto.product.UpdateProductDto;
import com.zhou.peakflow.pojo.Product;
import com.zhou.peakflow.vo.PageVo;

/**
 * 商品服务：读写分离的缓存策略，写操作统一带乐观锁并清理相关缓存
 */
public interface ProductService extends IService<Product> {

    // 分页查询商品（首页展示）：缓存 + 分布式锁 + 空值缓存防穿透
    PageVo<Product> pageQuery(Integer pageNum, Integer pageSize, Long categoryId, String keyword);

    // 与列表同策略：缓存 + 分布式锁 + 空值缓存（防穿透 / 防击穿 / 防雪崩）
    Product getProductDetail(Long id);

    // 管理员新增商品，成功后清理分页缓存
    void addProduct(AddProductDto dto);

    // 管理员更新商品（乐观锁），成功后清理分页与详情缓存
    void updateProduct(UpdateProductDto dto);

    // 管理员删除商品（须先下架），成功后清理分页与详情缓存
    void deleteProduct(Long id);

    // 上下架商品（乐观锁），成功后清理分页缓存
    void updateStatus(Long id, Integer status);

    // 清空所有商品分页缓存
    void clearProductListCache();

    // 写入商品详情缓存（过期时间加随机值防雪崩）
    void addProductDetailCache(Long id, Product product);

    // 清除指定商品的详情缓存
    void clearProductDetailCache(Long id);

    // 乐观锁扣减库存，返回是否扣减成功
    boolean deductStockWithOptimisticLock(Long productId, Integer quantity, Integer version);
}
