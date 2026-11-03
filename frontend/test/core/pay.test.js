// 支付链路 · 收款（下单 → prepay 拿收银台地址 → 跳转第三方收银台）
//
// 关键语义：新版后端把「支付」拆成 prepay + 第三方回调，前端点支付只是去收银台，
// 不能直接提示"支付成功"（钱还没收到）。这里把这条语义固化成测试。
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ElMessage } from 'element-plus'
import { mountView, seedLogin, waitFor, stubLocation } from '../helpers.js'
import { orders } from '../fixtures.js'
import OrderList from '@/views/mall/OrderList.vue'
import OrderDetail from '@/views/mall/OrderDetail.vue'

// 拦截 axios 层，直接断言前端发出的请求与跳转
describe('支付链路 · 收款', () => {
  let loc
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
    loc = stubLocation('/orders')
  })

  it('订单列表点「支付」→ 调 prepay 并跳转到后端返回的收银台地址', async () => {
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    const waitPay = orders.find(o => o.status === 0) || orders[0]
    await wrapper.vm.handlePay(waitPay)

    await waitFor(() => expect(loc.href).not.toBe(''))
    // MSW 的 prepay 返回 fixtures 里的 cashierUrl（收银台地址，带 out_trade_no）
    expect(loc.href).toContain('cashier')
    expect(loc.href).toContain('out_trade_no=')
  })

  it('支付跳转过程不直接提示「支付成功」（钱由第三方回调入账）', async () => {
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    await wrapper.vm.handlePay(orders.find(o => o.status === 0) || orders[0])
    await waitFor(() => expect(loc.href).not.toBe(''))

    const successCalls = ElMessage.success.mock.calls.map(c => String(c[0]))
    expect(successCalls.some(m => m.includes('支付成功'))).toBe(false)
  })

  it('订单详情点「支付」→ 同样走 prepay 跳收银台', async () => {
    const { wrapper } = await mountView(OrderDetail, { path: '/order/1001' })
    await waitFor(() => expect(wrapper.vm.order).toBeTruthy())

    await wrapper.vm.handlePay()
    await waitFor(() => expect(loc.href).not.toBe(''))
    expect(loc.href).toContain('cashier')
  })
})
