package com.zhou.peakflow.service;

import com.zhou.peakflow.dto.pay.RefundNotifyDto;
import com.zhou.peakflow.dto.refund.ApplyRefundDto;
import com.zhou.peakflow.dto.refund.RefundQueryDto;
import com.zhou.peakflow.vo.PageVo;
import com.zhou.peakflow.vo.RefundVo;

/**
 * 退款服务：退款与售后共用一条状态机，分界线是"货出没出仓库"
 */
public interface RefundService {

    // 用户提交退款申请，同时也是"申请售后"的入口；类型由服务端按订单状态推导
    Long apply(Long userId, ApplyRefundDto dto);

    // 管理员审核：仅退款通过后直接进退款中并发起退款；退货退款只进"待用户退货"，不发起退款
    void audit(Long operatorId, String operatorName, Long refundId, boolean approved, String remark);

    // 商家签收退货（仅退货退款）：待用户退货 → 退款中，同时回补库存并发起第三方退款
    void confirmReturnReceived(Long operatorId, String operatorName, Long refundId);

    // 用户撤销申请：待审核、待用户退货两个时机可撤，商家签收后不可撤
    void cancel(Long userId, Long refundId);

    // 管理员重新发起退款：仅"退款失败"可重试，沿用同一退款单号避免重复打款
    void retry(Long operatorId, String operatorName, Long refundId);

    // 管理员作废退款单：仅"退款失败"可作废，是渠道确实退不了时释放订单占用的泄压阀
    void close(Long operatorId, String operatorName, Long refundId, String remark);

    // 处理第三方退款结果异步通知，是全流程唯一能"确认退款"的入口，返回 success 表示已受理
    String handleRefundNotify(RefundNotifyDto dto, String rawBody);

    // 查询退款单详情（只能查自己的）
    RefundVo detail(Long userId, Long refundId);

    // 管理端查询退款单详情（审核工作台用，不做"只能查自己的"限制，权限由 refund:read 控制）
    RefundVo adminDetail(Long refundId);

    // 我的退款单列表（分页）
    PageVo<RefundVo> myRefunds(Long userId, RefundQueryDto queryDto);

    // 管理端退款单列表（分页，可按状态 / 订单号筛选）
    PageVo<RefundVo> adminList(RefundQueryDto queryDto);

    /**
     * 兜底收敛「卡在中间态」的退款单，返回本轮处理的笔数（定时任务调用）。
     *
     * 退款单有三个中间态，任何一个卡住都会造成实际损害，所以三条规则都要有：
     * 1. 待审核超期 -> 自动驳回。否则一直占着 uk_open_order「一个订单只能有一条未完结退款单」
     *    的唯一名额，用户无法重新申请；
     * 2. 退款中卡住 -> 自动重发退款请求（沿用同一退款单号，三方视为同一笔），
     *    但绝不自动改状态 —— 钱可能已经退了，自动判「失败」会导致后续被误作废而资损；
     * 3. 待用户退货超期 -> 自动作废，释放对订单的占用（已签收的绝不会进候选集）。
     */
    int sweepTimeoutRefunds(int limit);
}

