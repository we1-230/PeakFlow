package com.zhou.peakflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhou.peakflow.dto.pay.RefundNotifyDto;
import com.zhou.peakflow.dto.refund.ApplyRefundDto;
import com.zhou.peakflow.dto.refund.AuditRefundDto;
import com.zhou.peakflow.dto.refund.RefundQueryDto;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.service.RefundService;
import com.zhou.peakflow.utils.JwtUtil;
import com.zhou.peakflow.vo.PageVo;
import com.zhou.peakflow.vo.RefundVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 退款控制器
 */
@Slf4j
@RestController
@RequestMapping("/refund")
@RequiredArgsConstructor
@Tag(name = "退款管理", description = "退款申请、审核、查询相关接口")
public class RefundController extends BaseController {

    private final RefundService refundService;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    private static final String LOG_PREFIX = "【退款接口】";

    @PostMapping("/apply")
    @Operation(summary = "提交退款申请 / 申请售后")
    public Result<Long> apply(@RequestHeader("Authorization") String token,
                              @RequestBody @Valid ApplyRefundDto dto) {
        Long userId = getUserIdFromToken(token);
        log.info("{}提交退款/售后申请，userId={}, orderId={}", LOG_PREFIX, userId, dto.getOrderId());

        Long refundId = refundService.apply(userId, dto);
        return Result.success("申请已提交，请等待审核", refundId);
    }

    // 查询退款单详情（含审核意见、失败原因，前端据此展示进度）
    @GetMapping("/detail/{refundId}")
    @Operation(summary = "查询退款单详情")
    public Result<RefundVo> detail(@RequestHeader("Authorization") String token,
                                   @PathVariable Long refundId) {
        Long userId = getUserIdFromToken(token);
        return Result.success(refundService.detail(userId, refundId));
    }

    @PostMapping("/list")
    @Operation(summary = "我的退款单列表")
    public Result<PageVo<RefundVo>> myRefunds(@RequestHeader("Authorization") String token,
                                              @RequestBody @Valid RefundQueryDto queryDto) {
        Long userId = getUserIdFromToken(token);
        log.info("{}查询我的退款单，userId={}, pageNum={}", LOG_PREFIX, userId, queryDto.getPageNum());
        return Result.success(refundService.myRefunds(userId, queryDto));
    }

    // 撤销退款申请（仅「待审核」可撤销）
    @PostMapping("/cancel/{refundId}")
    @Operation(summary = "撤销退款申请")
    public Result<Void> cancel(@RequestHeader("Authorization") String token,
                               @PathVariable Long refundId) {
        Long userId = getUserIdFromToken(token);
        log.info("{}撤销退款申请，userId={}, refundId={}", LOG_PREFIX, userId, refundId);

        refundService.cancel(userId, refundId);
        return Result.success("已撤销退款申请");
    }

    // 退款结果异步通知
    @PostMapping(value = "/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "退款结果异步通知（供第三方回调，无需登录）")
    public String notify(@RequestBody RefundNotifyDto dto) {
        String rawBody;
        try {
            rawBody = objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            rawBody = String.valueOf(dto);
        }

        try {
            return refundService.handleRefundNotify(dto, rawBody);
        } catch (Exception e) {
            // 处理异常同样要回 failure，让第三方稍后重推，避免这笔退款结果永久丢失
            log.error("{}退款回调处理异常，outRefundNo={}", LOG_PREFIX, dto.getOutRefundNo(), e);
            return "failure";
        }
    }

    // 管理员：退款单列表（审核工作台）
    @PreAuthorize("hasAuthority('refund:read')")
    @PostMapping("/admin/list")
    @Operation(summary = "管理员查询退款单列表")
    public Result<PageVo<RefundVo>> adminList(@RequestBody @Valid RefundQueryDto queryDto) {
        log.info("{}管理员查询退款单列表，pageNum={}, status={}",
                LOG_PREFIX, queryDto.getPageNum(), queryDto.getStatus());
        return Result.success(refundService.adminList(queryDto));
    }

    // 管理员：退款单详情（审核工作台用）
    @PreAuthorize("hasAuthority('refund:read')")
    @GetMapping("/admin/detail/{refundId}")
    @Operation(summary = "管理员查询退款单详情")
    public Result<RefundVo> adminDetail(@PathVariable Long refundId) {
        log.info("{}管理员查询退款单详情，refundId={}", LOG_PREFIX, refundId);
        return Result.success(refundService.adminDetail(refundId));
    }

    // 管理员：确认收到退货（售后退货退款的放款动作）
    @PreAuthorize("hasAuthority('refund:audit')")
    @PostMapping("/admin/confirm-return/{refundId}")
    @Operation(summary = "管理员确认收到退货（售后专用）")
    public Result<Void> confirmReturnReceived(@RequestHeader("Authorization") String token,
                                              @PathVariable Long refundId) {
        Long operatorId = getUserIdFromToken(token);
        String operatorName = jwtUtil.getUsernameFromToken(jwtUtil.removeTokenPrefix(token));
        log.info("{}管理员确认收到退货，refundId={}, operator={}", LOG_PREFIX, refundId, operatorName);

        refundService.confirmReturnReceived(operatorId, operatorName, refundId);
        return Result.success("已确认收到退货，库存已入库，退款已发起，结果以支付渠道回调为准");
    }

    // 管理员：审核退款
    @PreAuthorize("hasAuthority('refund:audit')")
    @PostMapping("/admin/audit")
    @Operation(summary = "管理员审核退款")
    public Result<Void> audit(@RequestHeader("Authorization") String token,
                              @RequestBody @Valid AuditRefundDto dto) {
        Long operatorId = getUserIdFromToken(token);
        String operatorName = jwtUtil.getUsernameFromToken(jwtUtil.removeTokenPrefix(token));
        log.info("{}管理员审核退款，refundId={}, approved={}, operator={}",
                LOG_PREFIX, dto.getRefundId(), dto.getApproved(), operatorName);

        refundService.audit(operatorId, operatorName, dto.getRefundId(),
                Boolean.TRUE.equals(dto.getApproved()), dto.getRemark());

        // 提示语刻意不说「退款成功」：这个返回值只代表"审核动作完成了"，
        // 钱的最终去向由第三方异步回调决定，在这里承诺"已退款"就是替第三方打包票。
        return Result.success(Boolean.TRUE.equals(dto.getApproved())
                ? "审核已通过：仅退款已进入退款处理中；退货退款已进入「待用户退货」，确认收到退货后自动发起退款"
                : "已驳回该退款申请");
    }

    // 管理员：重新发起退款（仅「退款失败」可重试）
    @PreAuthorize("hasAuthority('refund:audit')")
    @PostMapping("/admin/retry/{refundId}")
    @Operation(summary = "管理员重新发起退款")
    public Result<Void> retry(@RequestHeader("Authorization") String token,
                              @PathVariable Long refundId) {
        Long operatorId = getUserIdFromToken(token);
        String operatorName = jwtUtil.getUsernameFromToken(jwtUtil.removeTokenPrefix(token));
        log.info("{}管理员重试退款，refundId={}, operator={}", LOG_PREFIX, refundId, operatorName);

        refundService.retry(operatorId, operatorName, refundId);
        return Result.success("已重新发起退款");
    }

    // 管理员：作废退款单（仅「退款失败」可作废）
    @PreAuthorize("hasAuthority('refund:close')")
    @PostMapping("/admin/close/{refundId}")
    @Operation(summary = "管理员作废退款单")
    public Result<Void> close(@RequestHeader("Authorization") String token,
                              @PathVariable Long refundId,
                              @RequestParam(required = false) String remark) {
        Long operatorId = getUserIdFromToken(token);
        String operatorName = jwtUtil.getUsernameFromToken(jwtUtil.removeTokenPrefix(token));
        log.info("{}管理员作废退款单，refundId={}, operator={}", LOG_PREFIX, refundId, operatorName);

        refundService.close(operatorId, operatorName, refundId, remark);
        return Result.success("退款单已作废，订单已恢复可操作");
    }
}
