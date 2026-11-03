// 支付链路 · 退款（用户申请/撤销 + 管理端审核/确认退货/重试/作废）
//
// 关键语义：新版后端的「退款」是【申请 → 待审核 → 审核 → 退款中 → 回调入账】的状态机，
// 前端点退款只是提交申请，不能提示"退款成功"。管理端的重试/作废只对「退款失败」开放。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import { orders } from '../fixtures.js'
import OrderList from '@/views/mall/OrderList.vue'
import RefundManage from '@/views/admin/RefundManage.vue'

const refundRow = (over = {}) => ({
  id: 1001, refundNo: 'REF2026101400001', orderNo: 'ORD2026101400001',
  refundAmount: 199.00, refundType: 1, refundTypeDesc: '仅退款',
  status: 0, statusDesc: '待审核', reason: '拍错了',
  applyTime: '2026-10-14 19:00:00', canConfirmReturn: false, canRetry: false, canClose: false,
  ...over,
})

describe('退款链路 · 用户侧', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
  })

  it('点「退款」→ 弹框收集原因 → 提交申请并提示等待审核（不是提示退款成功）', async () => {
    ElMessageBox.prompt.mockResolvedValue({ value: '尺码不合适', action: 'confirm' })

    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    const paid = orders.find(o => o.status === 1) || orders[0]
    await wrapper.vm.handleRefund(paid)

    await waitFor(() => {
      expect(ElMessage.success).toHaveBeenCalledWith('退款申请已提交，请等待审核')
    })
    // 不能出现"退款成功"这类误导性文案
    const msgs = ElMessage.success.mock.calls.map(c => String(c[0]))
    expect(msgs.some(m => m.includes('退款成功'))).toBe(false)
  })

  it('用户在弹框里取消 → 不发起退款请求', async () => {
    ElMessageBox.prompt.mockRejectedValue(new Error('cancel'))

    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    await wrapper.vm.handleRefund(orders.find(o => o.status === 1) || orders[0])

    expect(ElMessage.success).not.toHaveBeenCalledWith('退款申请已提交，请等待审核')
  })
})

describe('退款链路 · 管理端', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin({ admin: true })
  })

  async function mountAdmin() {
    const ctx = await mountView(RefundManage, { path: '/admin/refunds' })
    await waitFor(() => expect(ctx.wrapper.vm.loading).toBe(false))
    return ctx
  }

  it('加载退款单列表', async () => {
    const { wrapper } = await mountAdmin()
    expect(Array.isArray(wrapper.vm.list)).toBe(true)
  })

  it('审核通过 → 调 adminAudit（approved=true + 备注）', async () => {
    const { wrapper } = await mountAdmin()
    wrapper.vm.openAudit(refundRow())
    expect(wrapper.vm.auditVisible).toBe(true)

    wrapper.vm.auditForm.approved = true
    wrapper.vm.auditForm.remark = '同意退款'
    await wrapper.vm.submitAudit()

    expect(wrapper.vm.auditVisible).toBe(false)
    expect(ElMessage.success).toHaveBeenCalledWith('已通过退款审核')
  })

  it('审核驳回 → 提示已驳回', async () => {
    const { wrapper } = await mountAdmin()
    wrapper.vm.openAudit(refundRow())
    wrapper.vm.auditForm.approved = false
    await wrapper.vm.submitAudit()

    expect(ElMessage.success).toHaveBeenCalledWith('已驳回退款申请')
  })

  it('售后「确认收到退货」→ 调 adminConfirmReturn', async () => {
    const { wrapper } = await mountAdmin()
    await wrapper.vm.handleConfirmReturn(refundRow({ status: 7, canConfirmReturn: true }))
    expect(ElMessage.success).toHaveBeenCalledWith('已确认收到退货，退款已发起')
  })

  it('退款失败单可重试 → 调 adminRetry', async () => {
    const { wrapper } = await mountAdmin()
    await wrapper.vm.handleRetry(refundRow({ status: 3, canRetry: true }))
    expect(ElMessage.success).toHaveBeenCalledWith('已重新发起退款')
  })

  it('退款失败单可作废 → 收集原因并调 adminClose', async () => {
    ElMessageBox.prompt.mockResolvedValue({ value: '渠道确认无法退回', action: 'confirm' })
    const { wrapper } = await mountAdmin()
    await wrapper.vm.handleClose(refundRow({ status: 3, canClose: true }))
    expect(ElMessage.success).toHaveBeenCalledWith('退款单已作废')
  })

  it('作废时取消填写原因 → 不发起作废请求', async () => {
    ElMessageBox.prompt.mockRejectedValue(new Error('cancel'))
    const { wrapper } = await mountAdmin()
    await wrapper.vm.handleClose(refundRow({ status: 3, canClose: true }))
    expect(ElMessage.success).not.toHaveBeenCalledWith('退款单已作废')
  })

  it('状态展示映射：0→待审核 / 2→退款成功 / 3→退款失败 / 7→待用户退货', async () => {
    const { wrapper } = await mountAdmin()
    expect(wrapper.vm.statusMap[0].label).toBe('待审核')
    expect(wrapper.vm.statusMap[2].label).toBe('退款成功')
    expect(wrapper.vm.statusMap[3].label).toBe('退款失败')
    expect(wrapper.vm.statusMap[7].label).toBe('待用户退货')
  })
})
