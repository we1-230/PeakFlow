package com.zhou.peakflow.controller;

import com.zhou.peakflow.dto.order.SeckillOrderDto;
import com.zhou.peakflow.dto.product.AddSeckillProductDto;
import com.zhou.peakflow.dto.product.SelectProductDto;
import com.zhou.peakflow.dto.product.UpdateSeckillProductDto;
import com.zhou.peakflow.pojo.Product;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.service.SeckillOrderService;
import com.zhou.peakflow.vo.PageVo;
import com.zhou.peakflow.vo.SeckillResultVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 秒杀控制器
 */
@Slf4j
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
@Tag(name = "秒杀管理", description = "秒杀相关接口")
public class SeckillOrderController extends BaseController {

    private final SeckillOrderService seckillOrderService;

    // 秒杀下单（立即返回排队标识；限流在服务层三层统一处理，控制器保持轻薄）
    @PostMapping("/order")
    @Operation(summary = "秒杀下单", description = "返回requestId用于轮询结果")
    public Result<String> seckill(@RequestHeader("Authorization") String token,
                                  @Valid @RequestBody SeckillOrderDto seckillOrderDto) {
        log.info("秒杀下单请求，productId={}", seckillOrderDto.getProductId());
        Long userId = getUserIdFromToken(token);
        String requestId = seckillOrderService.seckill(userId, seckillOrderDto);
        return Result.success("队伍等待中", requestId);
    }

    // 前端轮询查询秒杀结果（约 1 秒一次）
    @PostMapping("/result/{requestId}")
    @Operation(summary = "查询秒杀结果")
    public Result<SeckillResultVo> getSeckillResult(@PathVariable String requestId) {
        SeckillResultVo result = seckillOrderService.getSeckillResult(requestId);
        Integer status = result == null ? null : result.getStatus();
        if (Integer.valueOf(SeckillResultVo.STATUS_SUCCESS).equals(status)) {
            return Result.success("秒杀成功", result);
        }
        if (Integer.valueOf(SeckillResultVo.STATUS_FAIL).equals(status)) {
            // 返回 200 让前端通过 data.status 判断，避免被 axios 拦截器当错误处理
            return Result.success("秒杀失败", result);
        }
        return Result.success("排队中", result);
    }

    @PostMapping("/list")
    @Operation(summary = "秒杀商品列表")
    public Result<PageVo<Product>> seckillList(@RequestBody @Valid SelectProductDto selectProductDto) {
        PageVo<Product> products = seckillOrderService.getSeckillProducts(selectProductDto);
        return Result.success(products);
    }

    @GetMapping("/detail/{id}")
    @Operation(summary = "秒杀商品详情")
    public Result<Product> detail(@PathVariable Long id) {
        Product product = seckillOrderService.getProductDetail(id);
        if (product == null) {
            return Result.error("商品不存在");
        }
        return Result.success(product);
    }

    // 秒杀全量缓存预热（管理员）
    @PreAuthorize("hasAuthority('seckill:manage')")
    @GetMapping("/preheat/all")
    @Operation(summary = "秒杀缓存预热")
    public Result<Void> preheatAll() {
        seckillOrderService.preheatAllSeckillCache();
        return Result.success("预热成功");
    }

    // 新增秒杀商品（管理员）
    @PreAuthorize("hasAuthority('seckill:manage')")
    @PostMapping("/add")
    @Operation(summary = "新增秒杀商品")
    public Result<Void> add(@Valid @RequestBody AddSeckillProductDto addSeckillProductDto) {
        log.info("新增秒杀商品请求，name={}", addSeckillProductDto.getName());
        seckillOrderService.addProduct(addSeckillProductDto);
        return Result.success("新增成功");
    }

    // 更新秒杀商品含库存（管理员）
    @PreAuthorize("hasAuthority('seckill:manage')")
    @PutMapping("/update")
    @Operation(summary = "更新秒杀商品")
    public Result<Void> update(@Valid @RequestBody UpdateSeckillProductDto updateSeckillProductDto) {
        log.info("更新秒杀商品请求，id={}", updateSeckillProductDto.getId());
        seckillOrderService.updateProduct(updateSeckillProductDto);
        return Result.success("更新成功");
    }

    // 删除秒杀商品（管理员）
    @PreAuthorize("hasAuthority('seckill:manage')")
    @DeleteMapping("/delete/{id}")
    @Operation(summary = "删除秒杀商品")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除秒杀商品请求，id={}", id);
        seckillOrderService.deleteProduct(id);
        return Result.success("删除成功");
    }
}
