package com.zhou.peakflow.service.impl;

import com.alibaba.fastjson.JSON;
import com.zhou.peakflow.constant.OrderConstants;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.enums.ProductShelfStatus;
import com.zhou.peakflow.constant.SeckillConstants;
import com.zhou.peakflow.enums.SeckillFlag;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.mapper.OrderItemMapper;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.ProductMapper;
import com.zhou.peakflow.pojo.Address;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.pojo.OrderItem;
import com.zhou.peakflow.pojo.Product;
import com.zhou.peakflow.service.AddressService;
import com.zhou.peakflow.utils.OrderNoGenerator;
import com.zhou.peakflow.vo.SeckillResultVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.zhou.peakflow.constant.CacheExpireConstants.SECKILL_RESULT_MINUTES;
import static com.zhou.peakflow.constant.OrderConstants.ORDER_TYPE_SECKILL;
import static com.zhou.peakflow.constant.OrderConstants.SECKILL_ORDER_NO_PREFIX;
import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_RESULT;
import static com.zhou.peakflow.constant.RedisKeyConstant.SECKILL_STOCK;

/**
 * 秒杀订单事务内核：落库事务 + 流水线公共组件（超时消息、结果回写）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillOrderTxService {

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final AddressService addressService;
    private final OrderNoGenerator orderNoGenerator;
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String LOG_PREFIX = "【秒杀事务】";

    // 库存回补 Lua：仅当 Key 存在时 INCRBY
    private DefaultRedisScript<Long> stockRestoreScript;

    @PostConstruct
    public void initLua() {
        stockRestoreScript = new DefaultRedisScript<>();
        stockRestoreScript.setResultType(Long.class);
        stockRestoreScript.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/seckill_stock_restore.lua")));
    }

    // 落库事务：DB 权威校验 → 扣库存 → 订单 + 明细，任何一步失败整体回滚（由外层 Bean 代理调用，事务生效）
    @Transactional(rollbackFor = Exception.class)
    public Order createSeckillOrder(Long userId, Long productId, Long addressId) {
        // 1. DB 权威读：下单价格、活动时间、上架状态以数据库为准（缓存只做入口快速失败）
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (!SeckillFlag.YES.getCode().equals(product.getIsSeckill())) {
            throw new BusinessException("商品不存在或非秒杀商品");
        }
        if (!ProductShelfStatus.ON_SHELF.getCode().equals(product.getStatus())) {
            throw new BusinessException("商品已下架");
        }
        LocalDateTime now = LocalDateTime.now();
        if (product.getSeckillStartTime() == null || product.getSeckillEndTime() == null
                || product.getSeckillStartTime().isAfter(now)
                || product.getSeckillEndTime().isBefore(now)) {
            throw new BusinessException("秒杀时间不在有效期内");
        }

        // 2. 地址归属校验
        Address address = addressService.getById(addressId);
        if (address == null || !address.getUserId().equals(userId)) {
            throw new BusinessException("收货地址不合法");
        }

        // 3. 扣减秒杀库存（seckill_stock > 0 兜底防超卖）
        int rows = productMapper.decreaseSeckillStockDirect(productId);
        if (rows == 0) {
            throw new BusinessException("库存不足");
        }

        // 4. 落库订单 + 明细
        Order order = buildSeckillOrder(userId, product, address);
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 数据库级「一人一单」兜底。
            if (isSeckillSlotConflict(e)) {
                log.warn("{}【限购兜底】数据库唯一索引拦下重复抢购，userId={}, productId={}",
                        LOG_PREFIX, userId, productId);
                // 「该用户确实已占过名额」，从而不释放购买权门闩（详见 SeckillConsumer）
                throw new BusinessException(ResultCode.SECKILL_REPEAT, "您已参与过该秒杀活动");
            }
            // 其他唯一键冲突（如 uk_order_no 订单号重复）属系统问题，
            throw e;
        }
        orderItemMapper.insert(buildSeckillOrderItem(order, product));

        log.info("{}秒杀订单落库成功，orderId={}, orderNo={}, userId={}, productId={}",
                LOG_PREFIX, order.getId(), order.getOrderNo(), userId, productId);
        return order;
    }

    // 发送秒杀订单超时延迟消息（10 分钟档，比普通订单 30 分钟更短：秒杀库存稀缺占用成本高）
    public void sendSeckillTimeoutDelayMessage(Long orderId) {
        rocketMQTemplate.syncSend(
                OrderConstants.ORDER_TIMEOUT_TOPIC,
                MessageBuilder.withPayload(String.valueOf(orderId)).build(),
                rocketMQTemplate.getProducer().getSendMsgTimeout(),
                SeckillConstants.SECKILL_TIMEOUT_DELAY_LEVEL
        );
        log.info("{}秒杀订单超时延迟消息已发送，orderId={}, delayLevel={}",
                LOG_PREFIX, orderId, SeckillConstants.SECKILL_TIMEOUT_DELAY_LEVEL);
    }

    // 写入秒杀成功结果（供前端轮询）
    public void writeSuccessResult(String requestId, Long orderId, String orderNo) {
        SeckillResultVo vo = SeckillResultVo.success(orderId, orderNo);
        stringRedisTemplate.opsForValue().set(resultKey(requestId),
                JSON.toJSONString(vo), SECKILL_RESULT_MINUTES, TimeUnit.MINUTES);
        log.info("{}写入秒杀成功结果，requestId={}, orderId={}", LOG_PREFIX, requestId, orderId);
    }

    // 写入秒杀失败结果（供前端轮询）
    public void writeFailResult(String requestId, String reason) {
        SeckillResultVo vo = SeckillResultVo.fail(reason);
        stringRedisTemplate.opsForValue().set(resultKey(requestId),
                JSON.toJSONString(vo), SECKILL_RESULT_MINUTES, TimeUnit.MINUTES);
        log.info("{}写入秒杀失败结果，requestId={}, reason={}", LOG_PREFIX, requestId, reason);
    }

    // 回补 Redis 秒杀库存预扣（仅当 Key 存在时 INCRBY：Key 过期后凭空回补会制造幽灵库存）
    public void rollbackRedisStock(Long productId, Integer quantity) {
        stringRedisTemplate.execute(stockRestoreScript,
                Collections.singletonList(SECKILL_STOCK + ":" + productId),
                String.valueOf(quantity));
    }

    /** 秒杀限购唯一索引名：必须与 sql/seckill_limit_per_user_uk.sql 保持一致 */
    private static final String UK_SECKILL_USER_PRODUCT = "uk_seckill_user_product";

    /**
     * 判断唯一键冲突是否来自「秒杀限购」索引。
     *
     * {@code order} 表上有两个唯一键：{@code uk_order_no}（订单号）与
     * {@code uk_seckill_user_product}（一人一单），二者抛出的都是
     * {@code DuplicateKeyException}，只能靠错误消息里的索引名区分。
     *
     * 必须区分的原因：订单号重复是系统问题（生成器异常，应当重试/告警），
     * 重复抢购是业务终态（应当直接拒绝、不重试），两者处置完全相反。
     */
    private boolean isSeckillSlotConflict(DuplicateKeyException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(UK_SECKILL_USER_PRODUCT);
    }

    private String resultKey(String requestId) {
        return SECKILL_RESULT + ":" + requestId;
    }

    private Order buildSeckillOrder(Long userId, Product product, Address address) {
        if (product.getSeckillPrice() == null) {
            throw new BusinessException("秒杀商品价格配置异常");
        }
        Order order = new Order();
        order.setOrderNo(orderNoGenerator.generate(SECKILL_ORDER_NO_PREFIX));
        order.setUserId(userId);
        order.setAddressId(address.getId());

        // 收货人快照：与普通订单一样，下单那一刻把地址固化进订单。
        //   之前这里漏了，导致秒杀订单的收货信息全为空 —— 订单详情拼出来的地址是空的，
        //   后续发货也没有收货人可寄。秒杀只是"怎么抢"不同，"卖给谁"必须与普通订单同口径。
        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getReceiverPhone());
        order.setReceiverProvince(address.getProvince());
        order.setReceiverCity(address.getCity());
        order.setReceiverDistrict(address.getDistrict());
        order.setReceiverDetailAddress(address.getDetailAddress());

        order.setTotalAmount(product.getSeckillPrice());
        order.setPayAmount(product.getSeckillPrice());
        order.setStatus(OrderStatus.WAIT_PAY.getCode());
        order.setOrderType(ORDER_TYPE_SECKILL);
        order.setIsSeckill(SeckillFlag.YES.getCode());
        // 秒杀商品 ID：唯一索引 uk_seckill_user_product 的组成部分，一人一单的数据库级依据
        order.setSeckillProductId(product.getId());
        order.setVersion(0);
        return order;
    }

    private OrderItem buildSeckillOrderItem(Order order, Product product) {
        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(product.getId());
        item.setProductName(product.getName());
        item.setProductImage(product.getImage());
        item.setProductPrice(product.getSeckillPrice());
        item.setQuantity(SeckillConstants.SECKILL_ORDER_QUANTITY);
        item.setSubtotal(product.getSeckillPrice());
        return item;
    }
}
