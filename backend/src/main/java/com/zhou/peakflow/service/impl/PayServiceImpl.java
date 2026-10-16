package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.constant.OrderConstants;
import com.zhou.peakflow.enums.PayStatus;
import com.zhou.peakflow.dto.pay.PayNotifyDto;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.pojo.PayRecord;
import com.zhou.peakflow.service.OrderService;
import com.zhou.peakflow.service.PayService;
import com.zhou.peakflow.utils.PaySignUtil;
import com.zhou.peakflow.vo.PrepayVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 支付服务实现：预支付下单、回调验签入账（幂等 + 金额校验）、过期支付单兜底关闭
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayServiceImpl implements PayService {

    private final PayRecordMapper payRecordMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final MockPayProperties mockPayProperties;
    private final RedissonClient redissonClient;
    private final PayTxService payTxService;


    private static final String LOG_PREFIX = "【支付服务】";

    // 发起支付：校验订单 → 创建/复用支付单 → 拼接并签名收银台地址
    @Override
    public PrepayVo prepay(Long userId, Long orderId) {
        log.info("{}发起支付（外层），userId={}, orderId={}", LOG_PREFIX, userId, orderId);

        String lockKey = "lock:prepay:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean isLocked = false;
        try {
            isLocked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!isLocked) {
                throw new BusinessException("操作过于频繁，请稍后重试");
            }

            // 调事务方法（走代理，事务生效）
            return payTxService.doPrepay(userId, orderId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后重试");
        } finally {
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 处理第三方异步通知
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String handleNotify(PayNotifyDto dto, String rawBody) {
        log.info("{}收到支付回调，outTradeNo={}, tradeNo={}, amount={}",
                LOG_PREFIX, dto.getOutTradeNo(), dto.getTradeNo(), dto.getAmount());

        // 1. 验签
        if (!verifySign(dto)) {
            // 验签不过说明报文被篡改或来自伪造方，直接拒绝，绝不能入账
            log.error("{}支付回调验签失败，已拒绝，outTradeNo={}", LOG_PREFIX, dto.getOutTradeNo());
            return "failure";
        }

        // 2. 找到本地支付单
        PayRecord record = payRecordMapper.selectByOutTradeNo(dto.getOutTradeNo());
        if (record == null) {
            log.error("{}支付单不存在，拒绝入账，outTradeNo={}", LOG_PREFIX, dto.getOutTradeNo());
            return "failure";
        }

        // 3. 幂等第一道：已经支付成功过，直接应答成功
        if (PayStatus.SUCCESS.getCode().equals(record.getStatus())) {
            log.info("{}重复回调（已支付成功），直接返回 success，outTradeNo={}", LOG_PREFIX, dto.getOutTradeNo());
            return "success";
        }

        // 支付单已关闭（订单已取消/超时关单）：这笔钱不该收，拒绝
        if (!PayStatus.canPay(record.getStatus())) {
            log.error("{}支付单状态不允许入账，status={}, outTradeNo={}",
                    LOG_PREFIX, record.getStatus(), dto.getOutTradeNo());
            return "failure";
        }

        // 4. 金额校验（防"改价攻击"）
        // 不校验金额的话，攻击者可以发起 1 分钱的支付，却让系统把 9999 元的订单标记为已付款。
        if (dto.getAmount() == null || record.getAmount().compareTo(dto.getAmount()) != 0) {
            log.error("{}支付金额不一致，应付={}, 实收={}, outTradeNo={}",
                    LOG_PREFIX, record.getAmount(), dto.getAmount(), dto.getOutTradeNo());
            return "failure";
        }

        // 5. 入账：CAS 更新（幂等第二道，也是真正的并发闸门）
        int rows = payRecordMapper.markPaid(
                dto.getOutTradeNo(),
                dto.getTradeNo(),
                LocalDateTime.now(),
                truncate(rawBody, 1000));

        if (rows == 0) {
            // 重新查支付单，看当前状态是什么
            PayRecord latest = payRecordMapper.selectByOutTradeNo(dto.getOutTradeNo());

            if (PayStatus.SUCCESS.getCode().equals(latest.getStatus())) {
                // 真的并发，被抢先入账
                log.warn("并发回调，已被其他请求入账，outTradeNo={}", dto.getOutTradeNo());
                return "success";
            }

            // 支付单被关闭了，但用户真的付了钱
            // 这种情况要告警，人工介入
            log.error("支付单已关闭但收到成功回调，需人工介入！outTradeNo={}, status={}",
                    dto.getOutTradeNo(), latest.getStatus());

            // 返回 failure，让支付宝继续重推（此时需要人工处理）
            return "failure";
        }

        // 6. 改订单状态：待付款 → 已付款（乐观锁）
        orderService.markOrderPaid(record.getOrderId());

        log.info("{}支付回调处理成功，outTradeNo={}, orderId={}", LOG_PREFIX, dto.getOutTradeNo(), record.getOrderId());
        return "success";
    }


    // 查询支付结果
    @Override
    public PayRecord query(Long userId, String outTradeNo) {
        PayRecord record = payRecordMapper.selectByOutTradeNo(outTradeNo);
        if (record == null) {
            throw new BusinessException(ResultCode.PAY_RECORD_NOT_FOUND);
        }
        // 防越权：只能查自己的支付单
        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }
        return record;
    }

    @Override
    public void closeByOrderId(Long orderId) {
        int rows = payRecordMapper.closeByOrderId(orderId);
        if (rows > 0) {
            log.info("{}支付单已关闭，orderId={}", LOG_PREFIX, orderId);
        }
    }

    // ---- 兜底扫描（定时任务调用） ----

    /**
     * 兜底关闭已过有效期的待支付单。
     */
    @Override
    public int sweepExpiredPayRecords(int limit) {
        LocalDateTime deadline = LocalDateTime.now()
                .minusMinutes(OrderConstants.PAY_RECORD_VALID_MINUTES);

        // 1.  数据不一致：支付单还是"待支付"，订单却已经收款（1/2/3）或已退款（6）。
        //    正常流程不可能出现 —— 订单转已付款的唯一入口是支付回调，回调里会先把支付单置为成功。
        //    出现即说明状态没同步，绝不能自动关单，只能告警人工核对。
        long anomalyCount = payRecordMapper.countPaidOrderWithWaitPayRecord(deadline);
        if (anomalyCount > 0) {
            log.error("{}【数据不一致】有 {} 笔支付单仍为待支付、但订单已进入收款之后的状态，"
                            + "需人工核对！此处刻意不做自动关闭（条件：pay_record.status=0 且 order.status IN (1,2,3,6)）",
                    LOG_PREFIX, anomalyCount);
        }

        // 2.  可以安全关闭的：订单处于「待付款(0)」—— 关掉只是解除它对订单关单的占用；
        List<Long> payIds = payRecordMapper.selectClosableExpiredPayIds(deadline, limit);
        if (CollectionUtils.isEmpty(payIds)) {
            return 0;
        }

        int closed = 0;
        for (Long payId : payIds) {
            try {
                if (payRecordMapper.closeExpiredById(payId) > 0) {
                    closed++;
                }
            } catch (Exception e) {
                // 单笔失败不影响其余，下一轮扫描会再试
                log.error("{}关闭过期支付单失败，payId={}", LOG_PREFIX, payId, e);
            }
        }

        log.info("{}过期支付单扫描完成，候选={}，已关闭={}，数据不一致={}",
                LOG_PREFIX, payIds.size(), closed, anomalyCount);
        return closed;
    }

    // 生成商户订单号
    private String generateOutTradeNo(Order order) {
        return "PAY" + order.getOrderNo() + "-" + System.currentTimeMillis();
    }

    // 组装返回给前端的收银台信息
    private PrepayVo buildPrepayVo(PayRecord record) {
        long timestamp = System.currentTimeMillis();

        // 参与签名的参数（用 LinkedHashMap 只是为了便于阅读，签名内部会自行按 key 排序）
        Map<String, Object> signParams = new LinkedHashMap<>();
        signParams.put("out_trade_no", record.getOutTradeNo());
        signParams.put("amount", record.getAmount().toPlainString());
        signParams.put("timestamp", timestamp);
        String sign = PaySignUtil.sign(signParams, mockPayProperties.getSecretKey());

        String cashierUrl = mockPayProperties.getCashierUrl()
                + "?out_trade_no=" + urlEncode(record.getOutTradeNo())
                + "&amount=" + urlEncode(record.getAmount().toPlainString())
                + "&timestamp=" + timestamp
                + "&sign=" + urlEncode(sign);

        return PrepayVo.builder()
                .outTradeNo(record.getOutTradeNo())
                .orderNo(record.getOrderNo())
                .amount(record.getAmount())
                .payChannel(record.getPayChannel())
                .cashierUrl(cashierUrl)
                .build();
    }

    // 校验回调签名
    private boolean verifySign(PayNotifyDto dto) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("out_trade_no", dto.getOutTradeNo());
        params.put("trade_no", dto.getTradeNo());
        params.put("amount", dto.getAmount() == null ? null : dto.getAmount().toPlainString());
        params.put("status", dto.getStatus());
        params.put("timestamp", dto.getTimestamp());
        return PaySignUtil.verify(params, mockPayProperties.getSecretKey(), dto.getSign());
    }

    /** URL 编码（参数值里可能含 + / = 等特殊字符，不编码会导致收银台取参错误） */
    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 截断字符串，避免回调原文超长导致入库失败 */
    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
