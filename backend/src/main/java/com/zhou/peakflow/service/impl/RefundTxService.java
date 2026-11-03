package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.config.RefundProperties;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.enums.RefundType;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.mapper.RefundRecordMapper;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.pojo.PayRecord;
import com.zhou.peakflow.pojo.RefundRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款事务服务（内层）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundTxService {

    private final RefundRecordMapper refundRecordMapper;
    private final PayRecordMapper payRecordMapper;
    private final OrderMapper orderMapper;
    private final RefundProperties refundProperties;
    private final MockPayProperties mockPayProperties;

    private static final String LOG_PREFIX = "【退款事务】";

    /** 自动审核时写入的"审核人"名称，便于在列表里一眼区分人工与系统 */
    private static final String SYSTEM_AUDITOR = "系统自动审核";

    @Transactional(rollbackFor = Exception.class)
    public RefundRecord doApply(Long userId, Long orderId, String reason,
                                String returnLogisticsCompany, String returnTrackingNo) {
        log.info("{}受理退款申请，userId={}, orderId={}", LOG_PREFIX, userId, orderId);

        // 1. 查订单（用于拿 orderNo、version、金额依据）
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }

        // 2. 友好校验：订单状态是否可退（含已完成订单的窗口期规则）
        checkRefundable(order);

        // 3. 判定退款类型 —— 服务端按订单状态推导，客户端无权指定。
        Integer refundType = RefundType.resolveByOrderStatus(order.getStatus());
        String returnCompany = null;
        String returnNo = null;

        if (RefundType.isReturnAndRefund(refundType)) {
            // 售后必须提供退货物流：商家需要知道"退回来的货现在在哪"，
            returnCompany = trimToNull(returnLogisticsCompany);
            returnNo = trimToNull(returnTrackingNo);
            if (returnCompany == null || returnNo == null) {
                log.warn("{}售后申请缺少退货物流信息，orderId={}, company=[{}], trackingNo=[{}]",
                        LOG_PREFIX, orderId, returnCompany, returnNo);
                throw new BusinessException(ResultCode.REFUND_RETURN_INFO_MISSING);
            }
            // 长度兜底（与列宽 varchar(64) 对齐，避免严格模式下插入失败）
            returnCompany = truncate(returnCompany, 64);
            returnNo = truncate(returnNo, 64);
        }

        // 4. 防重第一层：该订单是否已有「未完结」的退款单，先给用户明确提示。
        //    注意口径：未完结 = 待审核(0) / 退款中(1) / 退款失败(3)。

        RefundRecord open = refundRecordMapper.selectOpenByOrderId(orderId);
        if (open != null) {
            RefundStatus openStatus = RefundStatus.fromCode(open.getStatus());
            log.warn("{}订单已有未完结的退款单，拒绝重复申请，orderId={}, refundNo={}, status={}",
                    LOG_PREFIX, orderId, open.getRefundNo(), open.getStatus());
            if (RefundStatus.FAILED.getCode().equals(open.getStatus())) {
                throw new BusinessException(ResultCode.REFUND_REPEAT,
                        "该订单有一笔退款处理失败（退款单号 " + open.getRefundNo()
                                + "），请等待管理员重试或联系客服");
            }
            throw new BusinessException(ResultCode.REFUND_REPEAT,
                    "该订单已有一笔退款正在处理（"
                            + (openStatus == null ? "未知" : openStatus.getDesc()) + "），请勿重复申请");
        }

        // 5. 必须有支付成功的流水：退款金额一律以它为准，绝不由客户端传参决定
        PayRecord payRecord = payRecordMapper.selectPaidByOrderId(orderId);
        if (payRecord == null) {
            throw new BusinessException(ResultCode.REFUND_PAY_NOT_FOUND);
        }
        BigDecimal refundAmount = payRecord.getAmount();
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ResultCode.REFUND_AMOUNT_ERROR);
        }

        // 6. 原子落库（防重第二、三层）：把「订单确实可退」的条件写进 INSERT ... SELECT 的 WHERE，
        //    让校验与插入在同一条 SQL 里完成，消除"先查后插"之间的竞态窗口；
        //    第三层由唯一索引 uk_open_order 兜底 —— 并发插入只有一个能成功，另一个走 DuplicateKeyException
        String refundNo = generateRefundNo(order);
        Integer status = autoApprove(order, refundAmount)
                ? RefundStatus.REFUNDING.getCode()
                : RefundStatus.APPLYING.getCode();

        int rows;
        try {
            rows = refundRecordMapper.insertRefundIfOrderRefundable(
                    refundNo,
                    orderId,
                    userId,
                    payRecord.getOutTradeNo(),
                    refundAmount,
                    mockPayProperties.getChannel(),
                    normalizeReason(reason),
                    status,
                    order.getVersion(),
                    refundType,
                    returnCompany,
                    returnNo);
        } catch (DuplicateKeyException e) {
            // 被唯一索引 uk_open_order 拦下：并发下另一个请求已经插入了未完结退款单
            log.warn("{}并发申请退款被唯一索引拦下，orderId={}, refundNo={}", LOG_PREFIX, orderId, refundNo);
            throw new BusinessException(ResultCode.REFUND_REPEAT, "该订单已有一笔退款正在处理，请勿重复申请");
        }

        if (rows == 0) {
            // 订单状态或 version 在查询之后被人改掉了（比如被自动确认收货变成已完成）
            log.warn("{}条件插入退款单失败，订单状态已变更，orderId={}", LOG_PREFIX, orderId);
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR, "订单状态已变更，请刷新后重试");
        }

        // 7. 查回刚插入的退款单返回
        RefundRecord record = refundRecordMapper.selectByRefundNo(refundNo);
        log.info("{}退款单已生成，refundNo={}, orderId={}, amount={}, refundType={}, status={}",
                LOG_PREFIX, refundNo, orderId, refundAmount, refundType, status);
        return record;
    }

    // 事务内：审核退款单
    @Transactional(rollbackFor = Exception.class)
    public RefundRecord doAudit(Long operatorId, String operatorName, Long refundId,
                                boolean approved, String remark) {
        RefundRecord record = refundRecordMapper.selectById(refundId);
        if (record == null) {
            throw new BusinessException(ResultCode.REFUND_NOT_FOUND);
        }

        // 状态守卫：只有「待审核」能被审核（CAS 之外再判一次，是为了给出清晰的错误提示）
        if (!RefundStatus.canAudit(record.getStatus())) {
            RefundStatus current = RefundStatus.fromCode(record.getStatus());
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR,
                    "该退款单当前状态为「" + (current == null ? "未知" : current.getDesc()) + "」，不可审核");
        }

        String auditRemark = StringUtils.hasText(remark)
                ? remark.trim()
                : (approved ? "审核通过" : "审核驳回");

        if (approved) {
            // 目标状态按类型决定：货没出库 → 直接退款中；货已出库 → 先等用户寄回
            Integer targetStatus = RefundType.isReturnAndRefund(record.getRefundType())
                    ? RefundStatus.WAIT_RETURN.getCode()
                    : RefundStatus.REFUNDING.getCode();

            int rows = refundRecordMapper.approve(refundId, operatorId, operatorName,
                    auditRemark, targetStatus);
            if (rows == 0) {
                // 并发下已被其他管理员审核，或用户抢先撤销
                log.warn("{}审核通过失败（状态已变更），refundId={}", LOG_PREFIX, refundId);
                throw new BusinessException(ResultCode.REFUND_STATUS_ERROR, "该退款单已被处理，请刷新后重试");
            }
            // 把最新状态回填给外层，用于判断是否需要发起第三方退款
            record.setStatus(targetStatus);
            record.setAuditorId(operatorId);
            record.setAuditorName(operatorName);
            record.setAuditRemark(auditRemark);
            record.setAuditTime(LocalDateTime.now());

            if (RefundStatus.REFUNDING.getCode().equals(targetStatus)) {
                log.info("{}审核通过（仅退款），准备发起第三方退款，refundNo={}, auditor={}",
                        LOG_PREFIX, record.getRefundNo(), operatorName);
            } else {
                log.info("{}审核通过（退货退款），进入待用户退货，等待商家签收后才放款，"
                                + "refundNo={}, auditor={}, 退货物流={}/{}",
                        LOG_PREFIX, record.getRefundNo(), operatorName,
                        record.getReturnLogisticsCompany(), record.getReturnTrackingNo());
            }
            return record;
        }

        int rows = refundRecordMapper.reject(refundId, operatorId, operatorName, auditRemark);
        if (rows == 0) {
            log.warn("{}审核驳回失败（状态已变更），refundId={}", LOG_PREFIX, refundId);
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR, "该退款单已被处理，请刷新后重试");
        }
        log.info("{}审核驳回，refundNo={}, auditor={}, remark={}",
                LOG_PREFIX, record.getRefundNo(), operatorName, auditRemark);
        return null;
    }

    // 事务内：用户撤销申请
    @Transactional(rollbackFor = Exception.class)
    public void doCancel(Long userId, Long refundId) {
        RefundRecord record = refundRecordMapper.selectById(refundId);
        if (record == null) {
            throw new BusinessException(ResultCode.REFUND_NOT_FOUND);
        }
        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.REFUND_NO_PERMISSION);
        }

        if (!RefundStatus.canCancel(record.getStatus())) {
            RefundStatus current = RefundStatus.fromCode(record.getStatus());
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR,
                    "当前状态为「" + (current == null ? "未知" : current.getDesc())
                            + "」，已进入退款流程，无法撤销，请联系客服");
        }

        int rows = refundRecordMapper.cancel(refundId, "用户主动撤销申请");
        if (rows == 0) {
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR, "撤销失败，退款单已被处理");
        }
        log.info("{}用户撤销退款申请成功，refundNo={}", LOG_PREFIX, record.getRefundNo());
    }

    // 事务内：商家签收退货，把单据从「待用户退货」推进到「退款中」
    @Transactional(rollbackFor = Exception.class)
    public RefundRecord doConfirmReturnReceived(Long operatorId, String operatorName, Long refundId) {
        RefundRecord record = refundRecordMapper.selectById(refundId);
        if (record == null) {
            throw new BusinessException(ResultCode.REFUND_NOT_FOUND);
        }

        // 类型守卫：只有「退货退款」才存在"收到退货"这回事
        if (!RefundType.isReturnAndRefund(record.getRefundType())) {
            throw new BusinessException(ResultCode.REFUND_TYPE_NOT_MATCH,
                    "该退款单为「仅退款」，不存在退货，无需确认收到退货");
        }

        // 幂等闸门（放在状态守卫之前）：已经签收过就直接返回 null，不抛异常、也不重复回补库存。
        if (record.getReturnReceivedTime() != null) {
            log.info("{}退货已签收，幂等跳过，refundNo={}, receivedTime={}",
                    LOG_PREFIX, record.getRefundNo(), record.getReturnReceivedTime());
            return null;
        }

        // 状态守卫：只有「待用户退货」才能签收。
        // 这同时挡住了"还没审核就直接签收"和"审核被驳回后还想签收"。
        if (!RefundStatus.canConfirmReturn(record.getStatus())) {
            RefundStatus current = RefundStatus.fromCode(record.getStatus());
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR,
                    "确认收货失败：退款单当前状态为「" + (current == null ? "未知" : current.getDesc())
                            + "」，只有「待用户退货」的售后退款单可以确认收到退货");
        }

        int rows = refundRecordMapper.confirmReturnReceived(refundId);
        if (rows == 0) {
            // 命中 0 行 = 状态已被并发改掉（比如另一位管理员抢先签收）
            RefundRecord latest = refundRecordMapper.selectById(refundId);
            RefundStatus current = latest == null ? null : RefundStatus.fromCode(latest.getStatus());
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR,
                    "确认收货失败：退款单状态已变更为「" + (current == null ? "未知" : current.getDesc())
                            + "」，请刷新后重试");
        }

        record.setStatus(RefundStatus.REFUNDING.getCode());
        record.setReturnReceivedTime(LocalDateTime.now());
        log.info("{}已签收退货并推进到退款中，refundNo={}, orderId={}, operator={}",
                LOG_PREFIX, record.getRefundNo(), record.getOrderId(), operatorName);
        return record;
    }

    @Transactional(rollbackFor = Exception.class)
    public RefundRecord doRetry(Long operatorId, String operatorName, Long refundId) {
        RefundRecord record = refundRecordMapper.selectById(refundId);
        if (record == null) {
            throw new BusinessException(ResultCode.REFUND_NOT_FOUND);
        }
        if (!RefundStatus.canRetry(record.getStatus())) {
            RefundStatus current = RefundStatus.fromCode(record.getStatus());
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR,
                    "只有「退款失败」或「退款中」的退款单可以重试，当前状态为「"
                            + (current == null ? "未知" : current.getDesc()) + "」");
        }

        // 「退款中」是有条件重试：必须真的"卡住"了才允许。
        // 否则管理员手一抖，会把一笔刚发起 3 秒、三方正在处理的退款又发一遍。
        if (RefundStatus.REFUNDING.getCode().equals(record.getStatus())) {
            assertRefundingStuck(record);
        }

        int rows = refundRecordMapper.markRefunding(refundId);
        if (rows == 0) {
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR, "重试失败，退款单已被其他人处理");
        }
        record.setStatus(RefundStatus.REFUNDING.getCode());
        log.info("{}管理员重新发起退款，refundNo={}, operator={}",
                LOG_PREFIX, record.getRefundNo(), operatorName);
        return record;
    }

    /**
     * 校验一笔「退款中」的退款单确实已经卡住（允许重试）。
     * 锚点用 update_time 而非额外加一个重试计数字段：进入「退款中」后没人推动它就停在那一刻，
     * 每次重试（markRefunding）又会刷新它，于是一个字段同时承担「卡住计时」与「重试限频」两个职责——
     * 每重试一次就要再等 N 分钟。时间缺失（脏数据）时不阻拦，交给 sweepTimeoutRefunds 告警兜底；
     * 此处与「退款判不准就拒绝」方向相反是刻意的：重试幂等且沿用同一退款单号，放宽不会造成资损。
     */
    private void assertRefundingStuck(RefundRecord record) {
        int delayMinutes = refundProperties.resolveRetryDelayMinutes();
        LocalDateTime stuckSince = record.getUpdateTime() != null
                ? record.getUpdateTime()
                : record.getApplyTime();
        if (stuckSince == null) {
            log.warn("{}退款单缺少时间锚点，跳过重试间隔校验，refundNo={}",
                    LOG_PREFIX, record.getRefundNo());
            return;
        }
        if (stuckSince.plusMinutes(delayMinutes).isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR,
                    "该退款单发起不足 " + delayMinutes + " 分钟，可能仍在三方处理中，请稍后再重试");
        }
    }

    // 事务内：作废一张退款失败的退款单
    @Transactional(rollbackFor = Exception.class)
    public void doClose(Long operatorId, String operatorName, Long refundId, String remark) {
        RefundRecord record = refundRecordMapper.selectById(refundId);
        if (record == null) {
            throw new BusinessException(ResultCode.REFUND_NOT_FOUND);
        }
        if (!RefundStatus.canClose(record.getStatus())) {
            RefundStatus current = RefundStatus.fromCode(record.getStatus());
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR,
                    "只有「退款失败」的退款单可以作废，当前状态为「"
                            + (current == null ? "未知" : current.getDesc()) + "」");
        }

        String closeRemark = StringUtils.hasText(remark)
                ? remark.trim()
                : "管理员作废该笔退款（渠道侧无法退回）";

        int rows = refundRecordMapper.closeFailed(refundId, operatorId, operatorName, closeRemark);
        if (rows == 0) {
            throw new BusinessException(ResultCode.REFUND_STATUS_ERROR, "作废失败，退款单已被其他人处理");
        }
        log.info("{}已作废退款单，refundNo={}, operator={}, remark={}",
                LOG_PREFIX, record.getRefundNo(), operatorName, closeRemark);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRequestFailed(String refundNo, String failReason) {
        int rows = refundRecordMapper.markRefundFailed(refundNo, truncate(failReason, 255), null);
        if (rows > 0) {
            log.warn("{}退款请求失败已落库，refundNo={}, reason={}", LOG_PREFIX, refundNo, failReason);
        }
    }

    // 订单是否处于可退款状态
    private void checkRefundable(Order order) {
        Integer status = order.getStatus();

        if (!OrderStatus.canRefund(status)) {
            OrderStatus current = OrderStatus.fromCode(status);
            throw new BusinessException(ResultCode.REFUND_NOT_ALLOWED,
                    "当前订单状态为「" + (current == null ? "未知" : current.getDesc()) + "」，不支持退款");
        }

        // 已完成订单受退款窗口期限制
        if (OrderStatus.COMPLETED.getCode().equals(status)) {
            LocalDateTime anchor = order.getCompleteTime() != null
                    ? order.getCompleteTime()
                    : order.getCreateTime();

            // 两个时间都取不到说明数据异常。这里绝不能"直接跳过校验"——
            if (anchor == null) {
                log.error("{}已完成订单缺少时间锚点（completeTime 与 createTime 均为空），"
                        + "无法判定退款窗口期，按超期处理，orderId={}", LOG_PREFIX, order.getId());
                throw new BusinessException(ResultCode.REFUND_WINDOW_EXPIRED,
                        "订单时间信息异常，暂时无法受理退款，请联系客服");
            }

            long days = refundProperties.getWindowDays() == null ? 15 : refundProperties.getWindowDays();
            if (anchor.plusDays(days).isBefore(LocalDateTime.now())) {
                log.warn("{}订单已超出退款窗口期，orderId={}, anchor={}, windowDays={}",
                        LOG_PREFIX, order.getId(), anchor, days);
                throw new BusinessException(ResultCode.REFUND_WINDOW_EXPIRED,
                        "已超过" + days + "天可退款期限，如有疑问请联系客服");
            }
        }
    }

    /** 是否命中小额自动审核 */
    private boolean autoApprove(Order order, BigDecimal refundAmount) {
        if (!refundProperties.autoAuditEnabled()) return false;
        // 只有「已付款且未发货」才允许自动放行；
        // 已发货/已完成涉及退货，必须人工把关
        if (!OrderStatus.PAID.getCode().equals(order.getStatus())) return false;
        return refundAmount.compareTo(refundProperties.getAutoAuditAmount()) <= 0;
    }

    /** 生成商户退款单号（与支付单号风格保持一致：前缀 + 订单号 + 时间戳） */
    private String generateRefundNo(Order order) {
        return "REF" + order.getOrderNo() + "-" + System.currentTimeMillis();
    }

    /** 规整退款原因：去空白、限长（字段是 varchar(255)，留出充足余量） */
    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "用户申请退款";
        }
        String text = reason.trim();
        int max = refundProperties.getMaxReasonLength() == null ? 200 : refundProperties.getMaxReasonLength();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 去空白后为空则返回 null —— 用于把前端传来的空串/纯空格统一成"没填" */
    private String trimToNull(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.trim();
    }
}
