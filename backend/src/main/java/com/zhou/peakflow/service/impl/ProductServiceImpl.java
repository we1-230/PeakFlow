package com.zhou.peakflow.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhou.peakflow.constant.BusinessConstants;
import com.zhou.peakflow.constant.OrderConstants;
import com.zhou.peakflow.enums.ProductShelfStatus;
import com.zhou.peakflow.enums.SeckillFlag;
import com.zhou.peakflow.dto.product.AddProductDto;
import com.zhou.peakflow.dto.product.UpdateProductDto;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.guard.ProductOperateGuard;
import com.zhou.peakflow.mapper.ProductMapper;
import com.zhou.peakflow.pojo.Product;
import com.zhou.peakflow.service.ProductService;
import com.zhou.peakflow.vo.PageVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.zhou.peakflow.constant.CacheExpireConstants.*;
import static com.zhou.peakflow.constant.RedisKeyConstant.*;

/**
 * 商品 Service 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redissonClient;

    private final RedisTemplate<String, Object> redisTemplate;

    /** 商品删除 / 下架前的占用校验（在途订单 + 未完结退款单），与秒杀模块共用同一份规则 */
    private final ProductOperateGuard productOperateGuard;

    private final ProductMapper productMapper;

    // 分页查询普通商品（首页展示）
    @Override
    public PageVo<Product> pageQuery(Integer pageNum, Integer pageSize, Long categoryId, String keyword) {
        log.info("【商品分页】开始查询，pageNum={}, pageSize={}, categoryId={}, keyword=[{}]",
                pageNum, pageSize, categoryId, keyword);

        // ---- 1. 参数校验与默认值 ----
        if (pageNum == null || pageNum < 1) {
            pageNum = BusinessConstants.DEFAULT_PAGE_NUM;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = BusinessConstants.DEFAULT_PAGE_SIZE;
        }
        if (pageSize > BusinessConstants.MAX_PAGE_SIZE) {
            pageSize = BusinessConstants.MAX_PAGE_SIZE;
        }
        if (pageNum > BusinessConstants.MAX_PAGE_NUM) {
            throw new BusinessException("页码过大，请使用筛选条件减少页码");
        }

        // ---- 2. 构建缓存 key ----
        // 缓存 key 里的 keyword 用 MD5 前 8 位，避免 key 超长、特殊字符污染
        String cacheKey = buildPageCacheKey(pageNum, pageSize, categoryId, keyword);
        String lockKey = "lock:" + cacheKey;

        // ---- 3. 先查缓存 ----
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            log.info("【商品分页】缓存命中，cacheKey={}", cacheKey);
            // 用 TypeReference 保留泛型
            return JSON.parseObject(cachedJson, new TypeReference<PageVo<Product>>() {});
        }

        log.info("【商品分页】缓存未命中，准备获取分布式锁，cacheKey={}", cacheKey);

        // ---- 4. 加分布式锁 ----
        RLock lock = redissonClient.getLock(lockKey);
        // 提前声明 isLocked，finally 里才能判断
        boolean isLocked = false;

        try {
            isLocked = lock.tryLock(
                            OrderConstants.LOCK_WAIT_SECONDS,
                            TimeUnit.SECONDS
            );

            if (!isLocked) {
                log.warn("【商品分页】获取锁失败，cacheKey={}", cacheKey);
                throw new BusinessException("查询商品失败，请重试");
            }

            // ---- 5. 双重检查缓存 ----
            cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null) {
                log.info("【商品分页】双重检查缓存命中，cacheKey={}", cacheKey);
                return JSON.parseObject(cachedJson, new TypeReference<PageVo<Product>>() {});
            }

            // ---- 6. 构建分页与查询条件 ----
            Page<Product> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();

            if (categoryId != null) {
                wrapper.eq(Product::getCategoryId, categoryId);
            }

            // keyword 转义 % 和 _，避免用户输入通配符导致全表扫描
            if (StringUtils.hasText(keyword)) {
                String searchKeyword = escapeLike(keyword.replace(" ", "%"));
                wrapper.and(w -> w.like(Product::getName, searchKeyword)
                        .or()
                        .like(Product::getDescription, searchKeyword)
                        .or()
                        .like(Product::getDetail, searchKeyword));
                log.debug("【商品分页】应用搜索条件，keyword=[{}], searchKeyword=[{}]", keyword, searchKeyword);
            }

            wrapper.eq(Product::getIsSeckill, SeckillFlag.NO.getCode());
            wrapper.orderByAsc(Product::getCreateTime);

            // ---- 7. 查数据库 ----
            log.info("【商品分页】开始查询数据库");
            Page<Product> productPage = this.page(page, wrapper);

            log.info("【商品分页】MP查询结果：total={}, pages={}, current={}, size={}",
                    productPage.getTotal(), productPage.getPages(),
                    productPage.getCurrent(), productPage.getSize());

            // ---- 8. 封装 PageVo ----
            PageVo<Product> pageVo = new PageVo<>(
                    productPage.getRecords(),
                    productPage.getTotal(),
                    productPage.getPages(),
                    productPage.getCurrent(),
                    productPage.getSize()
            );

            // ---- 9. 写缓存 ----
            String json = JSON.toJSONString(pageVo);

            // 空值和正常值分开设置，单位明确
            if (productPage.getRecords().isEmpty()) {
                stringRedisTemplate.opsForValue().set(
                        cacheKey, json, NULL_CACHE_SECONDS, TimeUnit.SECONDS);
                log.info("【商品分页】结果为空，写入空值缓存，cacheKey={}, 过期={}秒",
                        cacheKey, NULL_CACHE_SECONDS);
            } else {
                // 用 ThreadLocalRandom 替代 new Random()
                // 避免每次创建对象，且多线程下无竞争
                long finalExpire = PRODUCT_DETAIL_MINUTES
                        + ThreadLocalRandom.current().nextInt(3);
                stringRedisTemplate.opsForValue().set(
                        cacheKey, json, finalExpire, TimeUnit.MINUTES);
                log.info("【商品分页】数据库查询成功，已写入缓存，cacheKey={}, 过期={}分钟",
                        cacheKey, finalExpire);
            }

            return pageVo;

        } catch (InterruptedException e) {
            log.error("【商品分页】获取锁被中断，cacheKey={}", cacheKey, e);
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后再试");
        } finally {
            // isLocked 和 isHeldByCurrentThread 都要判断
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 转义 LIKE 的通配符
    private String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    // 查询普通商品详情
    @Override
    public Product getProductDetail(Long id) {
        log.info("【商品详情】开始查询，商品ID：{}", id);

        // ---- 1. 参数校验 ----
        if (id == null || id <= 0) {
            log.warn("【商品详情】参数非法，商品ID：{}", id);
            throw new BusinessException("商品不存在");
        }

        String cacheKey = PRODUCT_DETAIL + id;
        String lockKey = "lock:product:detail:" + id;

        // ---- 2. 先查缓存 ----
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (json != null) {
            log.info("【商品详情】缓存命中，商品ID：{}", id);

            // 缓存解析加异常处理
            if (json.isEmpty()) {
                log.info("【商品详情】缓存为空值，商品ID：{}", id);
                return null;
            }
            try {
                return JSON.parseObject(json, Product.class);
            } catch (Exception e) {
                log.warn("【商品详情】缓存解析失败，删除脏数据，商品ID：{}", id, e);
                stringRedisTemplate.delete(cacheKey);
                // 不 return，继续走查库流程
            }
        }

        log.info("【商品详情】缓存未命中，准备获取分布式锁，商品ID：{}", id);

        // ---- 3. 加分布式锁防击穿 ----
        RLock lock = redissonClient.getLock(lockKey);

        boolean isLocked = false;

        try {
            // 不传持有时间，看门狗生效，业务没结束不会过期
            isLocked = lock.tryLock(OrderConstants.LOCK_WAIT_SECONDS, TimeUnit.SECONDS);

            if (!isLocked) {
                log.warn("【商品详情】获取锁失败，商品ID：{}", id);
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            log.info("【商品详情】成功获取锁，重试缓存，商品ID：{}", id);

            // ---- 4. 双重检查缓存 ----
            String retryJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (retryJson != null) {
                log.info("【商品详情】重试缓存命中，商品ID：{}", id);
                if (retryJson.isEmpty()) {
                    return null;
                }
                try {
                    return JSON.parseObject(retryJson, Product.class);
                } catch (Exception e) {
                    log.warn("【商品详情】重试缓存解析失败，删除脏数据，商品ID：{}", id, e);
                    stringRedisTemplate.delete(cacheKey);
                    // 继续走查库
                }
            }

            log.info("【商品详情】开始查询数据库，商品ID：{}", id);

            // ---- 5. 查数据库 ----
            Product product = this.getById(id);

            if (product == null) {
                log.warn("【商品详情】数据库未查询到商品，缓存空值，商品ID：{}", id);

                // 空值缓存单位修正
                stringRedisTemplate.opsForValue().set(
                        cacheKey, "", NULL_CACHE_SECONDS, TimeUnit.SECONDS);
                return null;
            }

            // ---- 6. 写缓存 ----
            addProductDetailCache(id, product);
            log.info("【商品详情】数据库查询成功，已写入缓存，商品ID：{}", id);

            return product;

        } catch (InterruptedException e) {
            // 恢复中断状态
            // 原来只抛业务异常，没有恢复中断标记，线程池可能无法正确停止
            Thread.currentThread().interrupt();
            log.error("【商品详情】获取锁被中断，商品ID：{}", id, e);
            throw new BusinessException("查询商品详情异常");

        } finally {
            // 双保险：isLocked && isHeldByCurrentThread
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("【商品详情】释放分布式锁，商品ID：{}", id);
            }
        }
    }



    @Override
    public void addProduct(AddProductDto addProductDto) {
        log.info("新增商品，商品名称：{}", addProductDto.getName());

        // 1. 应用层校验名称唯一
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getName, addProductDto.getName());
        if (this.count(wrapper) > 0) {
            throw new BusinessException("商品名称已存在，请勿重复添加");
        }

        // 2. DTO 转实体
        Product product = new Product();
        BeanUtils.copyProperties(addProductDto, product);
        product.setStatus(ProductShelfStatus.ON_SHELF.getCode());
        product.setSoldCount(0);
        product.setVersion(0);
        product.setIsSeckill(SeckillFlag.NO.getCode());

        // 3. 保存，唯一索引兜底
        try {
            this.save(product);
        } catch (DuplicateKeyException e) {
            log.warn("并发插入重复商品名称，name={}", addProductDto.getName());
            throw new BusinessException("商品名称已存在，请勿重复添加");
        }

        // 4. 删除所有分页缓存
        try {
            clearPageCache();
        } catch (Exception e) {
            log.error("删除分页缓存失败，name={}", addProductDto.getName(), e);
            // 不抛异常，靠过期兜底
        }


        log.info("新增商品成功，商品 ID：{}", product.getId());
    }


    // 更新普通商品
    @Override
    public void updateProduct(UpdateProductDto updateProductDto) {
        log.info("开始执行更新商品操作，商品ID：{}", updateProductDto.getId());

        Product product = this.getById(updateProductDto.getId());
        if (Objects.isNull(product)) {
            throw new BusinessException("商品不存在或已删除");
        }
        if (SeckillFlag.YES.getCode().equals(product.getIsSeckill())) {
            throw new BusinessException("秒杀商品不允许修改");
        }

        // 保留数据库当前 version
        Integer dbVersion = product.getVersion();

        BeanUtils.copyProperties(updateProductDto, product);
        product.setVersion(dbVersion);

        int rows = productMapper.updateProductWithOptimisticLock(product);
        if (rows == 0) {
            log.warn("【乐观锁冲突】更新商品失败，商品ID：{}", updateProductDto.getId());
            throw new BusinessException("商品已被其他管理员修改，请刷新页面后重试");
        }

        try {
            clearPageCache();
            clearProductDetailCache(product.getId());
        } catch (Exception e) {
            log.error("删除分页缓存失败，id={}", product.getId(), e);
        }

        log.info("更新商品流程结束，商品ID：{}", product.getId());
    }




    @Override
    public void deleteProduct(Long id) {
        log.info("开始执行删除商品操作，商品ID：{}", id);

        if (id == null || id <= 0) {
            throw new BusinessException("商品ID不存在或非法");
        }

        Product product = this.getById(id);
        if (product == null || product.getDeleted() == 1) {
            throw new BusinessException("商品不存在或已删除");
        }
        if (SeckillFlag.YES.getCode().equals(product.getIsSeckill())) {
            throw new BusinessException("秒杀商品不允许删除");
        }
        if (ProductShelfStatus.ON_SHELF.getCode().equals(product.getStatus())) {
            throw new BusinessException("商品已上架，请先下架再删除");
        }

        // 占用校验：存在在途订单（待付款/已付款/已发货）或进行中的退款/售后单时拒绝删除。
        productOperateGuard.assertReleasable(id, "删除");

        // 原子删除：WHERE 带 version
        int rows = productMapper.deleteProductWithVersion(id, product.getVersion());
        if (rows == 0) {
            log.warn("删除商品失败，商品ID={}，版本已变更", id);
            throw new BusinessException("删除失败，商品可能已被删除或状态已变更");
        }

        log.info("商品逻辑删除成功，ID：{}", id);

        try {
            clearPageCache();
            clearProductDetailCache(id);
        } catch (Exception e) {
            log.error("删除商品缓存失败，id={}", id, e);
        }
    }


    @Override
    public void updateStatus(Long id, Integer status) {
        log.info("开始更新商品状态，ID：{}，目标状态：{}", id, status);

        if (status == null || !ProductShelfStatus.isValid(status)) {
            throw new BusinessException("商品状态不合法");
        }
        if (id == null || id <= 0) {
            throw new BusinessException("商品ID不合法");
        }

        Product product = this.getById(id);
        if (product == null || product.getDeleted() == 1) {
            throw new BusinessException("商品不存在");
        }
        if (SeckillFlag.YES.getCode().equals(product.getIsSeckill())) {
            throw new BusinessException("秒杀商品不允许修改状态");
        }
        if (status.equals(product.getStatus())) {
            throw new BusinessException("商品状态无需变化");
        }
        if (ProductShelfStatus.ON_SHELF.getCode().equals(status)
                && product.getStock() <= 0) {
            throw new BusinessException("商品库存不足，无法上架");
        }

        // 下架前占用校验：存在在途订单或进行中的退款/售后单时拒绝下架。
        if (ProductShelfStatus.OFF_SHELF.getCode().equals(status)) {
            productOperateGuard.assertReleasable(id, "下架");
        }

        // 原子更新：WHERE 带旧状态、旧 version
        Integer oldStatus = product.getStatus();
        Integer oldVersion = product.getVersion();

        int rows = productMapper.updateStatusWithVersion(id, oldStatus, status, oldVersion);
        if (rows == 0) {
            log.warn("更新商品状态失败，商品ID={}，状态或版本已变更", id);
            throw new BusinessException("更新失败，商品状态已被其他管理员修改，请刷新重试");
        }

        log.info("商品状态更新成功，ID：{}，{} -> {}", id, oldStatus, status);

        try {
            clearPageCache();
            if (ProductShelfStatus.OFF_SHELF.getCode().equals(status)) {
                clearProductDetailCache(id);
            }
        } catch (Exception e) {
            log.error("删除商品缓存失败，id={}", id, e);
        }
    }

    // 生成分页缓存Key
    private String buildPageCacheKey(Integer pageNum, Integer pageSize, Long categoryId, String keyword) {
        StringBuilder sb = new StringBuilder(PRODUCT_PAGE);
        sb.append(pageNum).append(":").append(pageSize);
        sb.append(":").append(categoryId != null ? categoryId : "0");

        if (StringUtils.hasText(keyword)) {
            String hashed = DigestUtils.md5DigestAsHex(keyword.getBytes()).substring(0, 8);
            sb.append(":").append(hashed);
        } else {
            sb.append(":all");
        }

        return sb.toString();
    }

    // 删除所有分页缓存
    private void clearPageCache() {
        String pattern = PRODUCT_PAGE + "*";

        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(100)
                .build();

        // 分批删除，每 1000 个一批
        Set<String> batch = new HashSet<>(1000);
        long totalDeleted = 0;

        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());

                if (batch.size() >= 1000) {
                    stringRedisTemplate.delete(batch);
                    totalDeleted += batch.size();
                    batch.clear();
                }
            }
        }

        // 删最后一批
        if (!batch.isEmpty()) {
            stringRedisTemplate.delete(batch);
            totalDeleted += batch.size();
        }

        log.info("删除所有分页缓存，pattern={}, count={}", pattern, totalDeleted);
    }

    // 用 SCAN 替代 KEYS，避免阻塞 Redis
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            try (Cursor<byte[]> cursor = connection.scan(
                    ScanOptions.scanOptions().match(pattern).count(100).build())) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next()));
                }
            }
            return keys;
        });
        return keys;
    }


    // 清除所有商品列表相关缓存
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearProductListCache() {

        // 删除所有分页缓存
        Set<String> keys = scanKeys(PRODUCT_PAGE   + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.info("已清除商品分页缓存，共{}个", keys.size());
        }
    }

    // 清除指定商品详情缓存
    @Override
    public void clearProductDetailCache(Long id) {

        String detailCacheKey = PRODUCT_DETAIL +id;
        stringRedisTemplate.delete(detailCacheKey);

        log.info("已清除商品详情缓存，id={}", id);
    }

    // 使用乐观锁扣减库存（返回 boolean）
    @Override
    public boolean deductStockWithOptimisticLock(Long productId, Integer quantity, Integer version) {
        int rows = productMapper.deductStockWithVersion(productId, quantity, version);
        if (rows > 0) {
            log.info("【库存扣减】成功，productId={}, quantity={}, version={}",
                    productId, quantity, version);
            return true;
        }
        log.warn("【库存扣减】失败（库存不足或版本冲突），productId={}, quantity={}, version={}",
                productId, quantity, version);
        return false;
    }

    @Override
    public void addProductDetailCache(Long id, Product product) {
        String detailCacheKey = PRODUCT_DETAIL + id;

        // 基础过期时间 + 随机波动（0~10 分钟），防雪崩
        long expireMinutes = PRODUCT_DETAIL_MINUTES
                + ThreadLocalRandom.current().nextLong(0, 11);

        stringRedisTemplate.opsForValue().set(
                detailCacheKey,
                JSON.toJSONString(product),
                expireMinutes,
                TimeUnit.MINUTES);

        log.info("已添加商品详情缓存，id={}, 过期时间={}分钟", id, expireMinutes);
    }
}