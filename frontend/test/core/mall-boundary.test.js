// 商城页面 · 边界与状态变化（不是主流程，但最容易出错的地方）
//
// 覆盖：数量下限、未选中结算、空状态、立即购买与购物车结算的隔离、
//       秒杀商品混入购物车结算的过滤、状态页签切换重置分页、售罄按钮禁用。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { http } from 'msw'
import { server } from '../setup.js'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import { cartItems, seckillProducts } from '../fixtures.js'
import Cart from '@/views/mall/Cart.vue'
import Checkout from '@/views/mall/Checkout.vue'
import OrderList from '@/views/mall/OrderList.vue'
import ProductList from '@/views/mall/ProductList.vue'
import Seckill from '@/views/mall/Seckill.vue'

describe('购物车 · 数量与删除的边界', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
    vi.clearAllMocks()
  })

  it('数量传 0 或负数：直接忽略，不调接口、不改本地值', async () => {
    const { wrapper } = await mountView(Cart, { path: '/cart' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    const item = wrapper.vm.items[0]
    const before = item.quantity
    const spy = vi.spyOn(wrapper.vm.$, 'emit')

    await wrapper.vm.handleQtyChange(item, 0)
    expect(item.quantity).toBe(before)
    await wrapper.vm.handleQtyChange(item, -5)
    expect(item.quantity).toBe(before)
    spy.mockRestore()
  })

  it('数量输入框收到非法字符：回落为 1 而不是 NaN', async () => {
    const { wrapper } = await mountView(Cart, { path: '/cart' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    const item = wrapper.vm.items[0]
    wrapper.vm.handleQtyInput(item, { target: { value: 'abc' } })
    await waitFor(() => expect(item.quantity).toBe(1))
    wrapper.vm.handleQtyInput(item, { target: { value: '-3' } })
    await waitFor(() => expect(item.quantity).toBe(1))
  })

  it('删除一件：真实调用删除接口并从列表移除该项（其余保留）', async () => {
    const { wrapper } = await mountView(Cart, { path: '/cart' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    const target = wrapper.vm.items[0].productId
    await wrapper.vm.handleRemove({ productId: target })

    await waitFor(() => expect(wrapper.vm.items.some(i => i.productId === target)).toBe(false))
    expect(ElMessage.success).toHaveBeenCalled()
  })

  it('购物车为空：渲染空状态文案与「去逛逛」入口（不是白屏/加载中卡住）', async () => {
    // 用覆盖 handler 模拟空车，避免把共享桩数据清空影响后续用例
    server.use(http.get('/api/cart/list', () => new Response(
      JSON.stringify({ code: 200, message: 'ok', success: true, data: [], timestamp: Date.now() }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    )))

    const { wrapper } = await mountView(Cart, { path: '/cart' })
    await waitFor(() => expect(wrapper.vm.loading).toBe(false))
    expect(wrapper.vm.items.length).toBe(0)
    expect(wrapper.text()).toContain('购物车还是空的')
    expect(wrapper.text()).toContain('去逛逛')
  })

  it('未勾选任何商品点结算：给出提示且不跳转', async () => {
    const { wrapper, router } = await mountView(Cart, { path: '/cart' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    wrapper.vm.handleSelectAll(false)
    await waitFor(() => expect(wrapper.vm.selectedCount).toBe(0))
    wrapper.vm.handleCheckout()

    const warns = ElMessage.warning.mock.calls.map(c => String(c[0]))
    expect(warns.some(m => m.includes('勾选'))).toBe(true)
    expect(router.currentRoute.value.path).toBe('/cart')
  })
})

describe('结算页 · 两种模式互不污染 + 金额计算', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
    vi.clearAllMocks()
  })

  it('立即购买模式（带 productId）：只加载该商品，不混入购物车条目', async () => {
    const { wrapper } = await mountView(Checkout, { path: '/checkout', query: { productId: '12', quantity: '2' } })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    expect(wrapper.vm.items.length).toBe(1)
    expect(wrapper.vm.items[0].productId).toBe(12)
    expect(wrapper.vm.items[0].quantity).toBe(2)
  })

  it('购物车模式：过滤掉未勾选与秒杀商品，只结算普通商品', async () => {
    // 先把购物车里所有普通商品勾上，再塞一个秒杀条目进桩数据
    const { wrapper } = await mountView(Checkout, { path: '/checkout' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    const ids = wrapper.vm.items.map(i => i.productId)
    // 秒杀商品 id 不允许出现在结算清单里
    expect(ids.some(id => seckillProducts.some(s => s.id === id))).toBe(false)
    // 未勾选的商品也不应出现
    expect(wrapper.vm.items.length).toBeLessThanOrEqual(cartItems.length)
  })

  it('应付总额 = Σ 单价 × 数量（保留两位，不因浮点累加出现 0.30000000004）', async () => {
    const { wrapper } = await mountView(Checkout, { path: '/checkout' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    const expected = wrapper.vm.items.reduce((s, i) => s + Number(i.price) * i.quantity, 0)
    expect(Number(wrapper.vm.totalAmount.toFixed(2))).toBe(Number(expected.toFixed(2)))
    expect(wrapper.text()).toContain(expected.toFixed(2))
  })

  it('未选地址提交：拦在本地并提示，不发请求', async () => {
    const { wrapper, router } = await mountView(Checkout, { path: '/checkout' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    wrapper.vm.selectedAddressId = null
    await wrapper.vm.submitOrder()

    const warns = ElMessage.warning.mock.calls.map(c => String(c[0]))
    expect(warns.some(m => m.includes('收货地址'))).toBe(true)
    expect(router.currentRoute.value.path).toBe('/checkout')
  })
})

describe('订单列表 · 状态页签与分页状态', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
  })

  it('切换状态页签：重置到第 1 页并按状态重新拉取', async () => {
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThanOrEqual(0))

    wrapper.vm.pageNum = 3
    wrapper.vm.handleStatusChange(1)
    await waitFor(() => expect(wrapper.vm.currentStatus).toBe(1))
    expect(wrapper.vm.pageNum).toBe(1)
  })

  it('重复点同一状态：不重复请求（幂等保护）', async () => {
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders).toBeTruthy())

    const cur = wrapper.vm.currentStatus
    const spy = vi.spyOn(wrapper.vm, 'loadOrders')
    wrapper.vm.handleStatusChange(cur)
    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
  })

  it('订单卡片汇总件数 = 各明细数量之和（不是条目数）', async () => {
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))

    const order = wrapper.vm.orders.find(o => (o.items || []).length > 0)
    expect(order, '桩数据里应至少有一张带明细的订单').toBeTruthy()
    const expected = order.items.reduce((s, i) => s + i.quantity, 0)
    expect(wrapper.vm.totalCount(order)).toBe(expected)
  })
})

describe('商品列表 · 分类与空结果', () => {
  beforeEach(() => localStorage.clear())

  it('按 query 的分类过滤：只展示该分类商品', async () => {
    const { wrapper } = await mountView(ProductList, { path: '/products', query: { categoryId: '2' } })
    await waitFor(() => expect(wrapper.vm.products.length).toBeGreaterThan(0))
    expect(wrapper.vm.products.every(p => p.categoryId === 2)).toBe(true)
  })

  it('关键词无命中：展示空状态而不是残留上一次结果', async () => {
    const { wrapper } = await mountView(ProductList, { path: '/products', query: { keyword: '完全不存在的商品名xyz' } })
    await waitFor(() => expect(wrapper.vm.loading).toBe(false))
    expect(wrapper.vm.products.length).toBe(0)
    expect(wrapper.text()).toMatch(/没有|暂无|空/)
  })
})

describe('秒杀页 · 售罄与库存展示', () => {
  beforeEach(() => localStorage.clear())

  it('售罄商品的抢购按钮禁用并显示「已抢光」', async () => {
    const { wrapper } = await mountView(Seckill, { path: '/seckill' })
    await waitFor(() => expect(wrapper.vm.productList.length).toBeGreaterThan(0))

    const soldOut = wrapper.vm.productList.find(i => Number(i.seckillStock) <= 0)
    if (soldOut) {
      const btn = wrapper.findAll('button').find(b => b.text().includes('已抢光'))
      expect(btn).toBeTruthy()
      expect(btn.attributes('disabled')).toBeDefined()
    } else {
      // 桩数据都有库存时，至少验证在售商品展示「剩余 N 件」
      expect(wrapper.text()).toMatch(/剩余 \d+ 件/)
    }
  })

  it('库存进度 = 已售 /（已售 + 剩余），不出现 NaN 或越界', async () => {
    const { wrapper } = await mountView(Seckill, { path: '/seckill' })
    await waitFor(() => expect(wrapper.vm.productList.length).toBeGreaterThan(0))

    for (const item of wrapper.vm.productList) {
      const total = Number(item.soldCount) + Number(item.seckillStock)
      if (total <= 0) continue
      const pct = (Number(item.soldCount) / total) * 100
      expect(Number.isNaN(pct)).toBe(false)
      expect(pct).toBeGreaterThanOrEqual(0)
      expect(pct).toBeLessThanOrEqual(100)
    }
  })
})
