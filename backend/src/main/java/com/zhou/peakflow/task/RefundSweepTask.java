package com.zhou.peakflow.task;

import com.zhou.peakflow.constant.OrderConstants;
import com.zhou.peakflow.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款单兜底扫描任务：把卡在中间态的退款单收敛掉。
 *
 * 多实例下每个实例都会各自调度一次，统一经 TaskLock 串成单节点执行。
 */
@Component
@RequiredArgsConstructor
public class RefundSweepTask {

    private final RefundService refundService;
    private final TaskLock taskLock;

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
        taskLock.runExclusively("lock:task:refund-sweep:timeout", "退款兜底扫描-中间态收敛",
                () -> refundService.sweepTimeoutRefunds(OrderConstants.SWEEP_BATCH_SIZE));
    }
}
