package com.zhou.peakflow.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhou.peakflow.dto.product.AddCategoryDto;
import com.zhou.peakflow.dto.product.UpdateCategoryDto;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.Category;
import com.zhou.peakflow.mapper.CategoryMapper;
import com.zhou.peakflow.service.CategoryService;
import com.zhou.peakflow.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.zhou.peakflow.constant.CacheExpireConstants.CATEGORY_HOURS;
import static com.zhou.peakflow.constant.RedisKeyConstant.PRODUCT_CATEGORY;


/**
 * 商品分类Service实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ProductService productService;
    private final CategoryMapper categoryMapper;

    private final RedissonClient redissonClient;

    // 查询所有启用的分类
    @Override
    public List<Category> getEnabledCategories() {
        log.info("查询所有启用的分类");

        // ---- 1. 先查缓存 ----
        String json = stringRedisTemplate.opsForValue().get(PRODUCT_CATEGORY);
        if (json != null) {
            try {
                log.info("从缓存中获取分类数据");
                return JSON.parseArray(json, Category.class);
            } catch (Exception e) {
                log.error("缓存数据解析失败，删除缓存，key={}", PRODUCT_CATEGORY, e);
                stringRedisTemplate.delete(PRODUCT_CATEGORY);
            }
        }

        // ---- 2. 缓存未命中，加分布式锁防击穿 ----
        // 分类数据是全局共享的，用全局锁，粒度可以接受
        String lockKey = "lock:category";
        RLock lock = redissonClient.getLock(lockKey);

        boolean isLocked = false;

        try {
            // 尝试获取锁：等待 1 秒，持有 5秒自动释放
            isLocked = lock.tryLock(1, TimeUnit.SECONDS);

            if (!isLocked) {
                log.warn("获取分类锁失败，系统繁忙");
                throw new BusinessException("系统繁忙，请稍后再试");
            }

            // ---- 3. 双重检查：获取锁后再次检查缓存 ----
            // 可能在上一个线程释放锁之前，已经有线程重建好缓存了
            json = stringRedisTemplate.opsForValue().get(PRODUCT_CATEGORY);
            if (json != null) {
                try {
                    log.info("双重检查缓存命中，跳过数据库查询");
                    return JSON.parseArray(json, Category.class);
                } catch (Exception e) {
                    log.error("双重检查缓存解析失败，删除缓存", e);
                    stringRedisTemplate.delete(PRODUCT_CATEGORY);
                }
            }

            // ---- 4. 查数据库 ----

            log.info("从数据库中获取分类数据");
            List<Category> categoryList = queryEnabledCategoriesFromDb();

            // ---- 5. 写入缓存 ----
            if (categoryList != null && !categoryList.isEmpty()) {
                // 过期时间加随机，防雪崩
                long baseSeconds = CATEGORY_HOURS * 60 * 60;
                long randomSeconds = ThreadLocalRandom.current().nextInt(300);
                long expireSeconds = baseSeconds + randomSeconds;

                stringRedisTemplate.opsForValue().set(
                        PRODUCT_CATEGORY,
                        JSON.toJSONString(categoryList),
                        expireSeconds,
                        TimeUnit.SECONDS
                );
                log.info("分类缓存写入成功，过期时间={}秒", expireSeconds);
            } else {
                // 数据库也为空，缓存空值防穿透，空值时间短一点
                stringRedisTemplate.opsForValue().set(
                        PRODUCT_CATEGORY,
                        "[]",
                        300,
                        TimeUnit.SECONDS
                );
                log.info("分类数据为空，写入空值缓存防穿透");
            }

            return categoryList;

        } catch (InterruptedException e) {
            log.error("获取分类锁被中断", e);
            Thread.currentThread().interrupt();
            throw new BusinessException("获取分类锁被中断");
        } finally {
            // unlock 前判断 isHeldByCurrentThread
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 抽公共查库方法
    private List<Category> queryEnabledCategoriesFromDb() {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getStatus, 1)
                .orderByAsc(Category::getSort);
        return this.list(wrapper);
    }

    public void addCategory(AddCategoryDto categoryDto) {
        log.info("新增分类，name={}, sort={}",
                categoryDto.getName(), categoryDto.getSort());

        // 1. 校验分类名称是否已存在
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getName, categoryDto.getName());
        long count = this.count(wrapper);

        if (count > 0) {
            throw new BusinessException("商品分类名称已存在，请勿重复添加");
        }

        // 2. 新增分类
        Category category = new Category();
        BeanUtils.copyProperties(categoryDto, category);
        // 强制清空 ID，依赖数据库自增策略
        category.setId(null);

        try {
            this.save(category);
        } catch (DuplicateKeyException e) {
            log.warn("新增分类名称重复，name={}", categoryDto.getName());
            throw new BusinessException("分类名称已存在，请勿重复添加");
        }

        log.info("新增分类成功，id={}", category.getId());
    }

    @Override
    public void updateCategory(UpdateCategoryDto categoryDto) {
        long id = categoryDto.getId();
        if (id <= 0) {
            throw new BusinessException("分类 id 不合法");
        }

        log.info("禁用分类，id={}", id);
        // 状态校验与更新由同一条 SQL 原子完成
        try {
            int rows = categoryMapper.updateCategoryWithCheck(categoryDto);
            if (rows == 0) {
                throw new BusinessException("分类不存在、已禁用、已删除，或分类下有商品，无法禁用");
            }
        }catch (DuplicateKeyException e){
            log.warn("商品分类名字已重复");
            throw new BusinessException("分类不存在、已禁用、已删除，或分类下有商品，无法禁用");
        }

        log.info("禁用分类成功，id={}", id);
    }


    @Override
    public void deleteCategory(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException("分类 id 不合法");
        }

        log.info("删除分类，id={}", id);

        int rows = categoryMapper.deleteCategoryIfNoProduct(id);
        if (rows == 0) {
            throw new BusinessException("分类不存在、已删除，或分类下有商品，无法删除");
        }

        log.info("删除分类成功，id={}", id);
    }
}
