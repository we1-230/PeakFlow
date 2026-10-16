package com.zhou.peakflow.task;

import com.zhou.peakflow.constant.OrderConstants;
import com.zhou.peakflow.service.OrderService;
import com.zhou.peakflow.service.PayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单兜底扫描任务（超时关单 + 自动确认收货）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSweepTask {

    private final OrderService orderService;
    private final PayService payService;

    private static final String LOG_PREFIX = "【订单兜底扫描】";

    /** 超时关单扫描间隔：1 分钟（等的人可能正在付款，扫得勤一点） */
    private static final long TIMEOUT_SWEEP_INTERVAL_MS = 60_000L;

    /** 自动确认收货扫描间隔：30 分钟（业务以"天"为粒度，无需扫太勤） */
    private static final long AUTO_CONFIRM_SWEEP_INTERVAL_MS = 30 * 60_000L;

    /** 过期支付单扫描间隔：10 分钟（支付单有效期以分钟计，扫勤一点让宽限期尽快释放） */
    private static final long PAY_RECORD_SWEEP_INTERVAL_MS = 10 * 60_000L;

    /** 启动后首次执行的延迟：留出建连与预热时间，避免与启动期抢资源 */
    private static final long INITIAL_DELAY_MS = 60_000L;

    /**
     * 兜底关闭已超时未支付的订单。
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = TIMEOUT_SWEEP_INTERVAL_MS)
    public void sweepTimeoutPendingOrders() {
        try {
            orderService.sweepTimeoutPendingOrders(OrderConstants.SWEEP_BATCH_SIZE);
        } catch (Exception e) {
            // 兜住异常：单轮失败不应影响后续调度
            log.error("{}超时关单扫描异常", LOG_PREFIX, e);
        }
    }

    /** 兜底把发货超过 {@code AUTO_CONFIRM_RECEIPT_DAYS} 天仍未确认收货的订单自动置为已完成 */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = AUTO_CONFIRM_SWEEP_INTERVAL_MS)
    public void sweepAutoConfirmReceipt() {
        try {
            orderService.sweepAutoConfirmReceipt(OrderConstants.SWEEP_BATCH_SIZE);
        } catch (Exception e) {
            log.error("{}自动确认收货扫描异常", LOG_PREFIX, e);
        }
    }

    /**
     * 兜底关闭已过有效期的待支付单。
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = PAY_RECORD_SWEEP_INTERVAL_MS)
    public void sweepExpiredPayRecords() {
        try {
            payService.sweepExpiredPayRecords(OrderConstants.SWEEP_BATCH_SIZE);
        } catch (Exception e) {
            log.error("{}过期支付单扫描异常", LOG_PREFIX, e);
        }
    }
}
