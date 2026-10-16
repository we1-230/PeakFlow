package com.zhou.peakflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.config.RefundProperties;
import com.zhou.peakflow.enums.PayStatus;
import com.zhou.peakflow.enums.RefundStatus;
import com.zhou.peakflow.enums.RefundType;
import com.zhou.peakflow.dto.pay.RefundNotifyDto;
import com.zhou.peakflow.dto.refund.ApplyRefundDto;
import com.zhou.peakflow.dto.refund.RefundQueryDto;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.mapper.RefundRecordMapper;
import com.zhou.peakflow.pojo.PayRecord;
import com.zhou.peakflow.pojo.RefundRecord;
import com.zhou.peakflow.service.OrderService;
import com.zhou.peakflow.service.RefundService;
import com.zhou.peakflow.utils.PaySignUtil;
import com.zhou.peakflow.vo.PageVo;
import com.zhou.peakflow.vo.RefundVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 退款服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {

    private final RefundRecordMapper refundRecordMapper;
    private final PayRecordMapper payRecordMapper;
    private final RefundTxService refundTxService;
    private final OrderService orderService;
    private final RedissonClient redissonClient;
    private final MockPayProperties mockPayProperties;
    private final ObjectMapper objectMapper;
    /** 退款策略：中间态超时阈值（重试间隔 / 自动重试上限 / 待审核与待退货超期天数） */
    private final RefundProperties refundProperties;

    private static final String LOG_PREFIX = "【退款服务】";

    /** 系统自动操作时写入的操作人名称（auditor_id 留空，避免把系统行为伪装成某个管理员做的） */
    private static final String SYSTEM_OPERATOR_NAME = "系统自动";

    /** 调用第三方退款接口的 HTTP 客户端（连接/读取超时都设短，避免把用户线程挂死） */
    private static final RestTemplate REST_TEMPLATE = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    // 1.  用户申请退款 / 申请售后
    @Override
    public Long apply(Long userId, ApplyRefundDto dto) {
        if (dto == null || dto.getOrderId() == null) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "订单ID不能为空");
        }
        Long orderId = dto.getOrderId();
        log.info("{}【申请退款】userId={}, orderId={}, 退货物流={}/{}",
                LOG_PREFIX, userId, orderId,
                dto.getReturnLogisticsCompany(), dto.getReturnTrackingNo());

        String lockKey = "lock:refund:apply:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        RefundRecord record;
        try {
            // 加锁范围只包住"校验 + 落库"，不包第三方调用 —— 详见类注释
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException("操作过于频繁，请稍后重试");
            }

            // 事务内：校验 + 落库（走代理，@Transactional 生效）
            record = refundTxService.doApply(userId, orderId, dto.getReason(),
                    dto.getReturnLogisticsCompany(), dto.getReturnTrackingNo());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        if (RefundStatus.REFUNDING.getCode().equals(record.getStatus())) {
            requestThirdPartyRefund(record);
        }
        return record.getId();
    }

    // 1. .5 商家签收退货 → 推进到退款中 → 发起第三方退款（仅售后会走到这里）
    @Override
    public void confirmReturnReceived(Long operatorId, String operatorName, Long refundId) {
        log.info("{}【确认收到退货】refundId={}, operator={}", LOG_PREFIX, refundId, operatorName);

        // 事务内：打上签收时间戳 + 把状态从「待用户退货」推到「退款中」（CAS + 幂等闸门）
        RefundRecord record = refundTxService.doConfirmReturnReceived(operatorId, operatorName, refundId);

        // doConfirmReturnReceived 返回 null = 之前已经签收过（管理员双击 / 并发点击 / 网络重试）。
        // 此时必须直接结束：库存已经在第一次签收时回补过，退款也已经发起过，重来一次就是双份。
        if (record == null) {
            log.info("{}退货此前已签收，本次为幂等重复调用，跳过库存回补与发起退款，refundId={}",
                    LOG_PREFIX, refundId);
            return;
        }


        log.info("{}已签收退货并完成退货入库，refundNo={}, orderId={}",
                LOG_PREFIX, record.getRefundNo(), record.getOrderId());

        // 事务提交之后才对外发起退款：HTTP 不能进事务，否则接口一慢就长时间占着连接与行锁。
        //   退货退款只有走到这里（实物已入库）才允许动钱，这就是"先入库、后放款"的落地点。
        requestThirdPartyRefund(record);
    }


    // 管理员审核
    @Override
    public void audit(Long operatorId, String operatorName, Long refundId, boolean approved, String remark) {
        log.info("{}【审核退款】refundId={}, approved={}, auditor={}", LOG_PREFIX, refundId, approved, operatorName);

        // 事务内完成审核状态流转；通过时返回退款单，供后续判断是否需要发起第三方退款
        RefundRecord approvedRecord = refundTxService.doAudit(operatorId, operatorName, refundId, approved, remark);

        // 只有落到「退款中」才发起第三方退款。
        if (approvedRecord != null
                && RefundStatus.REFUNDING.getCode().equals(approvedRecord.getStatus())) {
            // 事务已提交（审核通过 → 退款中），现在才真正对外发起退款
            requestThirdPartyRefund(approvedRecord);
        }
    }


    // 3.  用户撤销
    @Override
    public void cancel(Long userId, Long refundId) {
        log.info("{}【撤销退款】userId={}, refundId={}", LOG_PREFIX, userId, refundId);
        refundTxService.doCancel(userId, refundId);
    }

    // 4.  管理员重试
    @Override
    public void retry(Long operatorId, String operatorName, Long refundId) {
        log.info("{}【重试退款】refundId={}, operator={}", LOG_PREFIX, refundId, operatorName);
        RefundRecord record = refundTxService.doRetry(operatorId, operatorName, refundId);
        // 重试沿用同一个商户退款单号，第三方据此识别为"同一笔退款"，不会重复打款
        requestThirdPartyRefund(record);
    }

    @Override
    public void close(Long operatorId, String operatorName, Long refundId, String remark) {
        log.info("{}【作废退款】refundId={}, operator={}", LOG_PREFIX, refundId, operatorName);
        // 作废只改单据状态、不涉及第三方，所以不需要再调渠道
        refundTxService.doClose(operatorId, operatorName, refundId, remark);
    }

    // ---- 兜底扫描：中间态超时收敛（定时任务调用） ----

    /**
     * 兜底收敛卡在中间态的退款单。
     *
     * 各规则"关闭/推进前要检查什么"是本方法的重点，详见各私有方法的注释。
     * 全部走 CAS（{@code WHERE status = 旧状态}），多实例并发扫描天然安全，不需要加锁。
     */
    @Override
    public int sweepTimeoutRefunds(int limit) {
        int handled = 0;
        handled += sweepTimeoutApplying(limit);
        handled += sweepStuckRefunding(limit);
        handled += sweepTimeoutWaitReturn(limit);
        return handled;
    }

    /**
     * 1.  待审核超期 → 自动驳回。
     */
    private int sweepTimeoutApplying(int limit) {
        int days = refundProperties.resolveApplyingTimeoutDays();
        LocalDateTime deadline = LocalDateTime.now().minusDays(days);
        List<Long> ids = refundRecordMapper.selectTimeoutApplyingIds(deadline, limit);
        if (CollectionUtils.isEmpty(ids)) {
            return 0;
        }

        int done = 0;
        for (Long id : ids) {
            try {
                int rows = refundRecordMapper.reject(id, null, SYSTEM_OPERATOR_NAME,
                        "申请后超过 " + days + " 天未审核，系统自动驳回，如需继续请重新申请");
                if (rows > 0) {
                    done++;
                    log.info("{}退款申请超期自动驳回，refundId={}, 超期阈值={}天", LOG_PREFIX, id, days);
                }
            } catch (Exception e) {
                log.error("{}超期退款申请自动驳回失败，refundId={}", LOG_PREFIX, id, e);
            }
        }
        log.info("{}待审核超期扫描完成，候选={}，已驳回={}", LOG_PREFIX, ids.size(), done);
        return done;
    }

    /**
     * 2.  退款中卡住 → 自动重发退款请求（不改状态）。
     * 避免三方长期故障时无限打扰对方；到点后交给人工按「重试 / 作废」处理。
     */
    private int sweepStuckRefunding(int limit) {
        int delayMinutes = refundProperties.resolveRetryDelayMinutes();
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(delayMinutes);
        List<Long> ids = refundRecordMapper.selectStuckRefundingIds(deadline, limit);
        if (CollectionUtils.isEmpty(ids)) {
            return 0;
        }

        int resent = 0;
        int needsManual = 0;
        for (Long id : ids) {
            try {
                RefundRecord record = refundRecordMapper.selectById(id);
                if (record == null || !RefundStatus.REFUNDING.getCode().equals(record.getStatus())) {
                    // 期间已被回调或人工处理掉
                    continue;
                }

                if (isBeyondAutoRetryWindow(record)) {
                    needsManual++;
                    log.error("{}【需人工介入】退款单卡在「退款中」已超过自动重试窗口，"
                                    + "refundNo={}, orderNo={}, 申请时间={}，请管理员在重试 / 作废中择一处理",
                            LOG_PREFIX, record.getRefundNo(), record.getOrderNo(), record.getApplyTime());
                    continue;
                }

                // 先 CAS 占位（1→1，刷新 update_time 形成限流），拿到才重发
                if (refundRecordMapper.markRefunding(id) == 0) {
                    continue;
                }
                requestThirdPartyRefund(record);
                resent++;
            } catch (Exception e) {
                log.error("{}自动重发退款请求失败，refundId={}", LOG_PREFIX, id, e);
            }
        }
        log.info("{}卡住的退款单扫描完成，候选={}，已自动重发={}，超窗需人工={}",
                LOG_PREFIX, ids.size(), resent, needsManual);
        return resent;
    }

    /** 是否已超出自动重试窗口（以申请时刻为锚点；缺失则按超窗处理：只告警、不再自动重发） */
    private boolean isBeyondAutoRetryWindow(RefundRecord record) {
        LocalDateTime anchor = record.getApplyTime() != null
                ? record.getApplyTime()
                : record.getCreateTime();
        if (anchor == null) {
            log.warn("{}退款单缺少申请时间，按超窗处理（仅告警），refundNo={}",
                    LOG_PREFIX, record.getRefundNo());
            return true;
        }
        return anchor.plusMinutes(refundProperties.resolveAutoRetryMaxMinutes())
                .isBefore(LocalDateTime.now());
    }

    /**
     * 3.  待用户退货超期 → 自动作废。
     */
    private int sweepTimeoutWaitReturn(int limit) {
        int days = refundProperties.resolveWaitReturnTimeoutDays();
        LocalDateTime deadline = LocalDateTime.now().minusDays(days);
        List<Long> ids = refundRecordMapper.selectTimeoutWaitReturnIds(deadline, limit);
        if (CollectionUtils.isEmpty(ids)) {
            return 0;
        }

        int done = 0;
        for (Long id : ids) {
            try {
                if (refundRecordMapper.closeAbandonedWaitReturn(id,
                        "超过 " + days + " 天未寄回退货，系统自动作废该售后单") > 0) {
                    done++;
                    log.info("{}售后单超期自动作废，refundId={}, 超期阈值={}天", LOG_PREFIX, id, days);
                }
            } catch (Exception e) {
                log.error("{}售后单超期自动作废失败，refundId={}", LOG_PREFIX, id, e);
            }
        }
        log.info("{}待用户退货超期扫描完成，候选={}，已作废={}", LOG_PREFIX, ids.size(), done);
        return done;
    }

    // 5.  处理第三方退款结果回调（唯一能"确认退款"的入口）
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String handleRefundNotify(RefundNotifyDto dto, String rawBody) {
        log.info("{}收到退款回调，outRefundNo={}, refundTradeNo={}, status={}, amount={}",
                LOG_PREFIX, dto.getOutRefundNo(), dto.getRefundTradeNo(), dto.getStatus(), dto.getRefundAmount());

        // 1. 验签：报文被篡改或来自伪造方，直接拒绝，绝不能入账
        if (!verifySign(dto)) {
            log.error("{}退款回调验签失败，已拒绝，outRefundNo={}", LOG_PREFIX, dto.getOutRefundNo());
            return "failure";
        }

        // 2. 找到本地退款单
        RefundRecord record = refundRecordMapper.selectByRefundNo(dto.getOutRefundNo());
        if (record == null) {
            log.error("{}退款单不存在，拒绝处理，outRefundNo={}", LOG_PREFIX, dto.getOutRefundNo());
            return "failure";
        }

        boolean successBranch = Integer.valueOf(1).equals(dto.getStatus());

        // 3. 幂等第一道：本地已经是同一个终态，直接应答成功
        // 第三方重复通知是常态，提前返回可避免后续无意义的写操作
        if (RefundStatus.SUCCESS.getCode().equals(record.getStatus())) {
            log.info("{}重复回调（已退款成功），直接返回 success，outRefundNo={}", LOG_PREFIX, dto.getOutRefundNo());
            return "success";
        }
        if (!successBranch && RefundStatus.FAILED.getCode().equals(record.getStatus())) {
            log.info("{}重复回调（已记录退款失败），直接返回 success，outRefundNo={}", LOG_PREFIX, dto.getOutRefundNo());
            return "success";
        }

        // 3.1 状态守卫（这里曾经埋着一个资金不一致的事故级缺陷）
        boolean allowed = RefundStatus.REFUNDING.getCode().equals(record.getStatus())
                || (successBranch && RefundStatus.FAILED.getCode().equals(record.getStatus()));
        if (!allowed) {
            log.error("{}退款单状态不允许入账，status={}, 回调结果={}, outRefundNo={}",
                    LOG_PREFIX, record.getStatus(), dto.getStatus(), dto.getOutRefundNo());
            return "failure";
        }

        // 4. 金额校验（防"改价攻击"）
        if (dto.getRefundAmount() == null
                || record.getRefundAmount().compareTo(dto.getRefundAmount()) != 0) {
            log.error("{}退款金额不一致，本地={}, 回调={}, outRefundNo={}",
                    LOG_PREFIX, record.getRefundAmount(), dto.getRefundAmount(), dto.getOutRefundNo());
            return "failure";
        }

        String content = truncate(rawBody, 1000);

        // 5. 按回调结果分支处理
        if (successBranch) {
            return handleRefundSuccess(record, dto, content);
        }
        return handleRefundFailure(record, dto, content);
    }

    // 退款成功：CAS 落库 → 订单置已退款 + 回滚库存 → 支付单置已退款
    private String handleRefundSuccess(RefundRecord record, RefundNotifyDto dto, String content) {
        // 幂等第二道，也是真正的并发闸门：WHERE status IN (1, 3)
        // 1=退款中（正常流程），3=退款失败（本地误判失败，但渠道实际成功）
        int rows = refundRecordMapper.markRefundSuccess(
                dto.getOutRefundNo(), dto.getRefundTradeNo(), content);
        if (rows == 0) {
            RefundRecord latest = refundRecordMapper.selectByRefundNo(dto.getOutRefundNo());
            if (latest != null && RefundStatus.SUCCESS.getCode().equals(latest.getStatus())) {
                log.warn("{}并发回调，退款单已被其他请求处理，outRefundNo={}",
                        LOG_PREFIX, dto.getOutRefundNo());
                return "success";
            }
            log.error("{}退款单入账失败但收到成功回调，需人工介入！outRefundNo={}, status={}",
                    LOG_PREFIX, dto.getOutRefundNo(),
                    latest == null ? "null" : latest.getStatus());
            return "failure";
        }

        if (RefundStatus.FAILED.getCode().equals(record.getStatus())) {
            log.warn("{}【状态自愈】退款单本地曾因请求异常被判失败，但渠道确认退款成功，已修正入账，"
                            + "refundNo={}, orderId={}", LOG_PREFIX, record.getRefundNo(), record.getOrderId());
        }

        // 订单 : 已退款 —— 只有钱真的退回去了，订单状态才允许变。
        orderService.markOrderRefunded(record.getOrderId(), record.getRefundAmount(), record.getRefundType());

        // 支付流水 : 支付成功 → 已退款
        syncPayRecordRefunded(record);

        log.info("{}退款成功入账完成，refundNo={}, orderId={}, amount={}",
                LOG_PREFIX, record.getRefundNo(), record.getOrderId(), record.getRefundAmount());
        return "success";
    }

    /** 退款失败：把退款单置为失败并留痕，等待管理员重试 */
    private String handleRefundFailure(RefundRecord record, RefundNotifyDto dto, String content) {
        String reason = StringUtils.hasText(dto.getReason()) ? dto.getReason() : "第三方退款失败";
        int rows = refundRecordMapper.markRefundFailed(dto.getOutRefundNo(), truncate(reason, 255), content);

        if (rows == 0) {
            // 重新查一次，判断为什么没更新上
            RefundRecord latest = refundRecordMapper.selectByRefundNo(dto.getOutRefundNo());

            if (latest != null
                    && (RefundStatus.SUCCESS.getCode().equals(latest.getStatus())
                        || RefundStatus.FAILED.getCode().equals(latest.getStatus()))) {
                log.warn("{}并发回调或重复通知，退款单已处于终态，outRefundNo={}, status={}",
                        LOG_PREFIX, dto.getOutRefundNo(), latest.getStatus());
                return "success";
            }
            log.error("{}退款单未能置为失败但收到失败回调，需人工介入！outRefundNo={}, status={}",
                    LOG_PREFIX, dto.getOutRefundNo(), latest == null ? "null" : latest.getStatus());
            return "failure";
        }

        log.error("{}退款失败，refundNo={}, reason={}", LOG_PREFIX, record.getRefundNo(), reason);
        return "success";
    }

    @Override
    public RefundVo detail(Long userId, Long refundId) {
        RefundRecord record = refundRecordMapper.selectById(refundId);
        if (record == null) {
            throw new BusinessException(ResultCode.REFUND_NOT_FOUND);
        }
        // 防越权：只能看自己的退款单
        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.REFUND_NO_PERMISSION);
        }
        return toVo(record);
    }

    @Override
    public RefundVo adminDetail(Long refundId) {
        RefundRecord record = refundRecordMapper.selectById(refundId);
        if (record == null) {
            throw new BusinessException(ResultCode.REFUND_NOT_FOUND);
        }
        return toVo(record);
    }

    @Override
    public PageVo<RefundVo> myRefunds(Long userId, RefundQueryDto queryDto) {
        // 强制以当前登录用户为过滤条件，杜绝"传别人的 userId 查别人退款单"
        queryDto.setUserId(userId);
        return pageQuery(queryDto);
    }

    @Override
    public PageVo<RefundVo> adminList(RefundQueryDto queryDto) {
        return pageQuery(queryDto);
    }

    private PageVo<RefundVo> pageQuery(RefundQueryDto queryDto) {
        Page<RefundRecord> page = new Page<>(queryDto.getPageNum(), queryDto.getPageSize());

        LambdaQueryWrapper<RefundRecord> wrapper = new LambdaQueryWrapper<RefundRecord>()
                .eq(queryDto.getUserId() != null, RefundRecord::getUserId, queryDto.getUserId())
                .eq(queryDto.getStatus() != null, RefundRecord::getStatus, queryDto.getStatus())
                .eq(StringUtils.hasText(queryDto.getRefundNo()), RefundRecord::getRefundNo, queryDto.getRefundNo())
                .eq(StringUtils.hasText(queryDto.getOrderNo()), RefundRecord::getOrderNo, queryDto.getOrderNo())
                .orderByDesc(RefundRecord::getId);

        Page<RefundRecord> result = refundRecordMapper.selectPage(page, wrapper);
        List<RefundVo> list = CollectionUtils.isEmpty(result.getRecords())
                ? Collections.emptyList()
                : result.getRecords().stream().map(this::toVo).collect(Collectors.toList());

        return new PageVo<>(list, result.getTotal(), result.getPages(),
                result.getCurrent(), result.getSize());
    }


    // 7.  向第三方发起退款请求
    private void requestThirdPartyRefund(RefundRecord record) {
        String refundUrl = mockPayProperties.getRefundUrl();
        if (!StringUtils.hasText(refundUrl)) {
            log.error("{}未配置第三方退款接口地址（mock-pay.refund-url），refundNo={}",
                    LOG_PREFIX, record.getRefundNo());
            refundTxService.markRequestFailed(record.getRefundNo(), "未配置第三方退款接口地址");
            return;
        }

        long timestamp = System.currentTimeMillis();

        // 参与签名的参数（方向：商户 → 第三方）
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("out_refund_no", record.getRefundNo());
        params.put("out_trade_no", record.getOutTradeNo());
        params.put("refund_amount", record.getRefundAmount().toPlainString());
        params.put("notify_url", mockPayProperties.getRefundNotifyUrl());
        params.put("timestamp", timestamp);
        params.put("sign", PaySignUtil.sign(params, mockPayProperties.getSecretKey()));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 显式只要 JSON：第三方接口不是内容协商的场合，Accept 必须钉死。
            // 不写的话 RestTemplate 会按自身转换器链拼出
            // `text/plain, application/xml, text/xml, application/json, ...`，
            // 一旦对端（或本服务）classpath 上有 XML 转换器，应答就会变成 XML 而不是 JSON。
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(params, headers);

            String resp = REST_TEMPLATE.postForObject(refundUrl, entity, String.class);
            log.info("{}第三方退款接口应答：refundNo={}, resp={}", LOG_PREFIX, record.getRefundNo(), resp);

            // 同步应答只代表"请求受理了"，最终结果以异步回调为准。
            // 判定必须是三态：只有第三方"明确拒绝"才允许置为失败；
            // "读不懂应答"绝不能当成拒绝 —— 钱可能已经退出去了，把它标成「退款失败」，
            // 后续人工重试/作废就会重复打款或吞掉这笔退款，直接造成资损。
            AcceptResult acceptResult = parseAcceptResult(resp);
            if (acceptResult == AcceptResult.REJECTED) {
                refundTxService.markRequestFailed(record.getRefundNo(),
                        "第三方拒绝受理：" + truncate(resp, 200));
            } else if (acceptResult == AcceptResult.UNKNOWN) {
                // 保留「退款中」：异步回调到了就自动收敛，没到则由 sweepStuckRefunding
                // 用同一退款单号重发（三方按 out_refund_no 幂等），人工也可按需介入
                log.warn("{}无法判定第三方是否已受理退款，保留「退款中」交由回调或兜底重发收敛，"
                                + "refundNo={}, resp={}",
                        LOG_PREFIX, record.getRefundNo(), truncate(resp, 200));
            }
        } catch (Exception e) {
            log.error("{}调用第三方退款接口异常，refundNo={}", LOG_PREFIX, record.getRefundNo(), e);
            refundTxService.markRequestFailed(record.getRefundNo(),
                    "调用第三方退款接口异常：" + truncate(e.getMessage(), 200));
        }
    }

    /**
     * 第三方同步应答的判定结果。
     *
     * <p>必须有 UNKNOWN 这一档：应答不是我们能识别的 JSON（网关错误页、HTML、被中间层改写格式等）时，
     * 既不能认为已受理、也不能认为被拒绝 —— 猜错的代价是资损，只能保留中间态交给兜底收敛。
     */
    private enum AcceptResult {
        /** 明确了受理 */
        ACCEPTED,
        /** 明确了拒绝受理 */
        REJECTED,
        /** 读不懂应答，无法判定 */
        UNKNOWN
    }

    /** 解析第三方同步应答，判断请求是否被受理 */
    @SuppressWarnings("unchecked")
    private AcceptResult parseAcceptResult(String resp) {
        if (!StringUtils.hasText(resp)) {
            // 空应答同样是"对方什么都没说"，不能推断成拒绝
            return AcceptResult.UNKNOWN;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(resp, Map.class);
            if (!map.containsKey("success")) {
                // 是 JSON，但没有约定的受理标志位：判不了，也不能当成拒绝
                return AcceptResult.UNKNOWN;
            }
            return Boolean.TRUE.equals(map.get("success"))
                    ? AcceptResult.ACCEPTED
                    : AcceptResult.REJECTED;
        } catch (Exception e) {
            return AcceptResult.UNKNOWN;
        }
    }

    /** 校验回调签名（方向：第三方 → 商户） */
    private boolean verifySign(RefundNotifyDto dto) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("out_refund_no", dto.getOutRefundNo());
        params.put("refund_trade_no", dto.getRefundTradeNo());
        params.put("out_trade_no", dto.getOutTradeNo());
        params.put("refund_amount", dto.getRefundAmount() == null ? null : dto.getRefundAmount().toPlainString());
        params.put("status", dto.getStatus());
        params.put("reason", dto.getReason());
        params.put("timestamp", dto.getTimestamp());
        return PaySignUtil.verify(params, mockPayProperties.getSecretKey(), dto.getSign());
    }

    // 把该笔退款对应的支付流水置为「已退款」
    private void syncPayRecordRefunded(RefundRecord record) {
        String outTradeNo = record.getOutTradeNo();

        if (!StringUtils.hasText(outTradeNo)) {
            // 理论上不该发生（申请退款时是从支付流水抄过来的），但一旦发生，
            // 支付单会永远停在"支付成功"，必须让人看见，而不是静默失败
            log.error("{}退款单缺少原支付单号，支付流水状态无法同步，需人工介入！refundNo={}, orderId={}",
                    LOG_PREFIX, record.getRefundNo(), record.getOrderId());
            return;
        }

        int payRows = payRecordMapper.markRefunded(outTradeNo);
        if (payRows > 0) {
            log.info("{}支付流水已置为已退款，outTradeNo={}", LOG_PREFIX, outTradeNo);
            return;
        }

        // 更新 0 行，回查一次判断到底是哪种情况
        PayRecord pay = payRecordMapper.selectByOutTradeNo(outTradeNo);
        if (pay == null) {
            log.error("{}支付流水不存在，需人工介入！refundNo={}, outTradeNo={}",
                    LOG_PREFIX, record.getRefundNo(), outTradeNo);
        } else if (PayStatus.REFUNDED.getCode().equals(pay.getStatus())) {
            // 幂等：重复回调时支付单早已是已退款，属正常路径
            log.info("{}支付流水已是已退款（重复回调，幂等），outTradeNo={}", LOG_PREFIX, outTradeNo);
        } else {
            log.error("{}【数据不一致】退款单已成功但支付流水未置为已退款，需人工介入！"
                            + "refundNo={}, outTradeNo={}, payStatus={}",
                    LOG_PREFIX, record.getRefundNo(), outTradeNo, pay.getStatus());
        }
    }

    /** 实体 → 展示对象 */
    private RefundVo toVo(RefundRecord record) {
        RefundStatus status = RefundStatus.fromCode(record.getStatus());
        RefundType type = RefundType.fromCode(record.getRefundType());

        // 「能不能签收退货」由服务端算好，前端不用再维护一份状态机。
        boolean canConfirmReturn = RefundType.isReturnAndRefund(record.getRefundType())
                && RefundStatus.canConfirmReturn(record.getStatus());

        // 「能不能撤销」：待审核、待用户退货 两个时机可以；
        // 商家签收后状态已进入退款中，钱在路上，不能单方面撤销。
        boolean canCancel = RefundStatus.canCancel(record.getStatus());

        return RefundVo.builder()
                .id(record.getId())
                .refundNo(record.getRefundNo())
                .orderId(record.getOrderId())
                .orderNo(record.getOrderNo())
                .refundAmount(record.getRefundAmount())
                .refundType(record.getRefundType())
                .refundTypeDesc(type == null ? "未知" : type.getDesc())
                .refundChannel(record.getRefundChannel())
                .reason(record.getReason())
                .returnLogisticsCompany(record.getReturnLogisticsCompany())
                .returnTrackingNo(record.getReturnTrackingNo())
                .returnReceivedTime(record.getReturnReceivedTime())
                .status(record.getStatus())
                .statusDesc(status == null ? "未知" : status.getDesc())
                .auditorName(record.getAuditorName())
                .auditTime(record.getAuditTime())
                .auditRemark(record.getAuditRemark())
                .failReason(record.getFailReason())
                .refundTradeNo(record.getRefundTradeNo())
                .applyTime(record.getApplyTime())
                .refundTime(record.getRefundTime())
                .canConfirmReturn(canConfirmReturn)
                .canCancel(canCancel)
                .canRetry(RefundStatus.canRetry(record.getStatus()))
                .canClose(RefundStatus.canClose(record.getStatus()))
                .build();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
