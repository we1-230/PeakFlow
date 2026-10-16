package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.RefundRecordMapper;
import com.zhou.peakflow.pojo.RefundRecord;
import com.zhou.peakflow.vo.RefundVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端退款单详情 <b>单元测试</b>：{@code RefundServiceImpl#adminDetail(refundId)}。
 *
 * <p>管理端「退款审核工作台」的详情按钮曾误调用户侧 {@code GET /refund/detail/{id}}，而用户侧服务层
 * 写死"只能看自己的退款单"，管理员必然被拒、弹窗打不开（报 3036）。修复方式是新增 adminDetail 接口。</p>
 *
 * <p>本类守护两件事：① adminDetail 不做归属校验（退款单属于别的用户时仍正常返回 VO，一旦被改回
 * "顺手加个归属判断"，缺陷就会复发）；② 用户侧 detail 的归属校验仍生效，作为对照证明"放开管理员"
 * 没有把用户侧的越权防护也一起放开。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("管理端退款详情单元测试 RefundServiceImpl#adminDetail")
class RefundServiceImplAdminDetailTest {

    @Mock
    private RefundRecordMapper refundRecordMapper;

    @InjectMocks
    private RefundServiceImpl refundService;

    // 常量

    private static final Long REFUND_ID = 7001L;
    /** 退款单的归属用户：故意用一个「并非管理员本人」的 id */
    private static final Long OWNER_USER_ID = 12L;
    private static final Long OTHER_USER_ID = 99L;
    private static final String REFUND_NO = "REFORD2026101500001-1792070000000";

    /** 造一张"别人名下"的退款单：归属 OWNER_USER_ID，而调用方是管理员（无 userId 概念） */
    private RefundRecord refundOfOtherUser() {
        RefundRecord r = new RefundRecord();
        r.setId(REFUND_ID);
        r.setRefundNo(REFUND_NO);
        r.setOrderId(2001L);
        r.setOrderNo("ORD2026101500001");
        r.setUserId(OWNER_USER_ID);
        r.setRefundAmount(new BigDecimal("199.00"));
        r.setRefundType(1);      // 仅退款
        r.setStatus(0);          // 待审核
        return r;
    }

    // 一、业务正确：管理员可看任意退款单

    /**
     * <p><b>为什么测它：</b>这是 F11 缺陷的回归守卫 —— 管理端详情若重新引入「只能看自己的」校验，
     * 管理员点详情会再次 3036 打不开，本用例会立刻变红。</p>
     * <p><b>关键断言：</b>① 不抛异常；② 返回的 VO 归属是「别人」的用户 id（说明没有被归属校验拦下）；
     * ③ 关键字段（退款单号/金额）经 toVo 正确映射。</p>
     */
    @Test
    @DisplayName("[业务正确] 管理员查「他人名下」的退款单 → 正常返回 VO（不校验归属）")
    void adminDetail_otherUserRefund_shouldReturnVoWithoutOwnershipCheck() {
        when(refundRecordMapper.selectById(REFUND_ID)).thenReturn(refundOfOtherUser());

        RefundVo vo = assertDoesNotThrow(() -> refundService.adminDetail(REFUND_ID));

        assertEquals(REFUND_ID, vo.getId(), "应返回目标退款单");
        assertEquals(REFUND_NO, vo.getRefundNo(), "退款单号应经 toVo 正确映射");
        assertEquals(0, new BigDecimal("199.00").compareTo(vo.getRefundAmount()), "退款金额应一致");
        // 路径断言：状态/类型文案只有 toVo 里才会填充 —— 非空即证明"归属校验没把它拦下"
        assertEquals("待审核", vo.getStatusDesc(), "statusDesc 由 toVo 计算，非空说明走到了映射阶段");
        assertEquals("仅退款", vo.getRefundTypeDesc(), "refundTypeDesc 同样由 toVo 计算");

        // 路径断言：确实只按主键查了一次
        verify(refundRecordMapper).selectById(REFUND_ID);
    }

    // 二、非法输入：退款单不存在

    /**
     * <p><b>为什么测它：</b>详情接口对不存在的 id 必须给出明确业务码（3030），
     * 而不是 NPE 或 5000 系统错误，否则前端无法区分「单子没了」和「服务坏了」。</p>
     * <p><b>关键断言：</b>异常码为 3030 REFUND_NOT_FOUND。</p>
     */
    @Test
    @DisplayName("[非法输入] 退款单不存在 → 3030 退款单不存在")
    void adminDetail_refundNotFound_shouldThrowRefundNotFound() {
        when(refundRecordMapper.selectById(REFUND_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.adminDetail(REFUND_ID));

        assertEquals(ResultCode.REFUND_NOT_FOUND.getCode(), ex.getCode(),
                "应返回专用业务码 3030，而不是兜底系统错误");
    }

    // 三、对照守卫：用户侧 detail 的「只能看自己的」不能被顺手放开

    /**
     * <p><b>为什么测它：</b>修复 F11 时新增了 adminDetail，最危险的做法是「直接把用户侧 detail 的
     * 归属校验删掉」——那会让任何登录用户都能查别人的退款单（含金额、退货物流、审核备注）。</p>
     * <p><b>关键断言：</b>用户侧 detail 传「别人的 userId」→ 3036 REFUND_NO_PERMISSION，
     * 并且<b>没有走到</b> toVo（用「是否继续查库」无法观测，这里以异常码 + 不返回 VO 作为路径证据）。</p>
     */
    @Test
    @DisplayName("[攻击·越权] 用户侧 detail 传他人 userId → 3036（对照守卫仍在）")
    void userDetail_otherUsersRefund_shouldBeRejected() {
        when(refundRecordMapper.selectById(REFUND_ID)).thenReturn(refundOfOtherUser());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.detail(OTHER_USER_ID, REFUND_ID));

        assertEquals(ResultCode.REFUND_NO_PERMISSION.getCode(), ex.getCode(),
                "用户查别人的退款单必须 3036");
        verify(refundRecordMapper, never()).selectById(OTHER_USER_ID);
    }
}
