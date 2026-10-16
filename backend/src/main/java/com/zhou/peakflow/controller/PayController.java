package com.zhou.peakflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhou.peakflow.dto.pay.PayNotifyDto;
import com.zhou.peakflow.dto.pay.PrepayRequestDto;
import com.zhou.peakflow.pojo.PayRecord;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.service.PayService;
import com.zhou.peakflow.vo.PrepayVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 支付控制器（商户侧对外接口）
 */
@Slf4j
@RestController
@RequestMapping("/pay")
@RequiredArgsConstructor
@Tag(name = "支付管理", description = "支付相关接口")
public class PayController extends BaseController {

    private final PayService payService;
    private final ObjectMapper objectMapper;

    private static final String LOG_PREFIX = "【支付接口】";

    @PostMapping("/prepay")
    @Operation(summary = "发起支付（返回收银台地址）")
    public Result<PrepayVo> prepay(@RequestHeader("Authorization") String token,
                                   @RequestBody @Valid PrepayRequestDto dto) {
        Long userId = getUserIdFromToken(token);
        log.info("{}发起支付请求，userId={}, orderId={}", LOG_PREFIX, userId, dto.getOrderId());
        return Result.success("支付单创建成功", payService.prepay(userId, dto.getOrderId()));
    }

    // 支付结果异步通知（第三方回调）
    @PostMapping(value = "/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "支付结果异步通知（供第三方回调，无需登录）")
    public String notify(@RequestBody PayNotifyDto dto) {
        String rawBody;
        try {
            // 保留回调报文原文，落库留档，方便日后对账/排查
            rawBody = objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            rawBody = String.valueOf(dto);
        }

        try {
            return payService.handleNotify(dto, rawBody);
        } catch (Exception e) {
            // 处理异常时也必须给第三方一个"失败"应答，让它稍后重推，避免这笔支付就此丢失
            log.error("{}支付回调处理异常，outTradeNo={}", LOG_PREFIX, dto.getOutTradeNo(), e);
            return "failure";
        }
    }

    // 查询支付结果（前端轮询）
    @GetMapping("/query/{outTradeNo}")
    @Operation(summary = "查询支付结果")
    public Result<PayRecord> query(@RequestHeader("Authorization") String token,
                                   @PathVariable String outTradeNo) {
        Long userId = getUserIdFromToken(token);
        log.info("{}查询支付结果，userId={}, outTradeNo={}", LOG_PREFIX, userId, outTradeNo);
        return Result.success(payService.query(userId, outTradeNo));
    }
}
