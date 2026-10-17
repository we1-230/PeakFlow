package com.zhou.peakflow.controller;

import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.Product;
import com.zhou.peakflow.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 商品详情接口 <b>不存在 id 的契约</b>（2026-10-17 修复）。
 *
 * <p>背景：{@code ProductServiceImpl#getProductDetail} 为了防缓存穿透，对「不存在的 id」
 * 会<b>返回 null 并写入空值缓存</b>，这是合理的设计。但 Controller 之前把这个 null 直接
 * 包成 {@code Result.success(null)}，于是接口表现为「HTTP 200 + code=200 + data=null」——
 * 前端拿到的是「成功但没数据」，页面渲染为空白且**不给任何提示**（回归测试实测发现）。</p>
 *
 * <p>修复只在 Controller 边界加判空：null → 抛 BusinessException("商品不存在")，
 * 由全局异常处理器转成 {@code code=3005}。Service 的空值缓存逻辑一行未动。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("商品详情接口 · 不存在 id 的响应契约")
class ProductControllerDetailTest {

    @Mock
    private ProductService productService;

    @InjectMocks
    private ProductController productController;

    @Test
    @DisplayName("[契约] 商品不存在 → 抛 BusinessException（不返回 200+null）")
    void detail_productNotFound_shouldThrowBusinessException() {
        when(productService.getProductDetail(999999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productController.detail(999999L));

        assertEquals("商品不存在", ex.getMessage());
    }

    @Test
    @DisplayName("[正常] 商品存在 → 返回 200 且带完整数据")
    void detail_productExists_shouldReturnSuccess() {
        Product p = new Product();
        p.setId(5L);
        p.setName("微单数码相机");
        when(productService.getProductDetail(5L)).thenReturn(p);

        var result = productController.detail(5L);

        assertEquals(200, result.getCode());
        assertTrue(result.isSuccess());
        assertEquals("微单数码相机", result.getData().getName());
    }
}
