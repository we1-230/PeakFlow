package com.zhou.peakflow.service;

import com.zhou.peakflow.pojo.Cart;
import java.util.List;

/**
 * 购物车服务：纯 Redis 存储（高频读写、弱一致性数据，最终一致由下单时的库存扣减保证）
 */
public interface CartService {

    // 加入购物车：HINCRBY 原子累加，超过库存则回滚本次增量
    void addToCart(Long userId, Long productId, Integer quantity);

    // 修改某商品的数量（覆盖为目标值）
    void updateQuantity(Long userId, Long productId, Integer quantity);

    // 删除购物车中的单个商品
    void removeFromCart(Long userId, Long productId);

    // 清空购物车
    void clearCart(Long userId);

    // 获取购物车列表：读取时自动剔除并清理失效商品（已删除 / 已下架 / 库存不足）
    List<Cart> getCartList(Long userId);

    // 批量移除商品（下单成功后清理）
    void removeProductsFromCart(Long userId, List<Long> productIds);

    // 设置某商品的选中态（selected：0-取消选中，1-选中）
    void selectProduct(Long userId, Long productId, Integer selected);

    // 获取已选中的商品（结算用）；购物车默认全选，手动取消的会被剔除
    List<Cart> getSelectedProducts(Long userId);
}
