package com.zhou.peakflow.task;

import com.zhou.peakflow.constant.OrderConstants;
import com.zhou.peakflow.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款单兜底扫描任务：把卡在中间态的退款单收敛掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundSweepTask {

    private final RefundService refundService;

    private static final String LOG_PREFIX = "【退款兜底扫描】";

    /** 收敛间隔：10 分钟（重试下限默认 30 分钟，扫勤一点不影响频次控制） */
    private static final long REFUND_SWEEP_INTERVAL_MS = 10 * 60_000L;

    /** 启动后首次执行的延迟：排在订单扫描之后，避免启动期多个扫描一起抢资源 */
    private static final long INITIAL_DELAY_MS = 90_000L;

    /**
     * 兜底收敛卡在中间态的退款单。
     *
     * 用 {@code fixedDelay} 而非 {@code fixedRate}：单轮变慢不会把延迟累积到下一轮。
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = REFUND_SWEEP_INTERVAL_MS)
    public void sweepTimeoutRefunds() {
        try {
            refundService.sweepTimeoutRefunds(OrderConstants.SWEEP_BATCH_SIZE);
        } catch (Exception e) {
            // 兜住异常：单轮失败不应影响后续调度
            log.error("{}退款中间态收敛扫描异常", LOG_PREFIX, e);
        }
    }
}
