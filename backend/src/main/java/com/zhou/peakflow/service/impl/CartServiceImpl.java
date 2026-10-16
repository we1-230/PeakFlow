package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.constant.BusinessConstants;
import com.zhou.peakflow.constant.CacheExpireConstants;
import com.zhou.peakflow.enums.ProductShelfStatus;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.Cart;
import com.zhou.peakflow.pojo.Product;
import com.zhou.peakflow.service.CartService;
import com.zhou.peakflow.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.zhou.peakflow.constant.RedisKeyConstant.CART;
import static com.zhou.peakflow.constant.RedisKeyConstant.CART_UNSELECTED;

/**
 * 购物车服务实现（纯 Redis 版）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ProductService productService;

    private static final String LOG_PREFIX = "【购物车】";

    // ---- 内部工具方法 ----

    private String getCartKey(Long userId) {
        return CART + userId;
    }

    /** 「取消选中」商品集合的 Redis Key */
    private String getUnselectedKey(Long userId) {
        return CART_UNSELECTED + ":" + userId;
    }

    /** 每次操作后刷新购物车过期时间（最后操作时间起 30 天） */
    private void refreshCartExpire(Long userId) {
        stringRedisTemplate.expire(getCartKey(userId),
                CacheExpireConstants.CART_DAYS, TimeUnit.DAYS);
        stringRedisTemplate.expire(getUnselectedKey(userId),
                CacheExpireConstants.CART_DAYS, TimeUnit.DAYS);
    }

    /** 校验商品是否存在且已上架，返回商品实体 */
    private Product requireOnShelfProduct(Long productId) {
        Product product = productService.getProductDetail(productId);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (ProductShelfStatus.OFF_SHELF.getCode().equals(product.getStatus())) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        return product;
    }

    // ---- 业务方法 ----

    // 加入购物车
    @Override
    public void addToCart(Long userId, Long productId, Integer quantity) {
        log.info("{}加入购物车 | userId={} | productId={} | quantity={}",
                LOG_PREFIX, userId, productId, quantity);

        if (userId == null || productId == null || quantity == null
                || quantity <= 0 || productId <= 0) {
            throw new BusinessException("参数不合法");
        }
        if (quantity > BusinessConstants.MAX_CART_ITEM_QUANTITY) {
            throw new BusinessException("单个商品数量不能超过" + BusinessConstants.MAX_CART_ITEM_QUANTITY);
        }

        Product product = requireOnShelfProduct(productId);

        String cartKey = getCartKey(userId);
        Long totalQty = stringRedisTemplate.opsForHash()
                .increment(cartKey, productId.toString(), quantity);

        // 库存不足：回滚本次增量
        if (product.getStock() != null && totalQty > product.getStock()) {
            stringRedisTemplate.opsForHash()
                    .increment(cartKey, productId.toString(), -quantity);
            throw new BusinessException(ResultCode.CART_STOCK_NOT_ENOUGH);
        }

        refreshCartExpire(userId);
        log.info("{}加入购物车成功，totalQty={}", LOG_PREFIX, totalQty);
    }

    // 修改购物车中某商品的数量（覆盖为目标值）
    @Override
    public void updateQuantity(Long userId, Long productId, Integer quantity) {
        log.info("{}修改数量 | userId={} | productId={} | targetQuantity={}",
                LOG_PREFIX, userId, productId, quantity);

        if (userId == null || productId == null || quantity == null
                || quantity <= 0 || productId <= 0) {
            throw new BusinessException("参数不合法");
        }
        if (quantity > BusinessConstants.MAX_CART_ITEM_QUANTITY) {
            throw new BusinessException("单个商品数量不能超过" + BusinessConstants.MAX_CART_ITEM_QUANTITY);
        }

        Product product = productService.getProductDetail(productId);
        if (product == null) {
            // 商品已不存在，顺手清理购物车中的失效项
            stringRedisTemplate.opsForHash().delete(getCartKey(userId), productId.toString());
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (ProductShelfStatus.OFF_SHELF.getCode().equals(product.getStatus())) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        if (product.getStock() == null || product.getStock() < quantity) {
            throw new BusinessException(ResultCode.CART_STOCK_NOT_ENOUGH);
        }

        stringRedisTemplate.opsForHash()
                .put(getCartKey(userId), productId.toString(), String.valueOf(quantity));

        refreshCartExpire(userId);
        log.info("{}修改数量成功，productId={}, quantity={}", LOG_PREFIX, productId, quantity);
    }

    // 删除购物车中的单个商品
    @Override
    public void removeFromCart(Long userId, Long productId) {
        log.info("{}删除商品 | userId={} | productId={}", LOG_PREFIX, userId, productId);

        if (userId == null || productId == null || productId <= 0) {
            throw new BusinessException("参数不合法");
        }

        stringRedisTemplate.opsForHash().delete(getCartKey(userId), productId.toString());
        // 商品都删了，它的"取消选中"记录也没有意义了，顺手清掉避免脏数据堆积
        stringRedisTemplate.opsForSet().remove(getUnselectedKey(userId), productId.toString());
        refreshCartExpire(userId);
        log.info("{}删除商品成功", LOG_PREFIX);
    }

    // 批量移除购物车商品（下单成功后清理）
    @Override
    public void removeProductsFromCart(Long userId, List<Long> productIds) {
        if (userId == null) {
            throw new BusinessException("参数不合法");
        }
        if (productIds == null || productIds.isEmpty()) {
            return;
        }

        log.info("{}批量移除购物车商品 | userId={} | 商品数量={}",
                LOG_PREFIX, userId, productIds.size());

        Object[] fields = productIds.stream().map(String::valueOf).toArray();
        Long deleted = stringRedisTemplate.opsForHash().delete(getCartKey(userId), fields);
        stringRedisTemplate.opsForSet().remove(getUnselectedKey(userId), fields);

        refreshCartExpire(userId);
        log.info("{}批量移除购物车商品完成，实际删除={}", LOG_PREFIX, deleted);
    }

    // 清空购物车
    @Override
    public void clearCart(Long userId) {
        log.info("{}清空购物车 | userId={}", LOG_PREFIX, userId);

        if (userId == null) {
            throw new BusinessException("参数不合法");
        }

        stringRedisTemplate.delete(getCartKey(userId));
        stringRedisTemplate.delete(getUnselectedKey(userId));
        log.info("{}清空购物车成功", LOG_PREFIX);
    }

    // 查询购物车列表
    @Override
    public List<Cart> getCartList(Long userId) {
        log.info("{}获取购物车列表 | userId={}", LOG_PREFIX, userId);

        if (userId == null) {
            throw new BusinessException("参数不合法");
        }

        String cartKey = getCartKey(userId);
        Map<Object, Object> cartMap = stringRedisTemplate.opsForHash().entries(cartKey);
        if (cartMap.isEmpty()) {
            return Collections.emptyList();
        }

        // 读取"被手动取消选中"的商品，用于还原每个条目的 selected 状态
        Set<String> unselectedIds = stringRedisTemplate.opsForSet().members(getUnselectedKey(userId));
        if (unselectedIds == null) {
            unselectedIds = Collections.emptySet();
        }

        // 批量查商品，避免逐个查询
        Set<Long> productIds = new HashSet<>();
        for (Object field : cartMap.keySet()) {
            productIds.add(Long.parseLong(field.toString()));
        }
        Map<Long, Product> productMap = productIds.stream()
                .map(productService::getProductDetail)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Product::getId, p -> p));

        List<Cart> result = new ArrayList<>();
        List<String> invalidFields = new ArrayList<>();

        for (Map.Entry<Object, Object> entry : cartMap.entrySet()) {
            Long productId = Long.parseLong(entry.getKey().toString());
            Integer quantity = Integer.parseInt(entry.getValue().toString());

            Product product = productMap.get(productId);
            if (product == null
                    || ProductShelfStatus.OFF_SHELF.getCode().equals(product.getStatus())
                    || product.getStock() == null
                    || product.getStock() < quantity) {
                invalidFields.add(productId.toString());
                continue;
            }

            Cart cart = new Cart();
            cart.setProductId(productId);
            cart.setQuantity(quantity);
            cart.setProductName(product.getName());
            cart.setImageUrl(product.getImage());
            cart.setPrice(product.getPrice());
            cart.setIsSeckill(product.getIsSeckill());
            // 购物车默认全选；只有出现在「取消选中」集合里的商品才是未选中
            cart.setSelected(unselectedIds.contains(productId.toString()) ? 0 : 1);
            result.add(cart);
        }

        // 清理失效商品
        if (!invalidFields.isEmpty()) {
            stringRedisTemplate.opsForHash().delete(cartKey, invalidFields.toArray());
            stringRedisTemplate.opsForSet().remove(getUnselectedKey(userId), invalidFields.toArray());
            log.info("{}清理失效商品，数量={}", LOG_PREFIX, invalidFields.size());
        }

        // 按商品 ID 倒序（最近加入的靠前）
        result.sort((a, b) -> b.getProductId().compareTo(a.getProductId()));

        return result;
    }

    // 设置某商品的选中态
    @Override
    public void selectProduct(Long userId, Long productId, Integer selected) {
        log.info("{}设置选中态 | userId={} | productId={} | selected={}",
                LOG_PREFIX, userId, productId, selected);

        if (userId == null || productId == null || productId <= 0) {
            throw new BusinessException("参数不合法");
        }

        // 商品必须真的在购物车里，防止无意义地为不存在的条目写选中态
        Boolean inCart = stringRedisTemplate.opsForHash()
                .hasKey(getCartKey(userId), productId.toString());
        if (!Boolean.TRUE.equals(inCart)) {
            throw new BusinessException("该商品不在购物车中");
        }

        String unselectedKey = getUnselectedKey(userId);
        boolean select = selected == null || selected != 0;
        if (select) {
            stringRedisTemplate.opsForSet().remove(unselectedKey, productId.toString());
        } else {
            stringRedisTemplate.opsForSet().add(unselectedKey, productId.toString());
        }

        refreshCartExpire(userId);
        log.info("{}选中态更新成功，selected={}", LOG_PREFIX, select ? 1 : 0);
    }

    // 获取已选中的购物车商品（结算 / 下单用）
    @Override
    public List<Cart> getSelectedProducts(Long userId) {
        return getCartList(userId).stream()
                .filter(cart -> Integer.valueOf(1).equals(cart.getSelected()))
                .collect(Collectors.toList());
    }
}
