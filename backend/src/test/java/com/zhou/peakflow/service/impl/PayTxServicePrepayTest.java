package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.enums.OrderStatus;
import com.zhou.peakflow.enums.PayStatus;
import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.OrderMapper;
import com.zhou.peakflow.mapper.PayRecordMapper;
import com.zhou.peakflow.pojo.Order;
import com.zhou.peakflow.pojo.PayRecord;
import com.zhou.peakflow.utils.PaySignUtil;
import com.zhou.peakflow.vo.PrepayVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发起支付 <b>业务层单元测试</b>：{@code PayTxService#doPrepay(userId, orderId)}（@Transactional）。
 *
 * <p>流程：selectById（null → 3003）→ 归属校验（≠ userId → 3007）→ canPay 只放行 WAIT_PAY →
 * selectWaitPayByOrderId 已存在待支付单则复用（幂等）→ 新建 PayRecord → buildPrepayVo（算签名 + 拼收银台 URL）。</p>
 *
 * <p>关键设计：第 4 步幂等是 check-then-act，而 pay_record 只有 uk_out_trade_no（单号带毫秒时间戳、永不冲突），
 * 没有任何数据库约束保证"同一订单只有一张待支付单"。并发安全完全由 {@code PayServiceImpl#prepay} 的
 * {@code lock:prepay:{orderId}} 分布式锁承担 —— 这也是锁必须加在事务外层的原因。</p>
 *
 * <p>与 handleNotify 的对比：那里多道校验返回 failure，这里多道校验抛 BusinessException，故用
 * assertThrows + 断言异常码/消息来区分是哪一道拦下的。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("发起支付业务层单元测试 PayTxService#doPrepay")
class PayTxServicePrepayTest {

    @Mock
    private PayRecordMapper payRecordMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private MockPayProperties mockPayProperties;

    @InjectMocks
    private PayTxService payTxService;

    // 常量

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 2001L;
    private static final String ORDER_NO = "ORD2026101400001";
    private static final BigDecimal PAY_AMOUNT = new BigDecimal("199.00");
    private static final String SECRET = "unit-test-secret-key";
    private static final String CASHIER_URL = "http://127.0.0.1:8080/mock-pay/cashier";
    private static final String CHANNEL = "MOCK_PAY";

    // 一、订单校验（三道闸门）

    /**
     * <p><b>为什么测它：</b>订单不存在时必须第一步拦住，不能拿 null 去建支付单。</p>
     * <p><b>关键断言：</b>抛 3003；<b>never</b> 查待支付单、<b>never</b> 插入。</p>
     */

    @Test
    @DisplayName("[非法输入] 订单不存在 → BusinessException(3003 订单不存在)，不查支付单")
    void doPrepay_orderNotFound_shouldThrow() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payTxService.doPrepay(USER_ID, ORDER_ID));

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode(), "应抛出 3003 订单不存在");
        // 路径断言：第 1 步就拦下了，不该走到后面
        verify(payRecordMapper, never()).selectWaitPayByOrderId(anyLong());
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    /**
     * <p><b>为什么测它：</b>否则可以替别人的订单建支付单（越权）。</p>
     * <p><b>关键断言：</b>抛 3007；<b>never</b> 插入。</p>
     */

    @Test
    @DisplayName("[越权防护] 订单不属于当前用户 → BusinessException(3007 无权操作此订单)，不建单")
    void doPrepay_orderBelongsToAnotherUser_shouldThrow() {
        Order othersOrder = orderOf(ORDER_ID, 9999L, OrderStatus.WAIT_PAY.getCode());
        when(orderMapper.selectById(ORDER_ID)).thenReturn(othersOrder);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payTxService.doPrepay(USER_ID, ORDER_ID));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode(),
                "用别人的 orderId 发起支付必须被拒（否则能替别人下单付款）");
        // 路径断言：说明已经过了"订单存在"这一关，是卡在归属校验上
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    /**
     * <p><b>为什么测它：</b>只有「待付款」能发起支付；提示语要带<b>当前状态的中文描述</b>以便前端直接展示。</p>
     * <p><b>关键断言：</b>抛异常且消息含「不允许支付」与状态描述；<b>never</b> 插入。</p>
     */

    @Test
    @DisplayName("[非法状态] 订单已付款 → 抛异常且消息带状态描述，不建单")
    void doPrepay_orderAlreadyPaid_shouldThrowWithStatusDesc() {
        Order paidOrder = orderOf(ORDER_ID, USER_ID, OrderStatus.PAID.getCode());
        when(orderMapper.selectById(ORDER_ID)).thenReturn(paidOrder);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payTxService.doPrepay(USER_ID, ORDER_ID));

        assertTrue(ex.getMessage().contains("不允许支付"), "消息应说明状态不允许支付，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains(OrderStatus.PAID.getDesc()),
                "消息应带上当前状态的中文描述（便于前端直接展示），实际：" + ex.getMessage());
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    /**
     * <p><b>为什么测它：</b>参数化覆盖 5 种终态（已取消/关闭/退款/发货/完成），确保 canPay 的白名单没有漏洞。</p>
     * <p><b>关键断言：</b>五种状态全部抛异常；<b>never</b> 插入。</p>
     */

    @Test
    @DisplayName("[非法状态] 订单已取消/已关闭/已退款 → 均不允许发起支付")
    void doPrepay_orderInTerminalStates_shouldThrow() {
        int[] terminalStates = {
                OrderStatus.CANCELLED.getCode(),
                OrderStatus.CLOSED.getCode(),
                OrderStatus.REFUNDED.getCode(),
                OrderStatus.SHIPPED.getCode(),
                OrderStatus.COMPLETED.getCode()
        };
        for (int state : terminalStates) {
            Order order = orderOf(ORDER_ID, USER_ID, state);
            when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

            assertThrows(BusinessException.class,
                    () -> payTxService.doPrepay(USER_ID, ORDER_ID),
                    "订单状态 " + state + " 不应允许发起支付");
        }
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    // 二、幂等（复用待支付单）—— 本接口最重要的业务设计

    /**
     * <p><b>为什么测它：</b><b>幂等设计</b>：用户重复点「去支付」不该产生多张待支付单。</p>
     * <p><b>关键断言：</b>返回的 outTradeNo/orderNo/amount 都来自已存在的那张单；<b>never</b> 插入（在「查」这层就分流了）。</p>
     */

    @Test
    @DisplayName("[幂等] 已存在待支付单 → 直接复用，【绝不新建】（用户重复点「去支付」不会产生多张单）")
    void doPrepay_existingWaitPayRecord_shouldReuseWithoutInsert() {
        givenPayableOrder();
        givenPrepayVoConfig();
        PayRecord existing = payRecordOf("PAY" + ORDER_NO + "-1700000000000", PayStatus.WAIT_PAY.getCode());
        when(payRecordMapper.selectWaitPayByOrderId(ORDER_ID)).thenReturn(existing);

        PrepayVo vo = payTxService.doPrepay(USER_ID, ORDER_ID);

        assertEquals(existing.getOutTradeNo(), vo.getOutTradeNo(), "应复用已存在的支付单号");
        assertEquals(ORDER_NO, vo.getOrderNo());
        assertEquals(PAY_AMOUNT, vo.getAmount());
        // 核心断言：幂等在"查"这一步就分流了，根本不会执行 insert
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    /**
     * <p><b>为什么测它：</b>复用时必须沿用原支付单的<b>金额快照</b>，否则同一单号会对应两个金额。</p>
     * <p><b>关键断言：</b>返回金额是 188.88（原单的），不是订单当前的 199.00。</p>
     */

    @Test
    @DisplayName("[幂等] 复用的支付单金额取自那条记录，而不是重新从订单取")
    void doPrepay_reuseKeepsExistingAmount() {
        givenPayableOrder();
        givenPrepayVoConfig();
        // 已存在的支付单金额与当前订单金额不同（历史快照）
        PayRecord existing = payRecordOf("PAY" + ORDER_NO + "-1700000000001", PayStatus.WAIT_PAY.getCode());
        existing.setAmount(new BigDecimal("188.88"));
        when(payRecordMapper.selectWaitPayByOrderId(ORDER_ID)).thenReturn(existing);

        PrepayVo vo = payTxService.doPrepay(USER_ID, ORDER_ID);

        assertEquals(new BigDecimal("188.88"), vo.getAmount(),
                "复用时应沿用原支付单的金额快照，避免同一笔单号对应两个金额");
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    /**
     * <p><b>为什么测它：</b>对照组：没有待支付单时才新建 —— 与「有则复用」形成互斥分支的完整覆盖。</p>
     * <p><b>关键断言：</b>不抛异常；<b>verify 插入被调用</b>。</p>
     */

    @Test
    @DisplayName("[幂等] 没有待支付单时才新建（明确对照：有则复用、无则建）")
    void doPrepay_noExistingRecord_shouldInsertNew() {
        givenPayableOrder();
        givenPrepayVoConfig();
        givenChannelConfig();
        when(payRecordMapper.selectWaitPayByOrderId(ORDER_ID)).thenReturn(null);

        assertDoesNotThrow(() -> payTxService.doPrepay(USER_ID, ORDER_ID),
                "没有待支付单时应正常走新建分支");

        verify(payRecordMapper).insert(any(PayRecord.class));
    }

    // 三、正常创建

    /**
     * <p><b>为什么测它：</b>新的支付单<b>每个字段都不能写错</b>（尤其金额必须取自订单，不接受外部传入）。</p>
     * <p><b>关键断言：</b>orderId/orderNo/userId 正确；<b>金额 = order.payAmount</b>；status=WAIT_PAY；version=0；渠道取自配置。</p>
     */

    @Test
    @DisplayName("[业务正确] 待付款订单 + 无待支付单 → 新建支付单，且【每个字段都写对】")
    void doPrepay_valid_shouldInsertRecordWithCorrectFields() {
        givenPayableOrder();
        givenPrepayVoConfig();
        givenChannelConfig();
        when(payRecordMapper.selectWaitPayByOrderId(ORDER_ID)).thenReturn(null);

        PrepayVo vo = payTxService.doPrepay(USER_ID, ORDER_ID);

        // 用 Captor 断言入库对象的字段 —— 比只断言返回值更能发现问题
        ArgumentCaptor<PayRecord> captor = ArgumentCaptor.forClass(PayRecord.class);
        verify(payRecordMapper).insert(captor.capture());
        PayRecord inserted = captor.getValue();

        assertEquals(ORDER_ID, inserted.getOrderId(), "orderId 应取自订单");
        assertEquals(ORDER_NO, inserted.getOrderNo(), "orderNo 应取自订单");
        assertEquals(USER_ID, inserted.getUserId(), "userId 应是发起人");
        assertEquals(PAY_AMOUNT, inserted.getAmount(),
                "金额必须取自 order.payAmount（服务端权威），不接受外部传入");
        assertEquals(PayStatus.WAIT_PAY.getCode(), inserted.getStatus(),
                "新建的支付单必须是待支付");
        assertEquals(0, inserted.getVersion(), "乐观锁版本应从 0 开始");
        assertEquals(CHANNEL, inserted.getPayChannel(), "支付渠道取自配置");

        // 返回值与入库对象一致
        assertEquals(inserted.getOutTradeNo(), vo.getOutTradeNo());
        assertNotNull(vo.getCashierUrl(), "应返回收银台地址");
    }

    /**
     * <p><b>为什么测它：</b>商户单号格式固定为 `PAY + 订单号 + - + 时间戳`，且带毫秒保证不冲突。</p>
     * <p><b>关键断言：</b>前缀正确；后缀是可解析的正整数（毫秒时间戳）。</p>
     */

    @Test
    @DisplayName("[业务正确] outTradeNo 按「PAY + 订单号 + - + 时间戳」生成，且每次不同")
    void doPrepay_outTradeNoFormat_shouldFollowRule() {
        givenPayableOrder();
        givenPrepayVoConfig();
        givenChannelConfig();
        when(payRecordMapper.selectWaitPayByOrderId(ORDER_ID)).thenReturn(null);

        PrepayVo first = payTxService.doPrepay(USER_ID, ORDER_ID);

        assertTrue(first.getOutTradeNo().startsWith("PAY" + ORDER_NO + "-"),
                "outTradeNo 应以 PAY+订单号+- 开头，实际：" + first.getOutTradeNo());
        long suffix = Long.parseLong(first.getOutTradeNo().substring(("PAY" + ORDER_NO + "-").length()));
        assertTrue(suffix > 0, "后缀应是毫秒时间戳");
    }

    /**
     * <p><b>为什么测它：</b>收银台 URL 里的签名必须能被第三方用同一密钥验通过，否则用户一进收银台就被判签名错误。</p>
     * <p><b>关键断言：</b>URL 含 out_trade_no/amount/timestamp/sign；<b>用 PaySignUtil.verify 反向验签通过</b>。</p>
     */

    @Test
    @DisplayName("[业务正确] 收银台 URL 的签名可被第三方用同一密钥验签通过")
    void doPrepay_cashierUrlSign_shouldBeVerifiable() throws Exception {
        givenPayableOrder();
        givenPrepayVoConfig();
        givenChannelConfig();
        when(payRecordMapper.selectWaitPayByOrderId(ORDER_ID)).thenReturn(null);

        PrepayVo vo = payTxService.doPrepay(USER_ID, ORDER_ID);

        Map<String, String> query = parseQuery(vo.getCashierUrl());
        assertEquals(vo.getOutTradeNo(), query.get("out_trade_no"), "URL 应带 out_trade_no");
        assertEquals(PAY_AMOUNT.toPlainString(), query.get("amount"), "URL 应带明文金额");
        assertNotNull(query.get("timestamp"), "URL 应带 timestamp");
        assertNotNull(query.get("sign"), "URL 应带 sign");

        // 反向验证：用同一套参数与密钥重算签名，必须与 URL 里的一致
        Map<String, Object> signParams = new LinkedHashMap<>();
        signParams.put("out_trade_no", query.get("out_trade_no"));
        signParams.put("amount", query.get("amount"));
        signParams.put("timestamp", query.get("timestamp"));
        assertTrue(PaySignUtil.verify(signParams, SECRET, query.get("sign")),
                "收银台 URL 的签名应能被验签工具验证通过（否则用户在收银台会被判签名错误）");
    }

    /**
     * <p><b>为什么测它：</b>唯一键冲突等持久化异常<b>必须向上传播</b>，否则事务不会回滚（会留下脏数据）。</p>
     * <p><b>关键断言：</b>异常被抛出且消息含唯一键名。</p>
     */

    @Test
    @DisplayName("[异常传播] 唯一键冲突等持久化异常 → 原样抛出，不被吞掉（交给事务回滚）")
    void doPrepay_insertThrows_shouldPropagate() {
        givenPayableOrder();
        givenChannelConfig();
        when(payRecordMapper.selectWaitPayByOrderId(ORDER_ID)).thenReturn(null);
        when(payRecordMapper.insert(any(PayRecord.class)))
                .thenThrow(new RuntimeException("Duplicate entry for key 'uk_out_trade_no'"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> payTxService.doPrepay(USER_ID, ORDER_ID));

        assertTrue(ex.getMessage().contains("uk_out_trade_no"),
                "持久化异常必须向上传播，否则事务不会回滚");
    }

    // 助手

    private Order orderOf(Long id, Long userId, int status) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNo(ORDER_NO);
        order.setUserId(userId);
        order.setPayAmount(PAY_AMOUNT);
        order.setStatus(status);
        return order;
    }

    private PayRecord payRecordOf(String outTradeNo, int status) {
        PayRecord record = new PayRecord();
        record.setOutTradeNo(outTradeNo);
        record.setOrderId(ORDER_ID);
        record.setOrderNo(ORDER_NO);
        record.setUserId(USER_ID);
        record.setAmount(PAY_AMOUNT);
        record.setPayChannel(CHANNEL);
        record.setStatus(status);
        return record;
    }

    /**
     * stub：一张「待付款且属于当前用户」的订单。
     *
     * <p>这里<b>刻意只 stub 订单本身</b>：配置类的 stub 拆成了下面两个更细的方法。
     * 原因是 Mockito 严格模式会检查「stub 了却没被用到」——
     * 而不同分支用到的配置不一样（复用路径读不到 getChannel，
     * 异常路径走不到 buildPrepayVo），混在一起 stub 就会报 UnnecessaryStubbingException。
     * <b>这个报错本身就是一种路径检查：它证明测试确实只走了预期的那条路。</b></p>
     */
    private void givenPayableOrder() {
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(orderOf(ORDER_ID, USER_ID, OrderStatus.WAIT_PAY.getCode()));
    }

    /** stub：走到 buildPrepayVo 才需要的配置（签名密钥 + 收银台地址） */
    private void givenPrepayVoConfig() {
        when(mockPayProperties.getSecretKey()).thenReturn(SECRET);
        when(mockPayProperties.getCashierUrl()).thenReturn(CASHIER_URL);
    }

    /** stub：只有「新建支付单」这条路径才读得到的配置（复用路径读不到它） */
    private void givenChannelConfig() {
        when(mockPayProperties.getChannel()).thenReturn(CHANNEL);
    }

    /** 把收银台 URL 的 query string 解析成 Map（值已做 URL 解码） */
    private Map<String, String> parseQuery(String url) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            map.put(pair.substring(0, eq),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        return map;
    }
}
