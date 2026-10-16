// 秒杀链路 · 轮询逻辑专项测试（假定时器 + mock 掉 api 模块）
//
// 轮询是秒杀链路里最容易出错的一环：1 秒一次、最多 30 次、成功/失败/超时/异常四种出口，
// 还要保证成功或失败后把定时器停掉（否则会持续打后端）。这里用假定时器精确控制每一拍。
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'
import { mountView, seedLogin, waitFor } from '../helpers.js'

// 用 mock 替换 api 模块：轮询节奏由假定时器控制，不引入真实网络抖动
vi.mock('@/api', () => ({
  Seckill: { order: vi.fn(), result: vi.fn(), list: vi.fn() },
  Address: { list: vi.fn() },
}))

const { Seckill, Address } = await import('@/api')
const SeckillView = (await import('@/views/mall/Seckill.vue')).default

// 挂载 → 打开地址弹窗 → 选地址，返回可继续操作的上下文（这些步骤用真实定时器完成）
async function prepareOrdering(resultMock) {
  seedLogin()
  Seckill.list.mockResolvedValue({ list: [{ id: 20, name: '秒杀款耳机', seckillStock: 8 }], total: 1 })
  Address.list.mockResolvedValue([{ id: 50, receiverName: '周奥宇', isDefault: 1 }])
  Seckill.order.mockResolvedValue('req-1')
  Seckill.result.mockImplementation(resultMock)

  const ctx = await mountView(SeckillView, { path: '/seckill' })
  await waitFor(() => expect(ctx.wrapper.vm.productList.length).toBe(1))
  await ctx.wrapper.vm.handleSeckill(ctx.wrapper.vm.productList[0])
  await waitFor(() => expect(ctx.wrapper.vm.addressDialogVisible).toBe(true))
  ctx.wrapper.vm.selectedAddressId = 50
  return ctx
}

describe('秒杀链路 · 轮询', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('下单后进入排队弹窗，文本带已等待秒数', async () => {
    const { wrapper } = await prepareOrdering(() => Promise.resolve({ status: 0, orderId: null, failReason: null }))

    vi.useFakeTimers()
    await wrapper.vm.confirmOrder()

    expect(wrapper.vm.pollingDialogVisible).toBe(true)
    expect(Seckill.order).toHaveBeenCalledWith(20, 50)
    expect(wrapper.vm.pollingText).toBe('排队中，请稍候...')

    await vi.advanceTimersByTimeAsync(1000)
    expect(wrapper.vm.pollingText).toBe('排队中... (1s)')

    await vi.advanceTimersByTimeAsync(2000)
    expect(wrapper.vm.pollingText).toBe('排队中... (3s)')
    expect(Seckill.result).toHaveBeenCalledTimes(3)
    expect(Seckill.result).toHaveBeenCalledWith('req-1')
  })

  it('轮询到「成功」→ 停止轮询、关闭弹窗、提示成功并跳转订单详情', async () => {
    const { wrapper, router } = await prepareOrdering(
      () => Promise.resolve({ status: 1, orderId: 1001, orderNo: 'ORD1', failReason: null })
    )

    vi.useFakeTimers()
    await wrapper.vm.confirmOrder()
    await vi.advanceTimersByTimeAsync(1000)

    expect(router.currentRoute.value.path).toBe('/order/1001')
    expect(ElMessage.success).toHaveBeenCalledWith('抢购成功！')
    expect(wrapper.vm.pollingDialogVisible).toBe(false)

    // 停止轮询：再推进时间也不应继续请求
    const calls = Seckill.result.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    expect(Seckill.result.mock.calls.length).toBe(calls)
  })

  it('轮询到「失败」→ 弹出失败原因、关闭弹窗、停止轮询', async () => {
    const { wrapper } = await prepareOrdering(
      () => Promise.resolve({ status: 2, orderId: null, failReason: '库存不足，已被抢完' })
    )

    vi.useFakeTimers()
    await wrapper.vm.confirmOrder()
    await vi.advanceTimersByTimeAsync(1000)

    expect(ElMessageBox.alert).toHaveBeenCalledWith(
      '库存不足，已被抢完', '抢购失败', expect.objectContaining({ type: 'error' })
    )
    expect(wrapper.vm.pollingDialogVisible).toBe(false)

    const calls = Seckill.result.mock.calls.length
    await vi.advanceTimersByTimeAsync(3000)
    expect(Seckill.result.mock.calls.length).toBe(calls)
  })

  it('失败但后端未给原因 → 兜底提示「商品已售罄」', async () => {
    const { wrapper } = await prepareOrdering(
      () => Promise.resolve({ status: 2, orderId: null, failReason: null })
    )

    vi.useFakeTimers()
    await wrapper.vm.confirmOrder()
    await vi.advanceTimersByTimeAsync(1000)

    expect(ElMessageBox.alert).toHaveBeenCalledWith(
      '抢购失败，商品已售罄', '抢购失败', expect.objectContaining({ type: 'error' })
    )
  })

  it('排队满 30 秒仍未出结果 → 提示排队超时并停止轮询', async () => {
    const { wrapper } = await prepareOrdering(() => Promise.resolve({ status: 0, orderId: null, failReason: null }))

    vi.useFakeTimers()
    await wrapper.vm.confirmOrder()

    // 推进到第 29 拍：仍在排队
    await vi.advanceTimersByTimeAsync(29 * 1000)
    expect(wrapper.vm.pollingText).toBe('排队中... (29s)')
    expect(wrapper.vm.pollingDialogVisible).toBe(true)

    // 第 30 拍：超时
    await vi.advanceTimersByTimeAsync(1000)
    expect(ElMessage.warning).toHaveBeenCalledWith('排队超时，请稍后查看订单状态')
    expect(wrapper.vm.pollingDialogVisible).toBe(false)

    const calls = Seckill.result.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    expect(Seckill.result.mock.calls.length).toBe(calls)
  })

  it('查询结果接口异常 → 提示查询失败并停止轮询（不会一直空转）', async () => {
    const { wrapper } = await prepareOrdering(() => Promise.reject(new Error('network')))

    vi.useFakeTimers()
    await wrapper.vm.confirmOrder()
    await vi.advanceTimersByTimeAsync(1000)

    expect(ElMessage.error).toHaveBeenCalledWith('查询结果失败')
    expect(wrapper.vm.pollingDialogVisible).toBe(false)

    const calls = Seckill.result.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    expect(Seckill.result.mock.calls.length).toBe(calls)
  })

  it('组件卸载时清理轮询定时器（避免残留请求）', async () => {
    const { wrapper } = await prepareOrdering(() => Promise.resolve({ status: 0, orderId: null, failReason: null }))

    vi.useFakeTimers()
    await wrapper.vm.confirmOrder()
    await vi.advanceTimersByTimeAsync(1000)

    const calls = Seckill.result.mock.calls.length
    wrapper.unmount() // 触发 onUnmounted → stopPolling

    await vi.advanceTimersByTimeAsync(5000)
    expect(Seckill.result.mock.calls.length).toBe(calls)
  })
})
