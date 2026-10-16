package com.zhou.peakflow.controller;

import com.zhou.peakflow.dto.product.AddProductDto;
import com.zhou.peakflow.dto.product.SelectProductDto;
import com.zhou.peakflow.dto.product.UpdateProductDto;
import com.zhou.peakflow.pojo.Product;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.service.ProductService;
import com.zhou.peakflow.vo.PageVo;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static org.springframework.beans.support.PagedListHolder.DEFAULT_PAGE_SIZE;


/**
 * 商品控制器
 */
@Slf4j
@RestController
@RequestMapping("/product")
@RequiredArgsConstructor
public class ProductController extends BaseController{

    private final ProductService productService;

    @PostMapping("/page")
    public Result<PageVo<Product>> page(@RequestBody @Valid SelectProductDto selectProductDto) {
        Integer pageSize = selectProductDto.getPageSize() != null ? selectProductDto.getPageSize() : DEFAULT_PAGE_SIZE;

        log.info("首页商品列表请求，pageNum={}, pageSize={}", selectProductDto.getPageNum(), pageSize);
        PageVo<Product> page = productService.pageQuery(selectProductDto.getPageNum(), pageSize,
                selectProductDto.getCategoryId(),
                selectProductDto.getKeyword());

        return Result.success(page);
    }

    @GetMapping("/detail/{id}")
    public Result<Product> detail(@PathVariable Long id) {
        log.info("商品详情请求，id={}", id);
        Product product = productService.getProductDetail(id);
        return Result.success(product);
    }

    @PreAuthorize("hasAuthority('product:manage')")
    @PostMapping("/add")
    public Result<Void> add(@Valid @RequestBody AddProductDto addProductDto) {
        log.info("新增商品请求，name={}", addProductDto.getName());
        productService.addProduct(addProductDto);
        return Result.success("新增成功");
    }

    @PreAuthorize("hasAuthority('product:manage')")
    @PutMapping("/update")
    public Result<Void> update(@Valid @RequestBody UpdateProductDto updateProductDto) {
        log.info("更新商品请求，id={}", updateProductDto.getId());
        productService.updateProduct(updateProductDto);
        return Result.success("更新成功");
    }

    @PreAuthorize("hasAuthority('product:manage')")
    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除商品请求，id={}", id);
        productService.deleteProduct(id);
        return Result.success("删除成功");
    }

    @PreAuthorize("hasAuthority('product:manage')")
    @PutMapping("/status/{id}")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        log.info("上下架商品请求，id={}, status={}", id, status);
        productService.updateStatus(id, status);
        return Result.success("操作成功");
    }
}