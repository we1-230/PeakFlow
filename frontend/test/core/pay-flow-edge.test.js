// 支付流程 · 异常与边界（正常路径见 pay.test.js / cashier-page.test.js）
//
// 重点守护三类线上踩过的坑：
//   1. prepay 失败时**绝不能跳转**（否则用户被带到空白/错误页，订单还在待付款）
//   2. 失败提示必须能区分「网络错误 / 接口错误 / 权限错误」，不能一律「网络异常」
//   3. 收银台地址必须原样使用后端返回值（含签名参数），前端不能自己拼路径 ——
//      这正是生产 405 事故的成因（前端自己拼了根相对路径）
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { http, HttpResponse } from 'msw'
import { server } from '../setup.js'
import { mountView, seedLogin, waitFor, stubLocation } from '../helpers.js'
import { orders, prepayVo } from '../fixtures.js'
import OrderList from '@/views/mall/OrderList.vue'
import OrderDetail from '@/views/mall/OrderDetail.vue'

const waitPayOrder = () => orders.find(o => o.status === 0) || orders[0]

const overridePrepay = (resolver) => server.use(http.post('/api/pay/prepay', resolver))

const jsonBiz = (code, message) => HttpResponse.json(
  { code, message, data: null, timestamp: Date.now(), error: true, success: false },
  { status: 200, headers: { 'Content-Type': 'application/json' } },
)

describe('支付 · prepay 失败时不得跳转', () => {
  let loc
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
    loc = stubLocation('/orders')
    vi.clearAllMocks()
  })

  it('业务错误（订单状态不允许支付）→ 保持当前页并展示后端文案', async () => {
    overridePrepay(() => jsonBiz(3010, '订单状态不允许支付'))
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    await wrapper.vm.handlePay(waitPayOrder())

    // 关键：location 未被赋值成收银台（仍停在原页面）
    expect(loc.href).not.toContain('cashier')
    const msgs = ElMessage.error.mock.calls.map(c => String(c[0]))
    expect(msgs.some(m => m.includes('订单状态不允许支付'))).toBe(true)
  })

  it('网络层失败（无响应）→ 提示「网络连接失败」，而不是笼统的「网络异常」', async () => {
    overridePrepay(() => HttpResponse.error())
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    await wrapper.vm.handlePay(waitPayOrder())

    expect(loc.href).not.toContain('cashier')
    const msgs = ElMessage.error.mock.calls.map(c => String(c[0]))
    expect(msgs.some(m => m.includes('网络连接失败'))).toBe(true)
  })

  it('服务端 500 → 提示「稍后重试」，不把堆栈细节抛给用户', async () => {
    overridePrepay(() => HttpResponse.json(
      { message: 'NullPointerException at PayServiceImpl.java:233' },
      { status: 500 },
    ))
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    await wrapper.vm.handlePay(waitPayOrder())

    const msgs = ElMessage.error.mock.calls.map(c => String(c[0]))
    expect(msgs.some(m => m.includes('稍后重试'))).toBe(true)
    expect(msgs.some(m => m.includes('NullPointerException'))).toBe(false)
  })

  it('权限失败（HTTP 401）→ 清登录态并跳登录，不弹错误气泡', async () => {
    localStorage.setItem('mall_jwt', 'stale')
    overridePrepay(() => HttpResponse.json({ message: 'Unauthorized' }, { status: 401 }))
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    await wrapper.vm.handlePay(waitPayOrder())

    await waitFor(() => expect(localStorage.getItem('mall_jwt')).toBeNull())
    expect(loc.href).toContain('/login')
    expect(ElMessage.error).not.toHaveBeenCalled()
  })
})

describe('支付 · 收银台地址必须原样使用后端返回', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
    vi.clearAllMocks()
  })

  it('跳转地址与后端返回的 cashierUrl 完全一致（不裁剪、不重拼、不改协议）', async () => {
    const loc = stubLocation('/orders')
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    await wrapper.vm.handlePay(waitPayOrder())
    await waitFor(() => expect(loc.href).not.toBe(''))

    expect(loc.href).toBe(prepayVo.cashierUrl)
    // 必须带签名与商户订单号，否则收银台无法验签
    const u = new URL(loc.href)
    expect(u.searchParams.get('out_trade_no')).toBe(prepayVo.outTradeNo)
    expect(u.searchParams.get('sign')).toBeTruthy()
    expect(u.searchParams.get('timestamp')).toBeTruthy()
  })

  it('订单详情页与列表页走同一条 prepay 链路（入口不同、行为一致）', async () => {
    const loc = stubLocation('/order/1001')
    const { wrapper } = await mountView(OrderDetail, { path: '/order/1001' })
    await waitFor(() => expect(wrapper.vm.order).toBeTruthy())

    await wrapper.vm.handlePay()
    await waitFor(() => expect(loc.href).not.toBe(''))
    expect(loc.href).toBe(prepayVo.cashierUrl)
  })
})

describe('支付 · 跳转前后不该有「已支付成功」的错误暗示', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
    vi.clearAllMocks()
  })

  it('点击支付只发起 prepay，不提前提示成功、也不改订单状态', async () => {
    const loc = stubLocation('/orders')
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    const before = wrapper.vm.orders.map(o => o.status)
    await wrapper.vm.handlePay(waitPayOrder())
    await waitFor(() => expect(loc.href).not.toBe(''))

    const successMsgs = ElMessage.success.mock.calls.map(c => String(c[0]))
    expect(successMsgs.some(m => m.includes('支付成功'))).toBe(false)
    // 本地订单状态不应被前端改写（真实状态由第三方回调驱动）
    expect(wrapper.vm.orders.map(o => o.status)).toEqual(before)
  })
})
