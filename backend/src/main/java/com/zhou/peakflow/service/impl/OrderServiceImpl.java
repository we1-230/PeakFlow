package com.zhou.peakflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhou.peakflow.config.OrderRiskProperties;
import com.zhou.peakflow.constant.OrderConstants;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.enums.ProductShelfStatus;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.enums.RefundType;
import com.zhou.peakflow.enums.SeckillFlag;
import com.zhou.peakflow.dto.order.CreateOrderDto;
import com.zhou.peakflow.dto.order.OrderQueryDto;
import com.zhou.peakflow.dto.order.UpdateOrderDto;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.*;
import com.zhou.peakflow.pojo.*;
import com.zhou.peakflow.service.*;
import com.zhou.peakflow.utils.OrderNoGenerator;
import com.zhou.peakflow.vo.OrderVo;
import com.zhou.peakflow.vo.PageVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.zhou.peakflow.constant.CacheExpireConstants.*;
import static com.zhou.peakflow.constant.RedisKeyConstant.*;

/**
 * 订单服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductService productService;
    private final ProductMapper productMapper;
    private final AddressService addressService;
    private final RocketMQTemplate rocketMQTemplate;
    private final OrderNoGenerator orderNoGenerator;
    private final StringRedisTemplate stringRedisTemplate;
    private final OrderOperationLogMapper orderOperationLogMapper;
    /** 支付流水 Mapper：订单取消/超时关单时要同步关闭关联的支付单 */
    private final PayRecordMapper payRecordMapper;
    // 退款单 Mapper
    private final RefundRecordMapper refundRecordMapper;

    /** 下单风控参数（待付款订单配额、惯犯惩罚等），见 application.yml 的 order-risk 段 */
    private final OrderRiskProperties orderRiskProperties;

    /** 秒杀服务：订单取消/超时关单/退款时回补 Redis 秒杀库存预扣 */
    private final SeckillOrderService seckillOrderService;

    private static final String LOG_PREFIX = "【订单服务】";

    // ---- 创建订单 ----

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(Long userId, CreateOrderDto createOrderDto) {
        log.info("{}创建订单，userId={}, addressId={}",
                LOG_PREFIX, userId, createOrderDto.getAddressId());

        // ---- 全部"拒绝性校验"排在第一个写操作之前 ----

        // 1.  参数校验
        validateOrderItems(createOrderDto.getItems());
        Address address = validateAddress(userId, createOrderDto.getAddressId());

        // 2.  幂等：防"同一个请求"重复提交（Redis 不可用时降级放行）
        String submitLockKey = tryAcquireSubmitLock(userId, createOrderDto.getItems());

        boolean orderCreated = false;
        try {
            // 3.  风控配额：单用户"待付款订单数"上限 —— 防恶意下单占库存
            checkUserUnpaidQuota(userId);

            // 4.  扣库存（第一个写操作）
            List<OrderItem> orderItems = new ArrayList<>();
            BigDecimal totalAmount = processProducts(createOrderDto.getItems(), orderItems);

            // 5.  落库
            Order order = buildAndSaveOrder(userId, createOrderDto, totalAmount, orderItems, address);
            orderCreated = true;

            // 6.  超时关单的延迟消息（发送失败不影响下单，只告警人工兜底关单）
            try {
                sendOrderTimeoutDelayMessage(order.getId());
            } catch (Exception e) {
                log.error("{}发送订单超时消息失败，该订单无法自动关单，需人工关注，orderId={}",
                        LOG_PREFIX, order.getId(), e);
            }

            log.info("{}订单创建成功，orderId={}, orderNo={}",
                    LOG_PREFIX, order.getId(), order.getOrderNo());
            return order;

        } catch (RuntimeException e) {
            // 下单失败要释放防重 key，否则用户会被"锁"满 10 秒才能重试
            // （setIfAbsent 是在下单成功之前就占位的，不主动释放就是"一次失败换十秒禁言"）
            if (!orderCreated) {
                releaseSubmitLock(submitLockKey);
            }
            throw e;
        }
    }

    private void validateOrderItems(List<CreateOrderDto.OrderItemDto> items) {
        if (CollectionUtils.isEmpty(items)) {
            throw new BusinessException("订单商品不能为空");
        }
        if (items.size() > OrderConstants.MAX_ORDER_ITEMS) {
            throw new BusinessException("单笔订单最多支持" + OrderConstants.MAX_ORDER_ITEMS + "种商品");
        }
        for (CreateOrderDto.OrderItemDto item : items) {
            if (item.getProductId() == null || item.getProductId() <= 0) {
                throw new BusinessException("商品 ID 不合法");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BusinessException("商品数量必须大于 0");
            }
            if (item.getQuantity() > OrderConstants.MAX_PRODUCT_QUANTITY) {
                throw new BusinessException("单个商品数量不能超过" + OrderConstants.MAX_PRODUCT_QUANTITY);
            }
        }
        long distinct = items.stream()
                .map(CreateOrderDto.OrderItemDto::getProductId)
                .distinct()
                .count();
        if (distinct != items.size()) {
            throw new BusinessException("订单中存在重复商品");
        }
    }

    private Address validateAddress(Long userId, Long addressId) {
        if (addressId == null) {
            throw new BusinessException("收货地址不能为空");
        }
        Address address = addressService.getById(addressId);
        if (address == null || !address.getUserId().equals(userId)) {
            throw new BusinessException("地址不存在或不属于当前用户");
        }
        return address;
    }

    private BigDecimal processProducts(List<CreateOrderDto.OrderItemDto> items,
                                       List<OrderItem> orderItems) {
        List<Long> productIds = items.stream()
                .map(CreateOrderDto.OrderItemDto::getProductId)
                .collect(Collectors.toList());

        List<Product> products = productService.listByIds(productIds);
        if (products.size() != productIds.size()) {
            throw new BusinessException("部分商品不存在");
        }

        Map<Long, Integer> quantityMap = items.stream()
                .collect(Collectors.toMap(CreateOrderDto.OrderItemDto::getProductId,
                        CreateOrderDto.OrderItemDto::getQuantity));

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Product product : products) {
            Integer quantity = quantityMap.get(product.getId());

            validateProductForOrder(product, quantity);

            boolean success = productService.deductStockWithOptimisticLock(
                    product.getId(), quantity, product.getVersion());
            if (!success) {
                log.warn("{}扣减库存失败，productId={}, quantity={}, version={}",
                        LOG_PREFIX, product.getId(), quantity, product.getVersion());
                throw new BusinessException("商品" + product.getName() + "创建失败请重试");
            }

            log.info("{}扣减库存成功，productId={}, quantity={}",
                    LOG_PREFIX, product.getId(), quantity);

            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(quantity));
            totalAmount = totalAmount.add(subtotal);
            orderItems.add(buildOrderItem(product, quantity, subtotal));
        }
        return totalAmount;
    }

    private void validateProductForOrder(Product product, Integer quantity) {
        if (SeckillFlag.YES.getCode().equals(product.getIsSeckill())) {
            throw new BusinessException("秒杀商品请前往秒杀页面购买：" + product.getName());
        }
        if (quantity > OrderConstants.MAX_PRODUCT_QUANTITY) {
            throw new BusinessException("商品" + product.getName()
                    + "单次购买不能超过" + OrderConstants.MAX_PRODUCT_QUANTITY + "件");
        }
        if (product.getStock() == null || product.getStock() < quantity) {
            throw new BusinessException("商品" + product.getName() + "库存不足");
        }
        if (ProductShelfStatus.OFF_SHELF.getCode().equals(product.getStatus())) {
            throw new BusinessException("商品" + product.getName() + "已下架");
        }
    }

    private OrderItem buildOrderItem(Product product, Integer quantity, BigDecimal subtotal) {
        OrderItem orderItem = new OrderItem();
        orderItem.setProductId(product.getId());
        orderItem.setProductName(product.getName());
        orderItem.setProductImage(product.getImage());
        orderItem.setProductPrice(product.getPrice());
        orderItem.setQuantity(quantity);
        orderItem.setSubtotal(subtotal);
        return orderItem;
    }

    // 幂等：尝试获取"提交锁"，返回本次使用的 Redis key（供失败时释放）
    private String tryAcquireSubmitLock(Long userId, List<CreateOrderDto.OrderItemDto> items) {
        try {
            String key = generateOrderSubmitKey(userId, items);
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", ORDER_SUBMIT_SECONDS, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(acquired)) {
                throw new BusinessException("订单重复提交，请勿重复点击");
            }
            return key;
        } catch (BusinessException e) {
            // "重复提交"是业务判定，要原样抛出去，不能被下面的兜底 catch 吞掉
            throw e;
        } catch (Exception e) {
            log.warn("{}防重提交Redis检查失败，降级放行", LOG_PREFIX, e);
            return null;
        }
    }

    /** 释放提交锁；下单失败时调用，让用户可以立刻重试而不是干等 10 秒 */
    private void releaseSubmitLock(String submitLockKey) {
        if (submitLockKey == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(submitLockKey);
        } catch (Exception e) {
            // 释放失败也无所谓：key 会在 10 秒后自然过期，只是用户要多等一会儿
            log.warn("{}释放防重提交key失败（将等待自然过期），key={}", LOG_PREFIX, submitLockKey, e);
        }
    }

    // 风控配额：限制单用户"同时持有的待付款订单数"
    private void checkUserUnpaidQuota(Long userId) {
        // 1.  惯犯识别：近 N 天内"下单后从未支付、被超时关单"的次数（实时推导，不落库计数）
        int windowDays = orderRiskProperties.resolveAbandonWindowDays();
        int abandonCount = orderMapper.countRecentTimeoutClosedByUserId(userId, windowDays);

        // 2.  有效配额 = 基础配额 - 惯犯惩罚（触底后不再降）
        int effectiveQuota = orderRiskProperties.resolveEffectiveQuota(abandonCount);

        // 3.  当前实际持有的待付款订单数
        int unpaidCount = orderMapper.countUnpaidByUserId(userId);

        if (unpaidCount >= effectiveQuota) {
            log.warn("{}【下单风控】待付款订单数超限，userId={}, 当前={}, 有效配额={}, 基础配额={}, 近{}天超时未支付={}次",
                    LOG_PREFIX, userId, unpaidCount, effectiveQuota,
                    orderRiskProperties.getMaxUnpaidOrders(), windowDays, abandonCount);
            throw new BusinessException(ResultCode.ORDER_UNPAID_QUOTA_EXCEEDED,
                    "您当前有 " + unpaidCount + " 笔待支付订单（上限 " + effectiveQuota
                            + " 笔），请先完成支付或取消其中一笔后再下单");
        }
    }

    /** 生成"提交锁"的 Redis key（对外保留 public，便于测试与排查） */
    public String generateOrderSubmitKey(Long userId, List<CreateOrderDto.OrderItemDto> items) {
        // key 的内容必须覆盖"用户提交了什么"：商品 + 数量。
        // 只放商品 ID 不放数量的话，用户先提交 [A×2] 失败、改成 [A×3] 重试会被误判成重复提交。
        String content = items.stream()
                .map(item -> item.getProductId() + "x" + item.getQuantity())
                .sorted()
                .collect(Collectors.joining(","));

        String contentHash = DigestUtils.md5DigestAsHex(
                content.getBytes(StandardCharsets.UTF_8));

        return ORDER_SUBMIT + ":" + userId + ":" + contentHash;
    }

    private Order buildAndSaveOrder(Long userId, CreateOrderDto dto,
                                    BigDecimal totalAmount, List<OrderItem> orderItems,
                                    Address address) {
        String orderNo = orderNoGenerator.generate(OrderConstants.ORDER_NO_PREFIX);
        if (address == null) {
            throw new BusinessException("地址不存在");
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setAddressId(dto.getAddressId());

        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getReceiverPhone());
        order.setReceiverProvince(address.getProvince());
        order.setReceiverCity(address.getCity());
        order.setReceiverDistrict(address.getDistrict());
        order.setReceiverDetailAddress(address.getDetailAddress());

        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);
        order.setStatus(OrderStatus.WAIT_PAY.getCode());
        order.setOrderType(OrderConstants.ORDER_TYPE_NORMAL);
        order.setIsSeckill(SeckillFlag.NO.getCode());
        order.setVersion(0);
        order.setRemark(dto.getRemark());

        this.save(order);

        for (OrderItem item : orderItems) {
            item.setOrderId(order.getId());
        }
        orderItemMapper.batchInsert(orderItems);

        return order;
    }

    @Override
    public void sendOrderTimeoutDelayMessage(Long orderId) {
        String messageBody = String.valueOf(orderId);
        rocketMQTemplate.syncSend(
                OrderConstants.ORDER_TIMEOUT_TOPIC,
                MessageBuilder.withPayload(messageBody).build(),
                rocketMQTemplate.getProducer().getSendMsgTimeout(),
                OrderConstants.ORDER_TIMEOUT_DELAY_LEVEL
        );
        log.info("{}订单超时延迟消息已发送，orderId={}, delayLevel={}",
                LOG_PREFIX, orderId, OrderConstants.ORDER_TIMEOUT_DELAY_LEVEL);
    }

    // ---- 超时取消 ----

    /**
     * 超时关闭订单。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean timeoutCancelOrder(Long orderId) {
        log.info("{}超时关闭订单，orderId={}", LOG_PREFIX, orderId);

        Order order = this.getById(orderId);
        if (order == null) {
            log.warn("{}订单不存在，跳过关闭：orderId={}", LOG_PREFIX, orderId);
            return true;
        }
        if (order.getStatus() != OrderStatus.WAIT_PAY.getCode()) {
            log.warn("{}订单状态不允许超时关闭，orderId={}, status={}",
                    LOG_PREFIX, orderId, order.getStatus());
            return true;
        }

        // 支付单仍在有效期内 → 用户可能正在收银台付款，本次放弃关单，交给兜底扫描稍后再关
        //   "最多宽限一个支付单有效期"，用尽后无条件关单。
        PayRecord waitPay = payRecordMapper.selectWaitPayByOrderIdForUpdate(orderId);
        if (waitPay != null && !isPayRecordExpired(waitPay) && !isGraceDeadlineReached(order)) {
            log.warn("{}存在未过期的支付单，本次不关单，orderId={}, outTradeNo={}, 支付单创建时间={}",
                    LOG_PREFIX, orderId, waitPay.getOutTradeNo(), waitPay.getCreateTime());
            return false;
        }

        // 改成 CLOSED，不是 CANCELLED
        int rows = orderMapper.updateOrderStatus(
                orderId,
                OrderStatus.WAIT_PAY.getCode(),
                OrderStatus.CLOSED.getCode(),
                order.getVersion());
        if (rows <= 0) {
            log.warn("{}超时关闭订单失败（状态已变更），orderId={}", LOG_PREFIX, orderId);
            return true;
        }

        log.info("{}超时关闭订单成功，orderId={}", LOG_PREFIX, orderId);

        rollbackStock(order);
        // 订单已关闭，必须同步关掉它上面"待支付"的支付单，否则旧收银台链接还能继续付款
        closePayRecord(orderId);
        return true;
    }

    /**
     * 支付单是否已过有效期。
     */
    private boolean isPayRecordExpired(PayRecord pay) {
        LocalDateTime createTime = pay.getCreateTime();
        if (createTime == null) {
            log.warn("{}支付单缺少创建时间，按已过期处理，outTradeNo={}",
                    LOG_PREFIX, pay.getOutTradeNo());
            return true;
        }
        return createTime.plusMinutes(OrderConstants.PAY_RECORD_VALID_MINUTES)
                .isBefore(LocalDateTime.now());
    }

    /**
     * 关单宽限期是否已经用尽（硬上限）。
     */
    private boolean isGraceDeadlineReached(Order order) {
        LocalDateTime createTime = order.getCreateTime();
        if (createTime == null) {
            log.warn("{}订单缺少创建时间，按已超宽限上限处理，orderId={}", LOG_PREFIX, order.getId());
            return true;
        }
        return createTime
                .plusMinutes(OrderConstants.ORDER_TIMEOUT_MINUTES)
                .plusMinutes(OrderConstants.PAY_RECORD_VALID_MINUTES)
                .isBefore(LocalDateTime.now());
    }

    // ---- 兜底扫描（定时任务调用） ----

    /**
     * 兜底关闭已到关单时刻的待付款订单。
     */
    @Override
    public int sweepTimeoutPendingOrders(int limit) {
        LocalDateTime deadline = LocalDateTime.now()
                .minusMinutes(OrderConstants.ORDER_TIMEOUT_MINUTES);
        List<Long> orderIds = orderMapper.selectTimeoutPendingOrderIds(deadline, limit);
        if (CollectionUtils.isEmpty(orderIds)) {
            return 0;
        }

        int done = 0;
        int deferred = 0;
        for (Long orderId : orderIds) {
            try {
                if (timeoutCancelOrder(orderId)) {
                    done++;
                } else {
                    // 支付单还在有效期内，下一轮扫描再关
                    deferred++;
                }
            } catch (Exception e) {
                // 单笔失败不影响其余订单，下一轮扫描会再试
                log.error("{}兜底关单失败，orderId={}", LOG_PREFIX, orderId, e);
            }
        }

        log.info("{}兜底关单扫描完成，候选={}，已终结={}，因支付单有效期推迟={}",
                LOG_PREFIX, orderIds.size(), done, deferred);
        return done;
    }

    /**
     * 兜底把已发货且超过 {@code AUTO_CONFIRM_RECEIPT_DAYS} 天未确认收货的订单自动置为已完成。
     */
    @Override
    public int sweepAutoConfirmReceipt(int limit) {
        LocalDateTime deadline = LocalDateTime.now()
                .minusDays(OrderConstants.AUTO_CONFIRM_RECEIPT_DAYS);
        List<Long> orderIds = orderMapper.selectAutoConfirmOrderIds(deadline, limit);
        if (CollectionUtils.isEmpty(orderIds)) {
            return 0;
        }

        int success = 0;
        for (Long orderId : orderIds) {
            try {
                if (autoConfirmReceipt(orderId)) {
                    success++;
                }
            } catch (Exception e) {
                log.error("{}自动确认收货失败，orderId={}", LOG_PREFIX, orderId, e);
            }
        }

        log.info("{}自动确认收货扫描完成，候选={}，成功={}", LOG_PREFIX, orderIds.size(), success);
        return success;
    }

    /**
     * 自动确认收货（系统发起，与用户手动确认的区别只在于"谁触发"）。
     *
     * @return 是否真的完成了确认收货
     */
    private boolean autoConfirmReceipt(Long orderId) {
        Order order = this.getById(orderId);
        if (order == null) {
            log.warn("{}自动确认收货：订单不存在，orderId={}", LOG_PREFIX, orderId);
            return false;
        }
        if (order.getStatus() != OrderStatus.SHIPPED.getCode()) {
            log.warn("{}自动确认收货：订单状态不允许，orderId={}, status={}",
                    LOG_PREFIX, orderId, order.getStatus());
            return false;
        }

        int rows = orderMapper.completeReceiptIfNoRefund(orderId, order.getVersion());
        if (rows == 0) {
            // 存在进行中的退款单，或状态/版本已被并发修改 → 本轮不处理，下一次扫描再试
            log.warn("{}自动确认收货未生效（存在进行中退款或状态已变更），orderId={}",
                    LOG_PREFIX, orderId);
            return false;
        }

        log.info("{}订单已自动确认收货，orderId={}, orderNo={}",
                LOG_PREFIX, orderId, order.getOrderNo());
        return true;
    }

    // 把订单标记为「已付款」
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markOrderPaid(Long orderId) {
        log.info("{}【支付入账】收到支付回调，准备把订单置为已付款，orderId={}", LOG_PREFIX, orderId);

        Order order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        // 幂等：已经是已付款，说明这笔回调已经处理过，直接返回
        if (OrderStatus.PAID.getCode().equals(order.getStatus())) {
            log.info("{}【支付入账】订单已是已付款状态，幂等跳过，orderId={}", LOG_PREFIX, orderId);
            return;
        }

        // 状态守卫：只有"待付款"的订单能入账。已取消/已关闭的订单不能因为一笔迟到的回调就变成已付款。
        if (!OrderStatus.canPay(order.getStatus())) {
            OrderStatus current = OrderStatus.fromCode(order.getStatus());
            throw new BusinessException("订单状态不允许支付入账，当前状态："
                    + (current == null ? "未知" : current.getDesc()));
        }

        // 乐观锁更新：WHERE status=0 AND version=?
        int rows = orderMapper.paySuccess(orderId, order.getVersion());
        if (rows == 0) {
            log.warn("{}【支付入账】乐观锁冲突，订单状态已被并发修改，orderId={}", LOG_PREFIX, orderId);
            throw new BusinessException("订单状态已变更，请稍后重试");
        }

        log.info("{}【支付入账】订单支付入账成功，orderId={}", LOG_PREFIX, orderId);
    }

    // ---- 取消订单 ----

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long userId, Long orderId) {
        log.info("{}取消订单，userId={}, orderId={}", LOG_PREFIX, userId, orderId);

        Order order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }

        if (!OrderStatus.canCancel(order.getStatus())) {
            throw new BusinessException("订单状态不允许取消，当前状态：" +
                    OrderStatus.fromCode(order.getStatus()).getDesc());
        }

        int updateResult = orderMapper.cancelOrder(orderId, order.getVersion());
        if (updateResult == 0) {
            log.warn("{}取消订单乐观锁冲突或状态已变更，orderId={}, version={}",
                    LOG_PREFIX, orderId, order.getVersion());
            throw new BusinessException("取消失败，订单状态已变更，请刷新后重试");
        }

        rollbackStock(order);
        // 订单已取消，同步关闭待支付单（防止"订单已取消却还能付款"造成资损）
        closePayRecord(orderId);

        log.info("{}取消订单成功，orderId={}", LOG_PREFIX, orderId);
    }

    // ---- 退款入账（由退款回调驱动） ----

    // 把订单置为「已退款」，并按货物去向决定是否回补库存
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markOrderRefunded(Long orderId, BigDecimal refundAmount, Integer refundType) {
        log.info("{}【退款入账】收到退款成功回调，准备把订单置为已退款，orderId={}, amount={}, refundType={}",
                LOG_PREFIX, orderId, refundAmount, refundType);

        Order order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        // 幂等：已经是已退款，说明这笔回调已经处理过，直接返回
        if (OrderStatus.REFUNDED.getCode().equals(order.getStatus())) {
            log.info("{}【退款入账】订单已是已退款状态，幂等跳过，orderId={}", LOG_PREFIX, orderId);
            return;
        }

        // 状态守卫：只有"已付款 / 已发货 / 已完成"的订单能退款入账
        if (!OrderStatus.canRefund(order.getStatus())) {
            OrderStatus current = OrderStatus.fromCode(order.getStatus());
            throw new BusinessException("订单状态不允许退款入账，当前状态："
                    + (current == null ? "未知" : current.getDesc()));
        }

        // 退款金额以退款单为准（服务端算出），此处仅做兜底，绝不相信客户端传参
        BigDecimal finalAmount = refundAmount != null
                ? refundAmount
                : (order.getPayAmount() != null ? order.getPayAmount() : order.getTotalAmount());

        // 乐观锁更新：WHERE status IN (1,2,3) AND version = ?
        int updateResult = orderMapper.refundOrder(orderId, order.getVersion(), finalAmount);
        if (updateResult == 0) {
            log.warn("{}【退款入账】乐观锁冲突，订单状态已被并发修改，orderId={}", LOG_PREFIX, orderId);
            throw new BusinessException("退款入账失败，订单状态已变更，请稍后重试");
        }

        // 库存回补策略：库存属于"实物资产"，它的增减应该由"货在哪"决定，
        if (RefundType.isReturnAndRefund(refundType)) {
            log.info("{}【退款入账】退货退款的库存在确认收到退货时已回补，此处跳过，orderId={}",
                    LOG_PREFIX, orderId);
        } else if (OrderStatus.PAID.getCode().equals(order.getStatus())) {
            rollbackStock(order);
        } else {
            // 兜底分支：只会命中"本次改造之前落库的历史退款单"
            log.warn("{}【库存待人工核实】该退款单为「仅退款」但订单已出库，货物去向不明，"
                            + "未自动回补库存，请运营核实实物后手工调整，orderId={}, status={}",
                    LOG_PREFIX, orderId, order.getStatus());
        }

        log.info("{}【退款入账】订单已置为已退款，orderId={}, refundAmount={}",
                LOG_PREFIX, orderId, finalAmount);
    }

    // 退货入库：商家确认收到退货后回补库存
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreStockForReturn(Long orderId) {
        Order order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        rollbackStock(order);
        log.info("{}退货入库完成，库存已回补，orderId={}", LOG_PREFIX, orderId);
    }

    // ---- 确认收货 ----

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceipt(Long userId, Long orderId) {
        Order order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }
        if (order.getStatus() != OrderStatus.SHIPPED.getCode()) {
            throw new BusinessException("只有已发货的订单才能确认收货");
        }

        // 条件更新：状态 + 版本 + 无进行中退款，三合一原子
        int updateResult = orderMapper.completeReceiptIfNoRefund(orderId, order.getVersion());
        if (updateResult == 0) {
            // rows == 0 有两种可能：存在进行中的退款单，或状态 / version 被并发改过。
            // 回查一次退款单，才能给出「先处理完退款」还是「刷新后重试」这类准确提示
            RefundRecord processing = refundRecordMapper.selectOpenByOrderId(orderId);
            if (processing != null) {
                throw new BusinessException("该订单存在进行中的退款单，请先处理完退款");
            }
            throw new BusinessException("确认收货失败，订单状态已变更");
        }
    }

    // ---- 关闭关联支付单 ----

    // 关闭该订单下「待支付」的支付单
    private void closePayRecord(Long orderId) {
        try {
            int rows = payRecordMapper.closeByOrderId(orderId);
            if (rows > 0) {
                log.info("{}已同步关闭关联支付单，orderId={}", LOG_PREFIX, orderId);
            }
        } catch (Exception e) {
            log.error("{}关闭关联支付单失败，需人工核对，orderId={}", LOG_PREFIX, orderId, e);
        }
    }

    // ---- 回滚库存 ----

    private void rollbackStock(Order order) {
        List<OrderItem> items = orderItemMapper.selectByOrderId(order.getId());
        if (CollectionUtils.isEmpty(items)) {
            return;
        }

        if (SeckillFlag.YES.getCode().equals(order.getIsSeckill())) {
            for (OrderItem item : items) {
                int rows = productMapper.rollbackSeckillStockByQuantity(
                        item.getProductId(), item.getQuantity());
                if (rows == 0) {
                    log.error("{}回滚秒杀库存失败，orderId={}, productId={}, quantity={}",
                            LOG_PREFIX, order.getId(), item.getProductId(), item.getQuantity());
                }
                // Redis 预扣库存同步回补（仅当 Key 存在），否则 Redis 与 DB 库存漂移、可抢名额越用越少
                seckillOrderService.rollbackRedisStock(item.getProductId(), item.getQuantity());
            }
        } else {
            for (OrderItem item : items) {
                int rows = productMapper.rollbackStock(item.getProductId(), item.getQuantity());
                if (rows == 0) {
                    log.error("{}回滚普通库存失败，orderId={}, productId={}, quantity={}",
                            LOG_PREFIX, order.getId(), item.getProductId(), item.getQuantity());
                }
            }
        }
        log.info("{}回滚库存成功，orderId={}", LOG_PREFIX, order.getId());
    }

    // ---- 订单详情（纯查库） ----

    @Override
    public OrderVo getOrderDetail(Long userId, Long orderId) {
        log.info("{}获取订单详情，userId={}, orderId={}", LOG_PREFIX, userId, orderId);

        Order order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权查看此订单");
        }

        OrderVo orderVo = buildOrderVo(order);
        if (orderVo.getStatus() == OrderStatus.WAIT_PAY.getCode()) {
            orderVo.setTimeoutRemaining(calculateTimeoutRemaining(orderVo.getCreateTime()));
        }
        return orderVo;
    }

    // ---- 用户订单列表 ----

    @Override
    public PageVo<OrderVo> getUserOrders(Long userId, OrderQueryDto queryDto) {
        log.info("{}获取用户订单列表，userId={}, pageNum={}, pageSize={}",
                LOG_PREFIX, userId, queryDto.getPageNum(), queryDto.getPageSize());

        Page<Order> page = new Page<>(queryDto.getPageNum(), queryDto.getPageSize());
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime);

        if (queryDto.getStatus() != null) {
            wrapper.eq(Order::getStatus, queryDto.getStatus());
        }
        if (StringUtils.hasText(queryDto.getOrderNo())) {
            wrapper.eq(Order::getOrderNo, queryDto.getOrderNo());
        }

        Page<Order> orderPage = this.page(page, wrapper);
        List<Order> orders = orderPage.getRecords();

        if (CollectionUtils.isEmpty(orders)) {
            return new PageVo<>(new ArrayList<>(), 0, 0,
                    queryDto.getPageNum(), queryDto.getPageSize());
        }

        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        List<OrderItem> allItems = orderItemMapper.selectByOrderIds(orderIds);
        Map<Long, List<OrderItem>> itemsMap = allItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        // 一次 IN 查询把整页订单「进行中的退款单」捞回来，避免逐单查询造成 N+1
        Map<Long, RefundRecord> refundMap = loadLatestRefunds(orderIds);

        List<OrderVo> orderVos = orders.stream()
                .map(order -> {
                    OrderVo vo = convertToOrderVo(order, itemsMap.get(order.getId()),
                            refundMap.get(order.getId()));
                    if (order.getStatus() == OrderStatus.WAIT_PAY.getCode()) {
                        vo.setTimeoutRemaining(calculateTimeoutRemaining(order.getCreateTime()));
                    }
                    return vo;
                })
                .collect(Collectors.toList());

        return new PageVo<>(orderVos, orderPage.getTotal(), orderPage.getPages(),
                queryDto.getPageNum(), queryDto.getPageSize());
    }

    // ---- 管理员功能 ----

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adminUpdateOrder(UpdateOrderDto updateOrderDto,
                                 Long operatorId, String operatorName) {
        log.info("{}管理员更新订单，orderId={}, operatorId={}, operatorName={}",
                LOG_PREFIX, updateOrderDto.getId(), operatorId, operatorName);

        // 1. 查订单（同时记录旧物流信息）
        Order order = this.getById(updateOrderDto.getId());
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        // 记录旧物流信息（用于物流更新的旧值条件）
        String oldTrackingNo = order.getTrackingNo();
        String oldLogisticsCompany = order.getLogisticsCompany();
        String oldRemark = order.getRemark();

        Integer oldStatus = order.getStatus();
        Integer newStatus = updateOrderDto.getStatus();

        // 2. 状态流转（状态机 + 乐观锁 + 退款单校验，条件更新一条搞定）
        if (newStatus != null && !newStatus.equals(oldStatus)) {
            OrderStatus targetStatus = OrderStatus.fromCode(newStatus);
            if (targetStatus == null) {
                throw new BusinessException("订单状态不合法");
            }

            // 管理员只能 PAID -> SHIPPED
            if (!(oldStatus == OrderStatus.PAID.getCode()
                    && newStatus == OrderStatus.SHIPPED.getCode())) {
                throw new BusinessException("管理员只能将已付款订单发货，当前状态："
                        + OrderStatus.fromCode(oldStatus).getDesc());
            }

            // 条件更新：状态 + version + 无进行中退款，三合一原子
            int rows = orderMapper.shipOrderIfNoRefund(
                    order.getId(), oldStatus, newStatus, order.getVersion());

            if (rows == 0) {
                // 更新失败，查原因给出准确提示
                RefundRecord processing = refundRecordMapper.selectOpenByOrderId(order.getId());
                if (processing != null) {
                    RefundStatus status = RefundStatus.fromCode(processing.getStatus());
                    String desc = status == null ? "未知" : status.getDesc();
                    log.warn("{}发货失败，订单存在进行中的退款单，orderId={}, refundNo={}, refundStatus={}",
                            LOG_PREFIX, order.getId(), processing.getRefundNo(), desc);
                    throw new BusinessException(ResultCode.ORDER_STATUS_ERROR,
                            "该订单存在进行中的退款单（" + desc + "），请先处理完退款后再发货");
                }

                log.warn("{}管理员更新订单状态失败，orderId={}，状态或版本已变更",
                        LOG_PREFIX, order.getId());
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }

            order.setStatus(newStatus);
            order.setShippingTime(LocalDateTime.now());
            order.setVersion(order.getVersion() + 1);

            log.info("{}管理员发货成功，orderId={}，操作人={}，{} -> {}",
                    LOG_PREFIX, order.getId(), operatorName, oldStatus, newStatus);

            // 写操作日志
            saveOrderOperationLog(order, operatorId, operatorName,
                    "发货", oldStatus, newStatus, updateOrderDto.getTrackingNo());
        }

        // 3. 更新物流信息（带旧值条件，防并发覆盖）
        boolean hasTrackingNo = StringUtils.hasText(updateOrderDto.getTrackingNo());
        boolean hasLogisticsCompany = StringUtils.hasText(updateOrderDto.getLogisticsCompany());
        boolean hasRemark = StringUtils.hasText(updateOrderDto.getRemark());

        if (hasTrackingNo || hasLogisticsCompany || hasRemark) {
            int rows = orderMapper.updateLogisticsInfoWithOldValue(
                    order.getId(),
                    hasTrackingNo ? updateOrderDto.getTrackingNo() : null,
                    hasTrackingNo ? oldTrackingNo : null,
                    hasLogisticsCompany ? updateOrderDto.getLogisticsCompany() : null,
                    hasLogisticsCompany ? oldLogisticsCompany : null,
                    hasRemark ? updateOrderDto.getRemark() : null,
                    hasRemark ? oldRemark : null);

            if (rows == 0) {
                log.warn("{}物流信息更新失败（旧值已变更），orderId={}",
                        LOG_PREFIX, order.getId());
                throw new BusinessException("物流信息已被其他管理员修改，请刷新后重试");
            }

            log.info("{}物流信息更新成功，orderId={}, trackingNo={}, logisticsCompany={}",
                    LOG_PREFIX, order.getId(),
                    updateOrderDto.getTrackingNo(), updateOrderDto.getLogisticsCompany());
        }

        log.info("{}管理员更新订单成功，orderId={}", LOG_PREFIX, updateOrderDto.getId());
    }
    @Override
    public PageVo<OrderVo> adminListOrders(OrderQueryDto queryDto) {
        log.info("{}管理员查询订单列表，query={}", LOG_PREFIX, queryDto);

        // 1. 参数校验与默认值
        if (queryDto.getPageNum() == null || queryDto.getPageNum() < 1) {
            queryDto.setPageNum(1);
        }
        if (queryDto.getPageSize() == null || queryDto.getPageSize() < 1) {
            queryDto.setPageSize(10);
        }
        if (queryDto.getPageSize() > 100) {
            queryDto.setPageSize(100);
        }

        Page<Order> page = new Page<>(queryDto.getPageNum(), queryDto.getPageSize());
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();

        // 2. 动态拼接查询条件
        // 订单号（精确）
        if (StringUtils.hasText(queryDto.getOrderNo())) {
            wrapper.eq(Order::getOrderNo, queryDto.getOrderNo());
        }

        // 用户ID（精确）
        if (queryDto.getUserId() != null) {
            wrapper.eq(Order::getUserId, queryDto.getUserId());
        }

        // 订单状态
        if (queryDto.getStatus() != null) {
            wrapper.eq(Order::getStatus, queryDto.getStatus());
        }

        // 订单类型
        if (queryDto.getOrderType() != null) {
            wrapper.eq(Order::getOrderType, queryDto.getOrderType());
        }

        // 是否秒杀
        if (queryDto.getIsSeckill() != null) {
            wrapper.eq(Order::getIsSeckill, queryDto.getIsSeckill());
        }

        if (queryDto.getCreateTimeStart() != null) {
            wrapper.ge(Order::getCreateTime, queryDto.getCreateTimeStart());
        }
        if (queryDto.getCreateTimeEnd() != null) {
            wrapper.le(Order::getCreateTime, queryDto.getCreateTimeEnd());
        }

        // 金额范围
        if (queryDto.getAmountStart() != null) {
            wrapper.ge(Order::getPayAmount, queryDto.getAmountStart());
        }
        if (queryDto.getAmountEnd() != null) {
            wrapper.le(Order::getPayAmount, queryDto.getAmountEnd());
        }

        // 3. 默认按创建时间倒序
        wrapper.orderByDesc(Order::getCreateTime);

        // 4. 分页查询
        Page<Order> orderPage = this.page(page, wrapper);
        List<Order> orders = orderPage.getRecords();

        if (CollectionUtils.isEmpty(orders)) {
            return new PageVo<>(new ArrayList<>(), 0, 0,
                    queryDto.getPageNum(), queryDto.getPageSize());
        }

        // 5. 批量查订单项
        List<Long> orderIds = orders.stream()
                .map(Order::getId)
                .collect(Collectors.toList());
        List<OrderItem> allItems = orderItemMapper.selectByOrderIds(orderIds);
        Map<Long, List<OrderItem>> itemsMap = allItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        // 管理端列表同样要看到退款进度（审核工作台一眼就能认出哪些单在退款）
        Map<Long, RefundRecord> refundMap = loadLatestRefunds(orderIds);

        // 6. 组装 VO
        List<OrderVo> orderVos = orders.stream()
                .map(order -> convertToOrderVo(order, itemsMap.get(order.getId()),
                        refundMap.get(order.getId())))
                .collect(Collectors.toList());

        return new PageVo<>(orderVos, orderPage.getTotal(), orderPage.getPages(),
                queryDto.getPageNum(), queryDto.getPageSize());
    }

    // ---- 私有辅助方法 ----

    // 写订单操作日志
    private void saveOrderOperationLog(Order order, Long operatorId, String operatorName,
                                       String operation, Integer oldStatus,
                                       Integer newStatus, String remark) {
        try {
            OrderOperationLog log1 = new OrderOperationLog();
            log1.setOrderId(order.getId());
            log1.setOrderNo(order.getOrderNo());
            log1.setOperatorId(operatorId);
            log1.setOperatorName(operatorName);
            log1.setOperation(operation);
            log1.setOldStatus(oldStatus);
            log1.setNewStatus(newStatus);
            log1.setRemark(remark);
            log1.setCreateTime(LocalDateTime.now());

            orderOperationLogMapper.insert(log1);

            log.info("{}订单操作日志已记录，orderId={}, operation={}, operator={}",
                    LOG_PREFIX, order.getId(), operation, operatorName);
        } catch (Exception e) {
            // 日志写入失败不影响主流程
            log.error("{}订单操作日志写入失败，orderId={}, operation={}",
                    LOG_PREFIX, order.getId(), operation, e);
        }
    }


    public OrderVo buildOrderVo(Order order) {
        List<OrderItem> items = orderItemMapper.selectByOrderId(order.getId());
        // 详情是单条场景，直接查一次即可（取最近一条退款单，含终态）
        RefundRecord refund = refundRecordMapper.selectLatestByOrderId(order.getId());
        return convertToOrderVo(order, items, refund);
    }

    // 批量装载订单「最近一条」退款单，返回 {@code orderId → 退款单} 的映射（无则不在 Map 中）
    private Map<Long, RefundRecord> loadLatestRefunds(List<Long> orderIds) {
        if (CollectionUtils.isEmpty(orderIds)) {
            return Collections.emptyMap();
        }
        List<RefundRecord> refunds = refundRecordMapper.selectLatestByOrderIds(orderIds);
        if (CollectionUtils.isEmpty(refunds)) {
            return Collections.emptyMap();
        }
        // 已按 id DESC 排序，merge 保留先遇到的那条 = 每个订单的最新一条
        return refunds.stream().collect(Collectors.toMap(
                RefundRecord::getOrderId, r -> r, (a, b) -> a));
    }

    // 转换为订单VO
    private OrderVo convertToOrderVo(Order order, List<OrderItem> items, RefundRecord refund) {
        OrderVo.OrderVoBuilder builder = OrderVo.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .addressId(order.getAddressId())
                .totalAmount(order.getTotalAmount())
                .payAmount(order.getPayAmount())
                .status(order.getStatus())
                .statusDesc(OrderStatus.fromCode(order.getStatus()).getDesc())
                .orderType(order.getOrderType())
                .payTime(order.getPayTime())
                .shippingTime(order.getShippingTime())
                .completeTime(order.getCompleteTime())
                .cancelTime(order.getCancelTime())
                .createTime(order.getCreateTime())
                .trackingNo(order.getTrackingNo())
                .logisticsCompany(order.getLogisticsCompany())
                .remark(order.getRemark())
                .address(OrderVo.AddressVo.builder()
                        .receiverName(order.getReceiverName())
                        .receiverPhone(order.getReceiverPhone())
                        .fullAddress(order.getReceiverProvince()
                                + order.getReceiverCity()
                                + order.getReceiverDistrict()
                                + order.getReceiverDetailAddress())
                        .build());

        // 带上退款信息：让用户在订单列表/详情就能看到退款进展，
        // 不必等订单状态变成"已退款"才知道后台发生了什么
        if (refund != null) {
            RefundStatus refundStatus = RefundStatus.fromCode(refund.getStatus());
            builder.refundId(refund.getId())
                    .refundNo(refund.getRefundNo())
                    .refundStatus(refund.getStatus())
                    .refundStatusDesc(refundStatus == null ? "未知" : refundStatus.getDesc())
                    // 未完结 = 还占着这张订单（不能发货、不能再申请退款）
                    .refundProcessing(RefundStatus.isOpen(refund.getStatus()));
        }

        if (!CollectionUtils.isEmpty(items)) {
            List<OrderVo.OrderItemVo> itemVos = items.stream()
                    .map(item -> OrderVo.OrderItemVo.builder()
                            .productId(item.getProductId())
                            .productName(item.getProductName())
                            .productImage(item.getProductImage())
                            .productPrice(item.getProductPrice())
                            .quantity(item.getQuantity())
                            .subtotal(item.getSubtotal())
                            .build())
                    .collect(Collectors.toList());
            builder.items(itemVos);
        }

        return builder.build();
    }

    private Long calculateTimeoutRemaining(LocalDateTime createTime) {
        LocalDateTime expireTime = createTime.plus(
                OrderConstants.ORDER_TIMEOUT_MINUTES, ChronoUnit.MINUTES);
        long remaining = LocalDateTime.now().until(expireTime, ChronoUnit.SECONDS);
        return Math.max(0, remaining);
    }
}