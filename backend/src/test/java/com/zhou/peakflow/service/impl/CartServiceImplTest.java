package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.Product;
import com.zhou.peakflow.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购物车服务单元测试（2026-10-17 修复的两类后端缺陷）。
 *
 * <p><b>缺陷 1：秒杀商品可经 API 加购。</b>前端对秒杀商品隐藏了「加入购物车」按钮，
 * 但后端 {@code addToCart} 完全没校验 —— 任何直接调接口的客户端都能加进去。
 * 而且因为秒杀商品 {@code stock=0}，会先命中库存校验，返回「商品库存不足」这种
 * <b>误导性提示</b>（用户以为是没货，实际是渠道不对）。生产实测复现过。</p>
 *
 * <p><b>缺陷 2：加购失败回滚留下 quantity=0 的幽灵条目。</b>回滚用的是
 * {@code increment(-quantity)}，若该商品<b>原本不在购物车</b>，减回去就留下 value=0 的字段。
 * 而列表清理条件是 {@code stock < quantity}，对 0 不成立 ⇒ 永远清不掉。
 * 后果：购物车出现「0 件」条目，且角标按条目数计数会虚增。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("购物车服务 · 秒杀商品拦截与回滚清理")
class CartServiceImplTest {

    private static final Long USER_ID = 32L;
    private static final Long PRODUCT_ID = 5L;
    private static final String CART_KEY = "cart" + USER_ID;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ProductService productService;
    @Mock
    private HashOperations<String, Object, Object> hashOps;
    @Mock
    private SetOperations<String, String> setOps;

    @InjectMocks
    private CartServiceImpl cartService;

    private Product product(int isSeckill, int stock) {
        Product p = new Product();
        p.setId(PRODUCT_ID);
        p.setName("测试商品");
        p.setImage("https://example.com/p.png");
        p.setPrice(new java.math.BigDecimal("99.00"));
        p.setStock(stock);
        p.setSeckillStock(isSeckill == 1 ? 10 : null);
        p.setIsSeckill(isSeckill);
        p.setStatus(1);   // 上架
        return p;
    }

    @Test
    @DisplayName("[秒杀拦截] 秒杀商品加购 → 明确业务拒绝，且不写 Redis")
    void addToCart_seckillProduct_shouldRejectBeforeStockCheck() {
        when(productService.getProductDetail(PRODUCT_ID)).thenReturn(product(1, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.addToCart(USER_ID, PRODUCT_ID, 1));

        // 必须是「渠道不对」的明确提示，不能是误导性的「库存不足」
        assertTrue(ex.getMessage().contains("秒杀"),
                "提示应说明秒杀商品不可加购，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("购物车"), "实际：" + ex.getMessage());
        // 一秒都不该碰购物车
        verify(stringRedisTemplate, never()).opsForHash();
    }

    @Test
    @DisplayName("[回滚] 新增商品时库存不足 → 删除字段，不留 quantity=0 幽灵条目")
    void addToCart_newItemOutOfStock_shouldDeleteFieldNotLeaveZero() {
        when(productService.getProductDetail(PRODUCT_ID)).thenReturn(product(0, 0));
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        // 原本不在购物车：increment 后返回 1（本次新增），但库存 0 ⇒ 应回滚
        when(hashOps.increment(CART_KEY, PRODUCT_ID.toString(), 1L)).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.addToCart(USER_ID, PRODUCT_ID, 1));
        assertEquals(ResultCode.CART_STOCK_NOT_ENOUGH.getCode(), ex.getCode());

        // 关键断言：回滚用 delete，而不是 increment(-1)（后者会留下 value=0）
        verify(hashOps).delete(eq(CART_KEY), eq(PRODUCT_ID.toString()));
        verify(hashOps, never()).increment(anyString(), anyString(), eq(-1L));
    }

    @Test
    @DisplayName("[回滚] 已有 3 件时再加到超库存 → 只减回本次增量，保留原有 3 件")
    void addToCart_existingItemOutOfStock_shouldDecrementOnly() {
        when(productService.getProductDetail(PRODUCT_ID)).thenReturn(product(0, 3));
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        // 原本 3 件，加 1 变 4（> 库存 3）⇒ 应减回 3
        when(hashOps.increment(CART_KEY, PRODUCT_ID.toString(), 1L)).thenReturn(4L);

        assertThrows(BusinessException.class,
                () -> cartService.addToCart(USER_ID, PRODUCT_ID, 1));

        verify(hashOps).increment(CART_KEY, PRODUCT_ID.toString(), -1L);
        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    @DisplayName("[列表] quantity=0 的历史幽灵条目 → 判为失效并清理")
    void getCartList_zeroQuantityEntry_shouldBeCleanedUp() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(hashOps.entries(CART_KEY)).thenReturn(new HashMap<>(Map.of(PRODUCT_ID.toString(), "0")));
        when(setOps.members(anyString())).thenReturn(null);
        // 商品本身正常（库存 0 的秒杀商品），仅数量为 0
        when(productService.getProductDetail(PRODUCT_ID)).thenReturn(product(1, 0));

        List<?> result = cartService.getCartList(USER_ID);

        assertTrue(result.isEmpty(), "0 件条目不应出现在列表里");
        // 注意 Mockito 对 varargs 的语义：生产代码 delete(key, invalidFields.toArray())
        // 会被展开记录下来，实际匹配到的是 delete("cart32", "5")，不是 delete("cart32", Object[])
        verify(hashOps).delete(eq(CART_KEY), eq(PRODUCT_ID.toString()));
    }

    @Test
    @DisplayName("[列表] 正常条目照常返回（修复不能误伤正常数据）")
    void getCartList_normalEntry_shouldReturn() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(hashOps.entries(CART_KEY)).thenReturn(new HashMap<>(Map.of(PRODUCT_ID.toString(), "2")));
        when(setOps.members(anyString())).thenReturn(null);
        when(productService.getProductDetail(PRODUCT_ID)).thenReturn(product(0, 10));

        List<?> result = cartService.getCartList(USER_ID);

        assertEquals(1, result.size());
        verify(hashOps, never()).delete(anyString(), any());
    }
}
