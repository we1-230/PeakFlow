package com.zhou.peakflow.controller;

import com.zhou.peakflow.dto.order.CreateOrderDto;
import com.zhou.peakflow.dto.order.OrderQueryDto;
import com.zhou.peakflow.dto.order.UpdateOrderDto;
import com.zhou.peakflow.dto.refund.ApplyRefundDto;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.service.AddressService;
import com.zhou.peakflow.service.CartService;
import com.zhou.peakflow.service.OrderService;
import com.zhou.peakflow.service.PayService;
import com.zhou.peakflow.service.RefundService;
import com.zhou.peakflow.utils.JwtUtil;
import com.zhou.peakflow.vo.OrderVo;
import com.zhou.peakflow.vo.PageVo;
import com.zhou.peakflow.pojo.Address;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 订单控制器
 */
@Slf4j
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
@Tag(name = "订单管理", description = "订单相关接口")
public class OrderController extends BaseController {

    private final OrderService orderService;
    private final CartService cartService;
    private final AddressService addressService;
    private final JwtUtil jwtUtil;
    /** 支付服务：发起支付走它（支付入账只允许第三方回调触发） */
    private final PayService payService;
    /** 退款服务：退款走"申请 → 审核 → 第三方退款 → 回调入账"，不再直接改订单状态 */
    private final RefundService refundService;

    @PostMapping("/create")
    @Operation(summary = "创建普通订单")
    public Result<Order> createOrder(@RequestHeader("Authorization") String token,
                                     @RequestBody @Valid CreateOrderDto createOrderDto) {
        Long userId = getUserIdFromToken(token);
        log.info("创建普通订单请求，userId={}", userId);

            if (createOrderDto.getAddressId() == null) {
                Address address = addressService.getDefaultAddress(userId);
                if (address == null || address.getId() == null) {
                    throw new BusinessException("请先设置收货地址");
                }
                createOrderDto.setAddressId(address.getId());
            }

            Order order = orderService.createOrder(userId, createOrderDto);

            try {
                List<Long> orderedProductIds = createOrderDto.getItems().stream()
                        .map(CreateOrderDto.OrderItemDto::getProductId)
                        .collect(Collectors.toList());
                cartService.removeProductsFromCart(userId, orderedProductIds);
            } catch (Exception e) {
                log.error("创建订单成功，但清理购物车失败，userId={}, orderId={}",
                        userId, order.getId(), e);
            }

            return Result.success(order);

    }

    @PostMapping("/cancel/{orderId}")
    @Operation(summary = "取消订单")
    public Result<Void> cancelOrder(@RequestHeader("Authorization") String token,
                                    @PathVariable Long orderId) {
        Long userId = getUserIdFromToken(token);
        log.info("取消订单请求，userId={}, orderId={}", userId, orderId);

        try {
            orderService.cancelOrder(userId, orderId);
            return Result.success("取消成功");
        } catch (BusinessException e) {
            log.warn("取消失败，userId={}, orderId={}, msg={}", userId, orderId, e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("取消异常，userId={}, orderId={}", userId, orderId, e);
            return Result.error("取消失败，请稍后重试");
        }
    }

    @PostMapping("/refund/{orderId}")
    @Operation(summary = "申请退款 / 申请售后（提交申请，等待审核）")
    public Result<Long> applyRefund(@RequestHeader("Authorization") String token,
                                    @PathVariable Long orderId,
                                    @RequestBody(required = false) @Valid ApplyRefundDto dto) {
        Long userId = getUserIdFromToken(token);

        // 路径参数优先级高于 body：以 URL 上的 orderId 为准，避免两者不一致时出现歧义
        if (dto == null) {
            dto = new ApplyRefundDto();
        }
        dto.setOrderId(orderId);

        log.info("申请退款/售后请求，userId={}, orderId={}", userId, orderId);

        Long refundId = refundService.apply(userId, dto);
        return Result.success("申请已提交，请等待审核", refundId);
    }

    @PostMapping("/confirm/{orderId}")
    @Operation(summary = "确认收货")
    public Result<Void> confirmReceipt(@RequestHeader("Authorization") String token,
                                       @PathVariable Long orderId) {
        Long userId = getUserIdFromToken(token);
        log.info("确认收货请求，userId={}, orderId={}", userId, orderId);

            orderService.confirmReceipt(userId, orderId);
            return Result.success("确认收货成功");

    }

    @GetMapping("/detail/{orderId}")
    @Operation(summary = "获取订单详情")
    public Result<OrderVo> getOrderDetail(@RequestHeader("Authorization") String token,
                                          @PathVariable Long orderId) {
        Long userId = getUserIdFromToken(token);
        log.info("获取订单详情请求，userId={}, orderId={}", userId, orderId);

            OrderVo orderVo = orderService.getOrderDetail(userId, orderId);
            return Result.success(orderVo);

    }

    @PostMapping("/list")
    @Operation(summary = "获取我的订单列表")
    public Result<PageVo<OrderVo>> getMyOrders(@RequestHeader("Authorization") String token,
                                               @RequestBody @Valid OrderQueryDto queryDto) {
        Long userId = getUserIdFromToken(token);
        log.info("获取我的订单列表请求，userId={}, pageNum={}, pageSize={}",
                userId, queryDto.getPageNum(), queryDto.getPageSize());

            PageVo<OrderVo> page = orderService.getUserOrders(userId, queryDto);
            return Result.success(page);

    }

    // 管理员：更新订单（发货 / 改物流信息）
    @PreAuthorize("hasAuthority('order:ship')")
    @PutMapping("/admin/update")
    @Operation(summary = "管理员更新订单（发货等）")
    public Result<Void> adminUpdateOrder(@RequestHeader("Authorization") String token,
                                         @Valid @RequestBody UpdateOrderDto updateOrderDto) {
        log.info("管理员更新订单请求，orderId={}", updateOrderDto.getId());

        try {
            Long operatorId = getUserIdFromToken(token);
            // 注意：这里必须先用 removeTokenPrefix 剥掉 "Bearer " 前缀。
            String operatorName = jwtUtil.getUsernameFromToken(jwtUtil.removeTokenPrefix(token));

            orderService.adminUpdateOrder(updateOrderDto, operatorId, operatorName);
            return Result.success("更新成功");
        } catch (BusinessException e) {
            log.warn("管理员更新订单失败，orderId={}, msg={}",
                    updateOrderDto.getId(), e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("管理员更新订单异常，orderId={}", updateOrderDto.getId(), e);
            return Result.error("更新失败，请稍后重试");
        }
    }

    // 管理员：根据条件查询订单列表
    @PreAuthorize("hasAuthority('order:read')")
    @PostMapping("/admin/list")
    @Operation(summary = "管理员查询订单列表")
    public Result<PageVo<OrderVo>> adminListOrders(@RequestBody @Valid OrderQueryDto queryDto) {
        log.info("管理员查询订单列表请求，pageNum={}, pageSize={}",
                queryDto.getPageNum(), queryDto.getPageSize());

            PageVo<OrderVo> page = orderService.adminListOrders(queryDto);
            return Result.success(page);

    }
}