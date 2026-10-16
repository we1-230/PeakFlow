package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.enums.PayStatus;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.pojo.PayRecord;
import com.zhou.peakflow.utils.PaySignUtil;
import com.zhou.peakflow.vo.PrepayVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付事务服务（内层）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayTxService {

    private final PayRecordMapper payRecordMapper;
    private final OrderMapper orderMapper;
    private final MockPayProperties mockPayProperties;

    private static final String LOG_PREFIX = "【支付事务】";

    // 事务内：校验订单 → 查/创建支付单 → 拼接收银台 URL
    @Transactional(rollbackFor = Exception.class)
    public PrepayVo doPrepay(Long userId, Long orderId) {
        log.info("{}发起支付，userId={}, orderId={}", LOG_PREFIX, userId, orderId);

        // 1. 校验订单存在且属于当前用户
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }

        // 2. 校验订单状态：只有「待付款」才能发起支付
        if (!OrderStatus.canPay(order.getStatus())) {
            OrderStatus current = OrderStatus.fromCode(order.getStatus());
            throw new BusinessException("订单当前状态不允许支付："
                    + (current == null ? "未知" : current.getDesc()));
        }

        // 3. 幂等：该订单若已存在"待支付"的支付单，直接复用
        PayRecord exist = payRecordMapper.selectWaitPayByOrderId(orderId);
        if (exist != null) {
            log.info("{}订单已存在待支付单，直接复用，outTradeNo={}",
                    LOG_PREFIX, exist.getOutTradeNo());
            return buildPrepayVo(exist);
        }

        // 4. 创建支付单
        PayRecord record = new PayRecord();
        record.setOutTradeNo(generateOutTradeNo(order));
        record.setOrderId(order.getId());
        record.setOrderNo(order.getOrderNo());
        record.setUserId(userId);
        record.setAmount(order.getPayAmount());
        record.setPayChannel(mockPayProperties.getChannel());
        record.setStatus(PayStatus.WAIT_PAY.getCode());
        record.setVersion(0);
        payRecordMapper.insert(record);

        log.info("{}支付单创建成功，outTradeNo={}, amount={}",
                LOG_PREFIX, record.getOutTradeNo(), record.getAmount());

        return buildPrepayVo(record);
    }

    // 生成商户订单号
    private String generateOutTradeNo(Order order) {
        return "PAY" + order.getOrderNo() + "-" + System.currentTimeMillis();
    }

    // 组装返回给前端的收银台信息（含商户签名）
    private PrepayVo buildPrepayVo(PayRecord record) {
        long timestamp = System.currentTimeMillis();

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

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}