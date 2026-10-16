package com.zhou.peakflow.service;

import com.zhou.peakflow.dto.pay.PayNotifyDto;
import com.zhou.peakflow.pojo.PayRecord;
import com.zhou.peakflow.vo.PrepayVo;

/**
 * 支付服务：订单管业务状态，支付单管资金流水，后者是幂等与对账的依据
 */
public interface PayService {

    // 发起支付：创建（或复用）支付单并返回收银台地址
    PrepayVo prepay(Long userId, Long orderId);

    // 处理第三方支付结果回调，是全流程唯一能"确认收款"的入口；返回 success 表示已受理
    String handleNotify(PayNotifyDto dto, String rawBody);

    // 主动查询支付结果（前端轮询用）
    PayRecord query(Long userId, String outTradeNo);

    // 关闭某订单下"待支付"的支付单（订单取消 / 超时关单时调用，防止旧收银台链接继续付款）
    void closeByOrderId(Long orderId);

    /**
     * 兜底扫描：关闭「已过有效期」的待支付单，返回实际关闭的笔数（定时任务调用）。
     */
    int sweepExpiredPayRecords(int limit);
}
