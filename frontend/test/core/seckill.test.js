// 秒杀链路 · 页面级测试（MSW 拦截真实 HTTP）
//
// 覆盖：列表加载 → 未登录拦截 → 已登录打开地址弹窗 → 下单拿 requestId → 轮询 → 跳转订单详情
// 轮询的「排队中/成功/失败/超时」细节在 seckill-polling.test.js 用假定时器精确验证。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import { setSeckillResult } from '../handlers.js'
import { USER_TOKEN } from '../fixtures.js'
import Seckill from '@/views/mall/Seckill.vue'

describe('秒杀链路（页面级）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('加载秒杀列表：渲染商品名、秒杀价与库存', async () => {
    const { wrapper } = await mountView(Seckill, { path: '/seckill' })

    await waitFor(() => expect(wrapper.vm.productList.length).toBe(2))

    const text = wrapper.text()
    expect(text).toContain('秒杀款耳机')
    expect(text).toContain('秒杀款键盘')
    expect(wrapper.vm.total).toBe(2)
  })

  it('未登录点「立即抢购」→ 提示先登录并跳转登录页（带 redirect）', async () => {
    const { wrapper, router } = await mountView(Seckill, { path: '/seckill' })
    await waitFor(() => expect(wrapper.vm.productList.length).toBe(2))

    await wrapper.vm.handleSeckill(wrapper.vm.productList[0])

    // 弹窗确认走 ElMessageBox.confirm（setup.js 里已 mock 成 confirm）
    expect(ElMessageBox.confirm).toHaveBeenCalled()
    await waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(router.currentRoute.value.query.redirect).toBe('/seckill')
    // 未登录不应该打开选地址弹窗
    expect(wrapper.vm.addressDialogVisible).toBe(false)
  })

  it('已登录点「立即抢购」→ 打开地址选择弹窗并载入地址列表', async () => {
    seedLogin()
    const { wrapper } = await mountView(Seckill, { path: '/seckill' })
    await waitFor(() => expect(wrapper.vm.productList.length).toBe(2))

    await wrapper.vm.handleSeckill(wrapper.vm.productList[0])

    await waitFor(() => expect(wrapper.vm.addressDialogVisible).toBe(true))
    expect(wrapper.vm.currentProduct.id).toBe(20)
    expect(wrapper.vm.addressList.length).toBeGreaterThan(0)
  })

  it('完整秒杀成功链路：下单拿 requestId → 轮询成功 → 跳转订单详情', async () => {
    seedLogin()
    // 直接指定该 requestId 的最终结果：成功
    setSeckillResult('req-mock-001', { status: 1, orderId: 1001, orderNo: 'ORD2026101400001', failReason: null })

    const { wrapper, router } = await mountView(Seckill, { path: '/seckill' })
    await waitFor(() => expect(wrapper.vm.productList.length).toBe(2))

    await wrapper.vm.handleSeckill(wrapper.vm.productList[0])
    await waitFor(() => expect(wrapper.vm.addressDialogVisible).toBe(true))

    wrapper.vm.selectedAddressId = 50
    await wrapper.vm.confirmOrder()

    // 轮询是 1s 一次，这里耐心等待跳转
    await waitFor(() => expect(router.currentRoute.value.path).toBe('/order/1001'), { timeout: 5000 })
    expect(ElMessage.success).toHaveBeenCalledWith('抢购成功！')
    // 成功后轮询弹窗要关闭
    expect(wrapper.vm.pollingDialogVisible).toBe(false)
  })

  it('未选收货地址时不应发起秒杀下单', async () => {
    seedLogin()
    const { wrapper } = await mountView(Seckill, { path: '/seckill' })
    await waitFor(() => expect(wrapper.vm.productList.length).toBe(2))

    await wrapper.vm.handleSeckill(wrapper.vm.productList[0])
    await waitFor(() => expect(wrapper.vm.addressDialogVisible).toBe(true))

    wrapper.vm.selectedAddressId = null
    await wrapper.vm.confirmOrder()

    // 没有地址就不该进入排队弹窗
    expect(wrapper.vm.pollingDialogVisible).toBe(false)
    expect(wrapper.vm.ordering).toBe(false)
  })
})
