package com.zhou.peakflow.guard;

import com.zhou.peakflow.config.RefundProperties;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.OrderItemMapper;
import com.zhou.peakflow.mapper.RefundRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 商品操作前置守卫：判断商品当前能不能删除 / 下架（下架不可逆，动手前必须先拦）。
 * 拦三类：在途订单、未完结的退款售后单、已完成但仍在售后保障期内的订单 —— 三者时间窗口不同、
 * 不能互相替代：已完成订单随时可申请售后，只有保障期这条拦得住。已取消/已关闭/已退款及过保订单
 * 不拦（order_item 存了名称/图片/成交价快照，撤下不影响历史追溯）。保障期口径必须与
 * RefundTxService#checkRefundable 严格对齐，一旦分叉就会出现「还能申请退款、但商品已经能删」的漏洞。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductOperateGuard {

    private final OrderItemMapper orderItemMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final RefundProperties refundProperties;

    private static final String LOG_PREFIX = "【商品占用校验】";

    /** window-days 配置缺失时的兜底值，与 RefundTxService 保持一致 */
    private static final int DEFAULT_WINDOW_DAYS = 15;

    /**
     * 校验商品能否执行删除 / 下架，不满足条件直接抛业务异常（action 仅用于拼接提示语）。
     * 校验与真正的下架/删除非原子，并发下可能漏掉一笔刚创建的订单；该结果与「先有订单、后下架」
     * 业务等价，故不加锁（要彻底消除需在下单链路对商品行加悲观锁）。
     */
    public void assertReleasable(Long productId, String action) {
        if (productId == null || productId <= 0) {
            throw new BusinessException("商品ID不合法");
        }

        // 1.  在途订单
        long inFlightCount = orderItemMapper.countInFlightOrdersByProductId(productId);
        if (inFlightCount > 0) {
            log.warn("{}存在在途订单，拒绝{}，productId={}, 在途订单数={}",
                    LOG_PREFIX, action, productId, inFlightCount);
            throw new BusinessException("该商品存在 " + inFlightCount
                    + " 笔未完成订单（待付款/已付款/已发货），暂不可" + action
                    + "，请等订单完结或退款完成后再操作");
        }

        // 2.  未完结的退款 / 售后单
        long openRefundCount = refundRecordMapper.countOpenRefundsByProductId(productId);
        if (openRefundCount > 0) {
            log.warn("{}存在未完结的退款/售后单，拒绝{}，productId={}, 未完结数={}",
                    LOG_PREFIX, action, productId, openRefundCount);
            throw new BusinessException("该商品存在 " + openRefundCount
                    + " 笔进行中的退款/售后单（待审核/退款中/待退货），暂不可" + action
                    + "，请先处理完售后流程");
        }

        // 3.  已完成但仍在售后保障期内
        int windowDays = resolveWindowDays();
        long inWindowCount = countOrdersInAfterSaleWindow(productId, windowDays);
        if (inWindowCount > 0) {
            log.warn("{}存在仍在售后保障期内的已完成订单，拒绝{}，productId={}, 笔数={}, 窗口={}天",
                    LOG_PREFIX, action, productId, inWindowCount, windowDays);
            throw new BusinessException("该商品有 " + inWindowCount
                    + " 笔订单仍在售后保障期内（确认收货后 " + windowDays + " 天内），暂不可" + action
                    + "，请等保障期结束或售后处理完毕后再操作");
        }

        log.info("{}校验通过，可{}，productId={}", LOG_PREFIX, action, productId);
    }

    /** 统计「已完成且仍在售后保障期内」的订单数 */
    private long countOrdersInAfterSaleWindow(Long productId, int windowDays) {
        // 窗口起点 = 当前时间 - 窗口天数。
        // 刻意用 JVM 时间而不是 SQL 的 NOW()：订单时间戳都是应用用 LocalDateTime.now() 写进去的，
        // 且退款申请链路也用 JVM 时间判断，两边必须同一个时钟。
        // 与 RefundTxService 的 `anchor.plusDays(days) >= now` 完全等价。
        LocalDateTime windowStart = LocalDateTime.now().minusDays(windowDays);
        return orderItemMapper.countInAfterSaleWindowOrdersByProductId(
                productId, OrderStatus.COMPLETED.getCode(), windowStart);
    }

    /** 售后窗口天数（配置缺失时回落默认值，避免因一份坏配置把校验整个关掉） */
    private int resolveWindowDays() {
        Integer days = refundProperties.getWindowDays();
        return days == null ? DEFAULT_WINDOW_DAYS : days;
    }
}


