package com.zhou.peakflow.controller;

import com.zhou.peakflow.dto.product.AddCategoryDto;
import com.zhou.peakflow.dto.product.UpdateCategoryDto;
import com.zhou.peakflow.pojo.Category;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.service.CategoryService;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

import static com.zhou.peakflow.constant.RedisKeyConstant.PRODUCT_CATEGORY;

/**
 * 商品分类控制器
 */
@Slf4j
@RestController
@RequestMapping("/category")
@RequiredArgsConstructor
public class CategoryController extends BaseController{

    private final CategoryService categoryService;

    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping("/list")
    public Result<List<Category>> getCategories() {
        log.info("查询所有分类");
        List<Category> categories = categoryService.getEnabledCategories();
        return Result.success(categories);
    }

    @PreAuthorize("hasAuthority('category:manage')")
    @PostMapping("/add")
    public Result<Void> addCategory(@Valid @RequestBody AddCategoryDto categoryDto) {
        // 走代理调用，事务生效
        categoryService.addCategory(categoryDto);

        try {
            stringRedisTemplate.delete(PRODUCT_CATEGORY);
        } catch (Exception e) {
            log.error("删除缓存失败，key={}", PRODUCT_CATEGORY, e);
            // 不抛异常，不影响前端
        }
        return Result.success("新增成功");
    }

    @PreAuthorize("hasAuthority('category:manage')")
    @PutMapping("/update")
    public Result<Void> updateCategory(@Valid @RequestBody UpdateCategoryDto categoryDto) {
        // 1. 事务方法
        categoryService.updateCategory(categoryDto);

        // 2. 事务提交后删缓存
        try {
            stringRedisTemplate.delete(PRODUCT_CATEGORY);
        }catch (Exception e){
            log.error("删除缓存失败",e);
        }

        log.info("删除分类缓存成功，key={}", PRODUCT_CATEGORY );
        return Result.success("更新成功");
    }

    @PreAuthorize("hasAuthority('category:manage')")
    @DeleteMapping("/delete/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        log.info("删除分类请求，id={}", id);
        categoryService.deleteCategory(id);

        // 2. 事务提交后，删除缓存
        try{
        Boolean deleted = stringRedisTemplate.delete(PRODUCT_CATEGORY);}
        catch (Exception e){
            log.error("清除缓存错误",e);
        }
        log.info("删除分类缓存，key={}", PRODUCT_CATEGORY);
        return Result.success("删除成功");
    }
}