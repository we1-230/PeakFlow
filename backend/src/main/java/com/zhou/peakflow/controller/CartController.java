package com.zhou.peakflow.controller;

import com.zhou.peakflow.pojo.Cart;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;


/**
 * 购物车控制器
 */
@Slf4j
@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController extends BaseController {

    private final CartService cartService;



    @PostMapping("/add")
    public Result<Void> add(@RequestHeader("Authorization") String token,
                            @RequestParam Long productId,
                            @RequestParam Integer quantity) {
        log.info("加入购物车请求，productId={}, quantity={}", productId, quantity);

        Long userId = getUserIdFromToken(token);

        cartService.addToCart(userId, productId, quantity);
        return Result.success("添加成功");
    }

    @PostMapping("/updateQuantity")
    public Result<Void> updateQuantity(@RequestHeader("Authorization") String token,
                                       @RequestParam Long productId,
                                       @RequestParam Integer quantity) {
        log.info("修改购物车商品数量请求，productId={}, quantity={}", productId, quantity);
        Long userId = getUserIdFromToken(token);

        cartService.updateQuantity(userId, productId, quantity);
        return Result.success("修改成功");
    }

    @DeleteMapping("/delete/{productId}")
    public Result<Void> delete(@RequestHeader("Authorization") String token,
                               @PathVariable Long productId) {
        log.info("删除购物车商品请求，productId={}", productId);

        Long userId = getUserIdFromToken(token);

        cartService.removeFromCart(userId, productId);
        return Result.success("删除成功");
    }

    @DeleteMapping("/clear")
    public Result<Void> clear(@RequestHeader("Authorization") String token) {
        log.info("清空购物车请求");

        Long userId = getUserIdFromToken(token);

        cartService.clearCart(userId);
        return Result.success("清空成功");
    }

    // 只读，无需限流
    @GetMapping("/list")
    public Result<List<Cart>> list(@RequestHeader("Authorization") String token) {
        log.info("获取购物车列表请求");

        Long userId = getUserIdFromToken(token);
        List<Cart> cartList = cartService.getCartList(userId);
        return Result.success(cartList);
    }

}