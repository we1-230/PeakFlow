package com.zhou.peakflow.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhou.peakflow.enums.ProductShelfStatus;
import com.zhou.peakflow.constant.SeckillConstants;
import com.zhou.peakflow.enums.SeckillFlag;
import com.zhou.peakflow.dto.order.SeckillMessageDto;
import com.zhou.peakflow.dto.order.SeckillOrderDto;
import com.zhou.peakflow.dto.product.AddSeckillProductDto;
import com.zhou.peakflow.dto.product.SelectProductDto;
import com.zhou.peakflow.dto.product.UpdateSeckillProductDto;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.guard.ProductOperateGuard;
import com.zhou.peakflow.mapper.CartMapper;
import com.zhou.peakflow.mapper.ProductMapper;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.pojo.Product;
import com.zhou.peakflow.service.ProductService;
import com.zhou.peakflow.service.SeckillOrderService;
import com.zhou.peakflow.vo.PageVo;
import com.zhou.peakflow.vo.SeckillResultVo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.zhou.peakflow.constant.CacheExpireConstants.*;
import static com.zhou.peakflow.constant.OrderConstants.SECKILL_TOPIC;
import static com.zhou.peakflow.constant.RedisKeyConstant.CART;
import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_ORDER_SUCCESS;
import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_PRODUCT_DETAIL;
import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_PRODUCT_PAGE;
import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_RESULT;
import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_STOCK;
import static com.zhou.peakflow.enums.ResultCode.SYSTEM_ERROR;

/**
 * 秒杀服务实现：入口限流 + Redis 预扣 + MQ 异步落库（含 MQ 不可用同步降级）+ 秒杀商品管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillOrderServiceImpl implements SeckillOrderService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ProductService productService;
    /** 商品删除 / 下架前的占用校验（在途订单 + 未完结退款单），与普通商品共用同一份规则 */
    private final ProductOperateGuard productOperateGuard;
    private final CartMapper cartMapper;
    private final RedissonClient redissonClient;
    private final RocketMQTemplate rocketMQTemplate;
    private final ProductMapper productMapper;
    private final SeckillOrderTxService seckillOrderTxService;
    private final MeterRegistry meterRegistry;

    private DefaultRedisScript<Long> stockDecrScript;

    // ---- 自定义监控指标 ----
    private Counter seckillSuccessCounter;
    private Counter seckillFailCounter;
    private Timer seckillTimer;

    private static final String LOG_PREFIX = "【秒杀服务】";

    // Redis 秒杀库存 Key 前缀（seckill:stock:{productId}）
    private static final String SECKILL_STOCK_KEY = SECKILL_STOCK + ":";

    // 购物车 Hash Key 前缀（cart:{userId}）
    private static final String CART_KEY_PREFIX = CART + ":";

    // 库存扣减 Lua 返回值：库存 Key 缺失（需从 DB 重建后重试）
    private static final long STOCK_KEY_MISSING = -2L;

    @PostConstruct
    public void initLua() {
        stockDecrScript = new DefaultRedisScript<>();
        stockDecrScript.setResultType(Long.class);
        stockDecrScript.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/seckill_decr.lua")));
    }

    @PostConstruct
    public void initMetrics() {
        seckillSuccessCounter = Counter.builder("seckill.success.total")
                .description("秒杀成功总次数")
                .register(meterRegistry);

        seckillFailCounter = Counter.builder("seckill.fail.total")
                .description("秒杀失败总次数")
                .register(meterRegistry);

        seckillTimer = Timer.builder("seckill.duration")
                .description("秒杀接口响应时间")
                .register(meterRegistry);
    }

    // ---- 秒杀入口（限流 + Redis 预扣 + 发 MQ） ----

    @Override
    public String seckill(Long userId, SeckillOrderDto dto) {
        return seckillTimer.record(() -> {

                return doSeckill(userId, dto);

        });
    }

    // 秒杀核心逻辑（与 seckill 分离，方便统一监控埋点）
    private String doSeckill(Long userId, SeckillOrderDto dto) {
        Long productId = dto.getProductId();
        log.info("{}开始秒杀，userId={}, productId={}", LOG_PREFIX, userId, productId);

        // 购买权门闩：同一用户同一商品只允许一单
        if (!acquirePurchaseGate(userId, productId)) {
            throw new BusinessException("您已参与过该秒杀活动");
        }

        // 商品与活动窗口校验（缓存层快速失败，落库前仍有一次 DB 权威校验兜底）
        Product product = getProductDetail(productId);
        if (product == null) {
            releasePurchaseGate(userId, productId);
            throw new BusinessException("商品不存在");
        }

        try {
            validateSeckillProduct(product);
        } catch (BusinessException e) {
            // 校验失败：释放门闩，把重试名额还给用户
            releasePurchaseGate(userId, productId);
            throw new BusinessException(e.getMessage());
        }

        // 预扣刻意放在发消息之前：靠 Redis 单线程的原子性把超卖挡在 DB 之外，
        // MQ 与 DB 只需承接「已通过筛选」的小流量（Key 缺失时从 DB 重建后重试一次）
        deductStock(productId, userId);

        // === 组装消息异步落库 ===
        String requestId = generateRequestId();
        saveWaitingResult(requestId);
        SeckillMessageDto msg = SeckillMessageDto.builder()
                .userId(userId)
                .productId(productId)
                .addressId(dto.getAddressId())
                .requestId(requestId)
                .build();

        try {
            sendSeckillMessage(msg);
            seckillSuccessCounter.increment();
            log.info("{}秒杀请求已通过MQ异步处理，userId={}, productId={}, requestId={}",
                    LOG_PREFIX, userId, productId, requestId);
            return requestId;
        } catch (Exception e) {
            // MQ 不可用 → 降级为同步落库。
            //
            // 这里刻意不回滚 Redis 预扣，原因要讲清楚（很容易被误改）：
            //    降级同步落库不是"放弃这次抢购"，而是"换一条路完成它" ——
            //    degradeToSyncCreate → createSeckillOrder 会照常扣 DB 的 seckill_stock，
            //    于是 Redis 扣 1、DB 也扣 1，两边一致。
            //    如果这里再回滚 Redis，就变成"Redis 还回去、DB 照扣" ⇒ Redis 比 DB 多 1
            //    ⇒ 库存漂移：Redis 显示还能抢，但 DB 已经卖完了，用户抢到却落库失败。
            //
            //    原则：**Redis 预扣是「占名额」，只有"这个名额最终没被用掉"时才该回滚。**
            log.warn("{}MQ发送失败，不回滚Redis预扣（降级同步落库会照常扣DB，两边各扣一次保持一致），"
                            + "直接降级同步落库，userId={}, productId={}",
                    LOG_PREFIX, userId, productId, e);
            degradeToSyncCreate(userId, productId, requestId, msg);
            return requestId;
        }
    }

    // 查询秒杀结果（无结果时返回排队中，前端根据 status 展示）
    @Override
    public SeckillResultVo getSeckillResult(String requestId) {
        String json = stringRedisTemplate.opsForValue().get(SECKILL_RESULT + ":" + requestId);
        if (json == null || json.isEmpty()) {
            return SeckillResultVo.waiting();
        }
        SeckillResultVo result = JSON.parseObject(json, SeckillResultVo.class);
        return result == null ? SeckillResultVo.waiting() : result;
    }

    // 购买权门闩：同一用户同一商品仅放行一次（成功后保留 24h；未落库的失败路径会释放）
    private boolean acquirePurchaseGate(Long userId, Long productId) {
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(SECKILL_ORDER_SUCCESS + ":" + userId + ":" + productId, "1",
                        SECKILL_IDEMPOTENT_HOURS, TimeUnit.HOURS);
        return Boolean.TRUE.equals(acquired);
    }

    // 释放购买权门闩（订单未落库的失败路径、以及订单取消/超时关单时由订单服务调用）
    @Override
    public void releasePurchaseGate(Long userId, Long productId) {
        stringRedisTemplate.delete(SECKILL_ORDER_SUCCESS + ":" + userId + ":" + productId);
    }

    // ---- MQ 消息与降级 ----

    // MQ 同步发送（失败只抛异常，Redis 库存回滚统一由调用方处理，避免双重回滚）
    private void sendSeckillMessage(SeckillMessageDto msg) {
        SendResult sendResult = rocketMQTemplate.syncSend(SECKILL_TOPIC, msg,
                SeckillConstants.MQ_SEND_TIMEOUT_MILLIS);
        if (!SendStatus.SEND_OK.equals(sendResult.getSendStatus())) {
            throw new BusinessException("MQ发送失败，状态=" + sendResult.getSendStatus());
        }
    }

    // MQ 不可用降级：同步落库（事务由 TxService 代理保证）
    private void degradeToSyncCreate(Long userId, Long productId, String requestId, SeckillMessageDto msg) {
        try {
            Order order = seckillOrderTxService.createSeckillOrder(userId, productId, msg.getAddressId());
            seckillOrderTxService.writeSuccessResult(requestId, order.getId(), order.getOrderNo());
            sendTimeoutMessageQuietly(order.getId());
            seckillSuccessCounter.increment();
            log.info("{}同步降级落库成功，userId={}, productId={}, orderNo={}",
                    LOG_PREFIX, userId, productId, order.getOrderNo());
        } catch (BusinessException e) {
            releasePurchaseGate(userId, productId);
            seckillOrderTxService.writeFailResult(requestId, e.getMessage());
            log.warn("{}同步降级业务失败，requestId={}, userId={}, productId={}, reason={}",
                    LOG_PREFIX, requestId, userId, productId, e.getMessage());
        } catch (Exception e) {
            releasePurchaseGate(userId, productId);
            seckillOrderTxService.writeFailResult(requestId, "系统繁忙，请稍后重试");
            log.error("{}同步降级落库异常，requestId={}, userId={}, productId={}",
                    LOG_PREFIX, requestId, userId, productId, e);
        }
    }

    // 超时消息发送失败只留痕告警：订单已落库不能因此回滚，生产者同步发送内部自带重试
    private void sendTimeoutMessageQuietly(Long orderId) {
        try {
            seckillOrderTxService.sendSeckillTimeoutDelayMessage(orderId);
        } catch (Exception e) {
            log.error("{}超时消息发送失败，该订单无法自动关单，需人工关注，orderId={}", LOG_PREFIX, orderId, e);
        }
    }

    // ---- 秒杀商品列表（缓存 + 分布式锁防击穿） ----

    // 查询秒杀商品列表：全量缓存，过期时间短（秒杀数据变化快）
    @Override
    public PageVo<Product> getSeckillProducts(SelectProductDto dto) {
        Integer pageNum = dto.getPageNum();
        Integer pageSize = dto.getPageSize() != null ? dto.getPageSize() : SeckillConstants.DEFAULT_PAGE_SIZE;
        if (pageSize > 100) {
            pageSize = 100; // 双保险：Controller 层 @Max 已限制，此处兜底防缓存污染
        }
        // 先查缓存
        String cacheKey = buildPageKey(pageNum, pageSize, dto.getCategoryId(), dto.getKeyword());
        String lockKey = cacheKey + ":lock";

        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (json != null) {
            return json.isEmpty() ? new PageVo<>() : JSON.parseObject(json, PageVo.class);
        }
        // 缓存未命中，加分布式锁串行回源
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 只传等待时间、不指定持有时间：交给 Redisson 看门狗自动续期
            boolean locked = lock.tryLock(1,  TimeUnit.SECONDS);

            if (!locked) {
                // 抢锁失败：等 100ms 后再查一次缓存（大概率拿到别人刚回填的结果）
                return retryCacheOrEmpty(cacheKey);
            }

            // 双重检查：拿到锁后再查一次缓存
            String retryJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (retryJson != null) {
                return retryJson.isEmpty() ? new PageVo<>() : JSON.parseObject(retryJson, PageVo.class);
            }
            // 双重检查后仍无缓存，回源数据库

            Page<Product> page = new Page<>(pageNum, pageSize);
            QueryWrapper<Product> queryWrapper = new QueryWrapper<>();
            // 有效性过滤：活动进行中 + 有库存 + 上架的秒杀商品
            queryWrapper.eq("is_seckill", SeckillFlag.YES.getCode())
                    .eq("status", ProductShelfStatus.ON_SHELF.getCode())
                    .le("seckill_start_time", LocalDateTime.now())
                    .ge("seckill_end_time", LocalDateTime.now())
                    .gt("seckill_stock", 0);
            if (dto.getCategoryId() != null) {
                queryWrapper.eq("category_id", dto.getCategoryId());
            }
            if (dto.getKeyword() != null && !dto.getKeyword().isBlank()) {
                queryWrapper.like("name", dto.getKeyword());
            }
            Page<Product> p = productService.page(page, queryWrapper);
            List<Product> products = p.getRecords();

            if (products.isEmpty()) {
                // 空结果缓存 60 秒防穿透
                stringRedisTemplate.opsForValue().set(cacheKey, "", NULL_CACHE_SECONDS, TimeUnit.SECONDS);
                return new PageVo<>();
            }

            PageVo<Product> pageVo = new PageVo<>();
            pageVo.setList(products);
            pageVo.setTotal(p.getTotal());
            pageVo.setPageNum(pageNum);
            pageVo.setPageSize(pageSize);

            // 过期时间加随机抖动，防止同批缓存同时失效引发集中回源
            long finalExpire = SECKILL_PRODUCT_PAGE_MINUTES + new Random().nextInt(3);
            stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(pageVo), finalExpire, TimeUnit.MINUTES);
            return pageVo;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{}获取分布式锁被中断", LOG_PREFIX, e);
            throw new BusinessException("查询秒杀商品列表异常");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 抢锁失败后的兜底：短等后重查缓存，仍无则返回空页
    private PageVo<Product> retryCacheOrEmpty(String cacheKey) {
        try {
            Thread.sleep(100);
            String retryJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (retryJson != null && !retryJson.isEmpty()) {
                return JSON.parseObject(retryJson, PageVo.class);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{}等待缓存重试被中断", LOG_PREFIX, e);
        }
       throw new BusinessException(SYSTEM_ERROR.getMessage());
    }

    // ---- 秒杀商品详情（缓存 + 分布式锁防击穿 + 实时库存） ----

    @Override
    public Product getProductDetail(Long productId) {
        if (productId == null || productId <= 0) {
            log.warn("{}参数非法，productId={}", LOG_PREFIX, productId);
            throw new BusinessException("商品不存在");
        }

        String cacheKey = SECKILL_PRODUCT_DETAIL + productId;
        String lockKey = cacheKey + ":lock";

        // ---- 1. 先查缓存 ----
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (json != null) {
            // 空值：数据库确实没有这个秒杀商品
            if (json.isEmpty()) {
                return null;
            }
            Product product = JSON.parseObject(json, Product.class);
            // 实时库存以 Redis 为准（缓存里的库存是快照，可能是旧的）
            product.setSeckillStock(syncRedisStock(productId));
            return product;
        }

        // ---- 2. 缓存未命中，加分布式锁防击穿 ----
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean locked = lock.tryLock(1, 3, TimeUnit.SECONDS);
            if (!locked) {
                // 抢锁失败：等 100ms 再查一次缓存（大概率拿到别人刚回填的结果）
                return retryCacheOrNull(cacheKey, productId);
            }

            // ---- 3. 双重检查：拿到锁后再查一次缓存 ----
            String retryJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (retryJson != null) {
                if (retryJson.isEmpty()) {
                    return null;
                }
                Product product = JSON.parseObject(retryJson, Product.class);
                product.setSeckillStock(syncRedisStock(productId));
                return product;
            }

            // ---- 4. 查数据库 ----
            Product product = productService.getById(productId);
            if (product == null || !SeckillFlag.YES.getCode().equals(product.getIsSeckill())) {
                // 不存在或非秒杀商品：缓存空值防穿透
                log.warn("{}数据库无此秒杀商品，缓存空值防穿透，productId={}", LOG_PREFIX, productId);
                stringRedisTemplate.opsForValue().set(cacheKey, "", NULL_CACHE_SECONDS, TimeUnit.SECONDS);
                return null;
            }

            // ---- 5. 写缓存 ----
            // 秒杀库存快照挪到 stock 字段，seckillStock 由实时同步填充
            Product cacheProduct = new Product();
            BeanUtils.copyProperties(product, cacheProduct);
            if (product.getSeckillStock() != null) {
                cacheProduct.setStock(product.getSeckillStock());
            }
            cacheProduct.setSeckillStock(null);

            long expireMinute = SECKILL_PRODUCT_DETAIL_MINUTES + ThreadLocalRandom.current().nextInt(2);
            stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(cacheProduct),
                    expireMinute, TimeUnit.MINUTES);

            // ---- 6. 返回时同步实时库存 ----
            product.setSeckillStock(syncRedisStock(productId));
            return product;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{}获取分布式锁被中断，productId={}", LOG_PREFIX, productId, e);
            throw new BusinessException("查询商品详情异常");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Product retryCacheOrNull(String cacheKey, Long productId) {
        try {
            Thread.sleep(100);
            String retryJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (retryJson != null) {
                if (retryJson.isEmpty()) {
                    return null;  // 空值：数据库确实没这个秒杀商品
                }
                Product product = JSON.parseObject(retryJson, Product.class);
                product.setSeckillStock(syncRedisStock(productId));
                return product;
            }
            // 抢锁失败 + 短等后还没缓存 = 缓存正在重建
            // 不是"商品不存在"，是"系统忙"
            log.warn("{}抢锁失败且短等后仍未命中缓存，缓存可能正在重建，productId={}",
                    LOG_PREFIX, productId);
            throw new BusinessException("系统繁忙，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{}等待缓存重试被中断，productId={}", LOG_PREFIX, productId, e);
            throw new BusinessException("系统繁忙，请稍后重试");
        }
    }
    // 实时查询秒杀库存
    private int syncRedisStock(Long productId) {
        String stockKey = SECKILL_STOCK_KEY + productId;
        String stockStr = stringRedisTemplate.opsForValue().get(stockKey);
        if (stockStr != null) {
            try {
                return Integer.parseInt(stockStr);
            } catch (NumberFormatException e) {
                // 库存缓存格式异常 = 严重故障，不自动修复，抛异常告警
                log.error("{}【严重】Redis库存格式异常，需人工介入！productId={}, stockStr={}",
                        LOG_PREFIX, productId, stockStr, e);
                throw new BusinessException("系统繁忙，请稍后重试");
            }
        }
        // Key 缺失：从 DB 重建（正常路径，不是故障）
        Product dbProduct = productMapper.selectById(productId);
        int dbStock = dbProduct == null || dbProduct.getSeckillStock() == null
                ? 0 : dbProduct.getSeckillStock();
        stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(dbStock),
                SECKILL_STOCK_HOURS, TimeUnit.HOURS);
        log.info("{}Redis库存缺失，已从DB重建，productId={}, dbStock={}", LOG_PREFIX, productId, dbStock);
        return dbStock;
    }

    // ---- 秒杀商品管理（管理员） ----

    // 写入指定商品详情缓存（秒杀库存快照挪到 stock 字段，seckillStock 由实时同步填充）
    private void addProductDetailCache(Long id, Product product) {
        String detailCacheKey = SECKILL_PRODUCT_DETAIL + id;
        long expireMinute = SECKILL_PRODUCT_DETAIL_MINUTES + new Random().nextInt(2);

        Product cacheProduct = new Product();
        BeanUtils.copyProperties(product, cacheProduct);

        if (product.getSeckillStock() != null) {
            cacheProduct.setStock(product.getSeckillStock());
        }
        cacheProduct.setSeckillStock(null);

        stringRedisTemplate.opsForValue().set(detailCacheKey, JSON.toJSONString(cacheProduct),
                expireMinute, TimeUnit.MINUTES);
    }

    // 新增秒杀商品（秒杀域专用 DTO；字段显式赋值，不依赖 BeanUtils 的字段名隐式匹配）
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addProduct(AddSeckillProductDto dto) {
        log.info("{}新增秒杀商品，name={}", LOG_PREFIX, dto.getName());

        // 秒杀库存必须明确给出：非 Web 直调（不经 @Valid）时也只能靠这里兜住
        if (dto.getSeckillStock() == null || dto.getSeckillStock() < 0) {
            throw new BusinessException("秒杀库存不能为空且不能小于0");
        }
        // 价格与时间窗的跨字段规则（@NotNull 只保证非空，管不了两个字段之间的关系）
        validateSeckillBusinessRule(dto.getPrice(), dto.getSeckillPrice(),
                dto.getSeckillStartTime(), dto.getSeckillEndTime());

        Product product = new Product();
        // 显式逐字段赋值，不用 BeanUtils.copyProperties：
        product.setName(dto.getName());
        product.setDescription(dto.getDescription());
        product.setDetail(dto.getDetail());
        product.setImage(dto.getImage());
        product.setPrice(dto.getPrice());
        product.setSeckillPrice(dto.getSeckillPrice());
        product.setSeckillStock(dto.getSeckillStock());
        product.setSeckillStartTime(dto.getSeckillStartTime());
        product.setSeckillEndTime(dto.getSeckillEndTime());
        product.setCategoryId(dto.getCategoryId());

        // 秒杀商品不走普通库存（普通下单入口对秒杀商品直接拒绝），置 0 避免语义混淆
        product.setStock(0);
        product.setStatus(ProductShelfStatus.ON_SHELF.getCode());
        product.setSoldCount(0);
        product.setVersion(0);
        product.setIsSeckill(SeckillFlag.YES.getCode());

        // 保存，唯一索引兜底（防并发下的重复名称）
        try {
            productService.save(product);
        } catch (DuplicateKeyException e) {
            log.warn("{}并发插入重复商品名称，name={}", LOG_PREFIX, dto.getName());
            throw new BusinessException("商品已存在，请勿重复添加");
        }

        // 初始化 Redis 秒杀库存 Key。
        // 新增时商品直接就是「上架」状态，不会走 updateStatus 的上架分支，
        // 所以这里必须自己初始化：入口预扣的 Lua 要求该 Key 存在（缺失返回 -2）。
        try {
            stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + product.getId(),
                    String.valueOf(dto.getSeckillStock()), SECKILL_STOCK_HOURS, TimeUnit.HOURS);
            log.info("{}新增秒杀商品已初始化Redis库存，productId={}, stock={}",
                    LOG_PREFIX, product.getId(), dto.getSeckillStock());
        } catch (Exception e) {
            log.error("{}新增秒杀商品初始化Redis库存失败（首次抢购时会从 DB 重建），productId={}",
                    LOG_PREFIX, product.getId(), e);
        }

        try {
            // 顺手重建详情缓存，避免新增后的第一次详情查询回源 DB
            addProductDetailCache(product.getId(), product);
            clearProductListCache();
        } catch (Exception e) {
            // 缓存失败不影响主流程，靠过期兜底
            log.error("{}新增秒杀商品后写缓存失败，productId={}",
                    LOG_PREFIX, product.getId(), e);
        }

        log.info("{}新增秒杀商品成功，productId={}, seckillPrice={}, seckillStock={}, 活动时间={} ~ {}",
                LOG_PREFIX, product.getId(), dto.getSeckillPrice(), dto.getSeckillStock(),
                dto.getSeckillStartTime(), dto.getSeckillEndTime());
    }

    // 更新秒杀商品（秒杀域专用 DTO + 乐观锁）
    @Override
    public void updateProduct(UpdateSeckillProductDto dto) {
        log.info("{}更新秒杀商品，productId={}", LOG_PREFIX, dto.getId());

        validateSeckillBusinessRule(dto.getPrice(), dto.getSeckillPrice(),
                dto.getSeckillStartTime(), dto.getSeckillEndTime());

        Product product = productService.getById(dto.getId());
        if (Objects.isNull(product)) {
            throw new BusinessException("商品不存在或已删除");
        }
        if (SeckillFlag.NO.getCode().equals(product.getIsSeckill())) {
            throw new BusinessException("该商品不是秒杀商品，不允许在此修改");
        }

        // 防御：活动进行中禁止修改商品信息
        // （改价/改活动时间会让 Redis 预扣库存与详情缓存快照同 DB 失配，
        //   典型后果是「页面显示一个价、下单按另一个价」）
        assertNotInSeckillActivity(product, "修改商品信息");

        // 先记下库中原值：用于「可选字段缺省时保留原值」与乐观锁版本回填
        Integer dbVersion = product.getVersion();
        Integer dbStatus = product.getStatus();
        Integer dbSeckillStock = product.getSeckillStock();

        product.setName(dto.getName());
        product.setDescription(dto.getDescription());
        product.setDetail(dto.getDetail());
        product.setImage(dto.getImage());
        product.setPrice(dto.getPrice());
        product.setSeckillPrice(dto.getSeckillPrice());
        product.setSeckillStartTime(dto.getSeckillStartTime());
        product.setSeckillEndTime(dto.getSeckillEndTime());
        product.setCategoryId(dto.getCategoryId());
        // 库存可选：不传则保留库中原值。
        // 若被覆写成 NULL，商品会从列表的「seckill_stock > 0」条件里直接消失，属于静默故障。
        boolean stockChanged = false;
        if (dto.getSeckillStock() != null) {
            product.setSeckillStock(dto.getSeckillStock());
            stockChanged = true;
        }
        // 状态不通过编辑接口修改：上下架统一走 updateStatus()，
        // 那里有「存在未完成订单禁止下架」的校验；编辑接口若能改状态，等于把这条业务规则旁路了。
        product.setStatus(dbStatus);
        // 版本：前端带了就用它做真正的乐观锁，没带则回落到库中当前版本（与改造前行为一致）
        product.setVersion(dto.getVersion() != null ? dto.getVersion() : dbVersion);

        int rows = productMapper.updateProductWithOptimisticLock(product);
        if (rows == 0) {
            log.warn("{}乐观锁冲突，更新秒杀商品失败，productId={}, version={}",
                    LOG_PREFIX, dto.getId(), product.getVersion());
            throw new BusinessException("商品已被其他管理员修改，请刷新页面后重试");
        }

        // 库存变了：同步 Redis 库存（失败不影响主流程，靠预热兜底）
        if (stockChanged) {
            try {
                stringRedisTemplate.opsForValue().set(
                        SECKILL_STOCK_KEY + product.getId(),
                        String.valueOf(product.getSeckillStock()),
                        SECKILL_STOCK_HOURS, TimeUnit.HOURS);
                log.info("{}同步Redis秒杀库存，productId={}, stock={}",
                        LOG_PREFIX, product.getId(), product.getSeckillStock());
            } catch (Exception e) {
                log.error("{}同步Redis秒杀库存失败，productId={}, stock={}",
                        LOG_PREFIX, product.getId(), product.getSeckillStock(), e);
                // 不抛，靠预热/重建兜底
            }

            try {
                addProductDetailCache(product.getId(), product);
                clearProductListCache();
            } catch (Exception e) {
                // 缓存失败不影响主流程，靠过期兜底
                log.error("{}新增秒杀商品后清缓存失败，productId={}",
                        LOG_PREFIX, product.getId(), e);
            }
        }
    }

    // 删除秒杀商品（在途订单 / 未完结退款单 / 仍在售后保障期内，任一命中即拒绝）
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProduct(Long id) {
        log.info("{}删除秒杀商品，productId={}", LOG_PREFIX, id);

        if (id == null || id <= 0) {
            throw new BusinessException("商品ID不存在或非法");
        }

        Product product = productService.getById(id);
        if (product == null) {
            throw new BusinessException("商品不存在或已删除");
        }
        if (SeckillFlag.NO.getCode().equals(product.getIsSeckill())) {
            throw new BusinessException("普通商品不允许在此删除");
        }

        // 防御：活动进行中禁止删除（商品突然消失会让正在抢购的用户与已产生的订单无所适从）
        assertNotInSeckillActivity(product, "删除");

        // 占用校验：在途订单 + 进行中的退款/售后单 + 仍在售后保障期内的已完成订单。
        // 规则与普通商品完全一致，共用 ProductOperateGuard，不要再各写一份。
        productOperateGuard.assertReleasable(id, "删除");

        // 条件更新：只更新 deleted = 0 的记录，防止并发重复删除
        UpdateWrapper<Product> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", id);
        updateWrapper.eq("deleted", 0);          // 关键：加上未删除条件
        updateWrapper.set("deleted", 1);

        boolean updated = productService.update(null, updateWrapper);
        if (!updated) {
            // 影响行数为 0，说明并发下已被其他线程删除
            throw new BusinessException("商品已被删除，请勿重复操作");
        }

        try {
            // 删除后必须「清」缓存，不能「写」缓存：
            // 回写 addProductDetailCache 会让一个已删除的商品继续从缓存里被访问到。
            clearProductDetailCache(id);
            clearSeckillStockCache(id);
            clearProductListCache();
        } catch (Exception e) {
            // 缓存失败不影响主流程，靠过期兜底
            log.error("{}删除秒杀商品后清缓存失败，productId={}", LOG_PREFIX, id, e);
        }

        // 商品已删除，购物车里的引用要一起清掉，
        // 否则用户要等到结算时才发现商品不可购买，体验很差
        try {
            cleanCartByProduct(id);
        } catch (Exception e) {
            log.error("{}删除秒杀商品后清购物车失败，productId={}", LOG_PREFIX, id, e);
        }

        log.info("{}秒杀商品已逻辑删除，productId={}", LOG_PREFIX, id);
    }

    // 上下架秒杀商品（下架存在未完成订单时拒绝）
    @Override
    public void updateStatus(Long id, Integer status) {
        log.info("{}上下架秒杀商品，productId={}, status={}", LOG_PREFIX, id, status);

        if (status == null || !ProductShelfStatus.isValid(status)) {
            throw new BusinessException("商品状态不合法");
        }
        if (id == null || id <= 0) {
            throw new BusinessException("商品ID不合法");
        }

        Product product = productService.getById(id);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (SeckillFlag.NO.getCode().equals(product.getIsSeckill())) {
            throw new BusinessException("普通商品不允许在此修改");
        }
        if (status.equals(product.getStatus())) {
            throw new BusinessException("商品状态无需变化");
        }

        // 防御：活动进行中禁止上下架
        // （下架会直接中断正在进行的活动；上架会让活动提前开跑）
        assertNotInSeckillActivity(product, "上下架");

        if (status.equals(ProductShelfStatus.OFF_SHELF.getCode())) {
            // 下架前校验：在途订单 + 进行中的退款/售后单，与普通商品共用同一份规则
            productOperateGuard.assertReleasable(id, "下架");
        }

        UpdateWrapper<Product> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", id)
                .eq("version", product.getVersion())
                .set("status", status)
                .set("version", product.getVersion() + 1)
                .set("update_time", LocalDateTime.now());

        boolean success = productService.update(updateWrapper);
        if (!success) {
            log.warn("{}乐观锁冲突，更新秒杀商品状态失败，productId={}", LOG_PREFIX, id);
            throw new BusinessException("商品状态已被其他管理员修改，请刷新后重试");
        }

        if (ProductShelfStatus.ON_SHELF.getCode().equals(status)) {
            // 上架：重建详情缓存。

            try {
                clearProductListCache();
            } catch (Exception e) {
                // 缓存失败不影响主流程，靠过期兜底
                log.error("{}上架秒杀商品后刷缓存失败，productId={}", LOG_PREFIX, id, e);
            }
        } else {
            // 下架：清详情缓存 + 删 Redis 库存 Key（两个方法分开调，避免"顺手删了"被看漏）
            try {
                clearProductDetailCache(id);
                clearSeckillStockCache(id);
                clearProductListCache();
            } catch (Exception e) {
                // 缓存失败不影响主流程，靠过期兜底
                log.error("{}下架秒杀商品后清缓存失败，productId={}", LOG_PREFIX, id, e);
            }

            // 下架后购物车里的引用一起清掉，否则用户结算时才发现商品已下架
            try {
                cleanCartByProduct(id);
            } catch (Exception e) {
                log.error("{}下架秒杀商品后清购物车失败，productId={}", LOG_PREFIX, id, e);
            }
        }
        log.info("{}秒杀商品状态更新成功，productId={}, status={}", LOG_PREFIX, id, status);
    }


    // 回补 Redis 秒杀库存预扣（订单取消/超时关单/退款时由订单服务调用）
    @Override
    public void rollbackRedisStock(Long productId, Integer quantity) {
        seckillOrderTxService.rollbackRedisStock(productId, quantity);
    }

    // ---- 缓存预热 ----

    /**
     * 秒杀缓存预热（管理员手动触发 / 活动前预热）
     */
    @Override
    public void preheatAllSeckillCache() {
        long start = System.currentTimeMillis();
        log.info("{}====== 开始秒杀缓存预热 ======", LOG_PREFIX);

        // 这三个条件必须与 getSeckillProducts() 的过滤条件完全一致。
        // 之前漏了活动时间窗，导致「还没开始 / 已经结束」的活动也被预热，白写 Redis。
        QueryWrapper<Product> wrapper = new QueryWrapper<>();
        wrapper.eq("is_seckill", SeckillFlag.YES.getCode())
                .eq("status", ProductShelfStatus.ON_SHELF.getCode())
                .le("seckill_start_time", LocalDateTime.now())
                .ge("seckill_end_time", LocalDateTime.now())
                .gt("seckill_stock", 0);

        List<Product> productList = productService.list(wrapper);

        Set<Long> validIds = new HashSet<>();
        int successCount = 0;
        int failCount = 0;

        // 2. 补齐：详情缓存 + 库存 Key
        for (Product product : productList) {
            Long productId = product.getId();
            // 先登记为「有效」，即使下面预热失败也不会在第 3 步被误清
            validIds.add(productId);
            try {
                addProductDetailCache(productId, product);

                // 初始化语义：入口预扣的 Lua 要求库存 Key 必须存在（缺失返回 -2）。
                stringRedisTemplate.opsForValue().setIfAbsent(
                        SECKILL_STOCK_KEY + productId,
                        String.valueOf(product.getSeckillStock()),
                        SECKILL_STOCK_HOURS, TimeUnit.HOURS);

                successCount++;
            } catch (Exception e) {
                // 单个商品失败不影响其余商品
                failCount++;
                log.error("{}商品预热失败，productId={}", LOG_PREFIX, productId, e);
            }
        }

        // 3. 清理：Redis 里存在、但已不属于有效集合的库存 Key
        int cleanedCount = cleanStaleStockKeys(validIds);

        // 4. 列表首页缓存
        // 局限说明：列表缓存 key 按「页码:每页条数:
        try {
            SelectProductDto dto = new SelectProductDto();
            dto.setPageNum(1);
            dto.setPageSize(SeckillConstants.DEFAULT_PAGE_SIZE);
            getSeckillProducts(dto);
        } catch (Exception e) {
            failCount++;
            log.error("{}列表首页缓存预热失败", LOG_PREFIX, e);
        }

        log.info("{}====== 秒杀缓存预热完成 ====== 有效商品={}，成功={}，失败={}，清理陈旧Key={}，耗时={}ms",
                LOG_PREFIX, validIds.size(), successCount, failCount, cleanedCount,
                System.currentTimeMillis() - start);
    }

    /**
     * 清理 Redis 中「已不属于当前有效秒杀商品」的库存 Key。

     * @param validIds 当前有效的秒杀商品 ID 集合
     * @return 实际清理掉的 Key 数量
     */
    private int cleanStaleStockKeys(Set<Long> validIds) {
        Set<String> stockKeys = scanKeys(SECKILL_STOCK_KEY + "*");
        if (CollectionUtils.isEmpty(stockKeys)) {
            return 0;
        }

        Set<String> staleKeys = new HashSet<>();
        for (String key : stockKeys) {
            // key 形如 seckill:stock:{productId}
            String idPart = key.substring(key.lastIndexOf(':') + 1);
            Long productId;
            try {
                productId = Long.parseLong(idPart);
            } catch (NumberFormatException e) {
                // key 结构不符合预期（人工误写 / 历史遗留），一并清掉
                log.warn("{}发现结构异常的库存Key，一并清理，key={}", LOG_PREFIX, key);
                staleKeys.add(key);
                continue;
            }
            if (!validIds.contains(productId)) {
                staleKeys.add(key);
            }
        }

        if (staleKeys.isEmpty()) {
            return 0;
        }
        try {
            stringRedisTemplate.delete(staleKeys);
            log.info("{}清理陈旧库存Key完成，count={}, keys={}", LOG_PREFIX, staleKeys.size(), staleKeys);
            return staleKeys.size();
        } catch (Exception e) {
            log.error("{}清理陈旧库存Key失败，待下次预热重试，count={}",
                    LOG_PREFIX, staleKeys.size(), e);
            return 0;
        }
    }


    // ---- 校验与辅助方法 ----

    /**
     * 防御性校验：商品正处于秒杀活动进行中时，禁止一切会破坏活动一致性的操作（删 / 下架 / 改价 / 改时间）。
     * 双向禁止的理由：删或下架会让抢到一半的用户失去商品；改价或改时间会让 Redis 预扣库存与详情缓存
     * 快照和 DB 失配，典型后果是「页面显示一个价、下单按另一个价」。与 ProductOperateGuard 分工互补
     * （那边是订单侧跨表占用校验，这里是秒杀专属的活动时间窗校验），两者都要过；时间未配置则不拦截。
     *
     * @param product 必须是库里的当前商品而非 DTO——本次请求可能正在改活动时间
     * @param action  操作名称，用于拼接提示语
     */
    private void assertNotInSeckillActivity(Product product, String action) {
        LocalDateTime start = product.getSeckillStartTime();
        LocalDateTime end = product.getSeckillEndTime();
        if (start == null || end == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        // 闭区间 [start, end] 都算活动期内，与 validateSeckillProduct 的判定口径一致：
        // 那边认为"不在有效期" = start > now || end < now，取反即得此处条件。
        if (!start.isAfter(now) && !end.isBefore(now)) {
            log.warn("{}商品正处于秒杀活动期内，拒绝{}，productId={}, 活动时间={} ~ {}",
                    LOG_PREFIX, action, product.getId(), start, end);
            throw new BusinessException("该商品正在秒杀活动进行中（"
                    + start.toLocalDate() + " ~ " + end.toLocalDate()
                    + "），活动期间不允许" + action + "，请等活动结束后再操作");
        }
    }

    // 活动窗口与上架校验（缓存快速失败；落库前 TxService 还有 DB 权威校验兜底）
    private void validateSeckillProduct(Product product) {

        if (product == null || !SeckillFlag.YES.getCode().equals(product.getIsSeckill())) {

            throw new BusinessException("商品不存在或非秒杀商品");
        }
        if (!ProductShelfStatus.ON_SHELF.getCode().equals(product.getStatus())) {
            throw new BusinessException("商品已下架");
        }
        if (product.getSeckillStartTime() == null || product.getSeckillEndTime() == null) {
            log.warn("{}秒杀商品时间配置异常，productId={}", LOG_PREFIX, product.getId());
            throw new BusinessException("秒杀商品时间配置异常");
        }
        if (product.getSeckillStartTime().isAfter(LocalDateTime.now())
                || product.getSeckillEndTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("秒杀时间不在有效期内");
        }
    }

    // 秒杀商品业务规则校验：价格关系 + 时间窗（跨字段规则，@NotNull 表达不了）
    private void validateSeckillBusinessRule(BigDecimal price, BigDecimal seckillPrice,
                                             LocalDateTime startTime, LocalDateTime endTime) {
        if (price == null || seckillPrice == null) {
            throw new BusinessException("商品原价与秒杀价不能为空");
        }
        if (startTime == null || endTime == null) {
            throw new BusinessException("秒杀开始时间与结束时间不能为空");
        }
        if (seckillPrice.compareTo(price) > 0) {
            throw new BusinessException("秒杀价不能高于商品原价");
        }
        if (!endTime.isAfter(startTime)) {
            throw new BusinessException("秒杀结束时间必须晚于开始时间");
        }
    }

    /**
     * Redis 预扣库存：Key 缺失（Lua 返回 -2）时从 DB 重建后重试一次；剩余 < 0 视为库存不足。
     * remain 为 null 表示 Lua 没有返回结果（异常路径），与库存不足一并按失败处理 ——
     * 这里一律 fail-closed：宁可少卖一单，也不能放行到 DB 造成超卖。
     */
    private void deductStock(Long productId, Long userId) {
        Long remain = executeDecr(productId);
        if (remain != null && remain == STOCK_KEY_MISSING) {
            reinitStockFromDb(productId);
            remain = executeDecr(productId);
        }
        if (remain == null || remain < 0) {
            releasePurchaseGate(userId, productId);
            throw new BusinessException("库存不足");
        }
    }

    private Long executeDecr(Long productId) {
        return stringRedisTemplate.execute(stockDecrScript,
                Collections.singletonList(SECKILL_STOCK_KEY + productId),
                String.valueOf(SeckillConstants.SECKILL_ORDER_QUANTITY));
    }

    // 从 DB 权威值重建库存 Key（SETNX 防并发重建互相覆盖）
    private void reinitStockFromDb(Long productId) {
        Product product = productMapper.selectById(productId);
        int dbStock = product == null || product.getSeckillStock() == null
                ? 0 : product.getSeckillStock();
        stringRedisTemplate.opsForValue().setIfAbsent(SECKILL_STOCK_KEY + productId,
                String.valueOf(dbStock), SECKILL_STOCK_HOURS, TimeUnit.HOURS);
        log.info("{}Redis库存缺失，已从DB重建，productId={}, dbStock={}", LOG_PREFIX, productId, dbStock);
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 写入排队中占位结果（前端轮询在结果落定前拿到"排队中"）
    private void saveWaitingResult(String requestId) {
        String resultKey = SECKILL_RESULT + ":" + requestId;
        stringRedisTemplate.opsForValue().set(resultKey,
                JSON.toJSONString(SeckillResultVo.waiting()), SECKILL_RESULT_MINUTES, TimeUnit.MINUTES);
    }

    // 清理商品的购物车数据（DB + 缓存）
    private void cleanCartByProduct(Long productId) {
        cartMapper.deleteByProductId(productId);
        String field = String.valueOf(productId);
        Set<String> cartKeys = scanKeys(CART_KEY_PREFIX + "*");
        if (cartKeys != null && !cartKeys.isEmpty()) {
            for (String cartKey : cartKeys) {
                redisTemplate.opsForHash().delete(cartKey, field);
            }
        }
    }

    /**
     * SCAN 代替 KEYS（避免阻塞 Redis）
     *
     * 关键：用 stringRedisTemplate.scan，和删除用同一个模板，
     * 保证 key 的序列化器一致，否则扫出来的 key 删不掉。
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(100)
                .build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    /**
     * 删除所有秒杀商品分页缓存
     *
     * 用 SCAN 分批遍历 + 分批删除：
     * 1. 不收集所有 key（省内存）
     * 2. 每 1000 个删一批（不阻塞 Redis）
     */
    private void clearProductListCache() {
        String pattern = SECKILL_PRODUCT_PAGE + "*";
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(100)
                .build();

        List<String> batch = new ArrayList<>(1000);
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

        log.info("{}删除秒杀商品分页缓存，pattern={}, count={}", LOG_PREFIX, pattern, totalDeleted);
    }

    // 清除指定商品的详情缓存
    private void clearProductDetailCache(Long id) {
        stringRedisTemplate.delete(SECKILL_PRODUCT_DETAIL + id);
    }

    // 清除指定商品的 Redis 秒杀库存 Key。
    // 这个 Key 不只是缓存，它承载着「还能抢多少件」——下架/删除后必须显式删掉，
    // 否则活动已经停了、Redis 里却还留着可抢名额，就是幽灵库存。
    // 单独成方法（而不是塞进 clearProductDetailCache）就是为了让调用方一眼看到这件事。
    private void clearSeckillStockCache(Long id) {
        stringRedisTemplate.delete(SECKILL_STOCK_KEY + id);
    }

    // 生成秒杀商品分页缓存 Key（过滤维度进 key，避免不同关键词/分类串缓存）
    private String buildPageKey(Integer pageNum, Integer pageSize, Long categoryId, String keyword) {
        StringBuilder sb = new StringBuilder(SECKILL_PRODUCT_PAGE);
        sb.append(":").append(pageNum).append(":").append(pageSize);
        sb.append(":").append(categoryId != null ? categoryId : "0");
        String kw = (keyword == null ? "" : keyword.trim());
        sb.append(":").append(kw.isEmpty() ? "0" : Integer.toHexString(kw.hashCode()));
        return sb.toString();
    }
}

