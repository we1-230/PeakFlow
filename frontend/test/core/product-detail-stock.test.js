// 秒杀商品详情 · 库存口径一致性（2026-10-17 线上问题回归）
//
// 线上现象：秒杀商品明明有库存，一进详情页就弹「该商品已售罄」、库存显示 0，
//          但点「立即抢购」却能成功。
// 根因：后端 Product 有两个独立库存字段 ——
//   · stock        —— 普通库存，秒杀商品恒为 0
//   · seckillStock —— 秒杀库存（真实可售量）
// 详情页的「展示」「售罄提示」「进度条」全读 stock，只有抢购校验读 seckillStock，
// 于是页面自相矛盾。本文件把「展示与校验必须同源」固化成测试。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { http } from 'msw'
import { server } from '../setup.js'
import { mountView, waitFor } from '../helpers.js'
import { seckillProducts, normalProducts } from '../fixtures.js'
import ProductDetail from '@/views/mall/ProductDetail.vue'

const SKU_SECKILL = seckillProducts[0]      // stock=0, seckillStock=8, isSeckill=1
const SKU_NORMAL = normalProducts[0]        // stock=50, isSeckill=0

describe('秒杀商品详情 · 库存展示与校验同源', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('秒杀商品（stock=0 / seckillStock=8）：不得弹「已售罄」，应显示秒杀库存 8 件', async () => {
    const { wrapper } = await mountView(ProductDetail, { path: `/product/${SKU_SECKILL.id}` })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())

    // 核心断言 1：进入页面不弹售罄提示（线上就是这条弹窗 + 库存 0 让人以为卖完了）
    const warns = ElMessage.warning.mock.calls.map(c => String(c[0]))
    expect(warns.some(m => m.includes('售罄') || m.includes('抢完'))).toBe(false)

    // 核心断言 2：可售库存按秒杀口径取，且文案标明「秒杀库存」
    expect(wrapper.vm.availableStock).toBe(SKU_SECKILL.seckillStock)
    const text = wrapper.text()
    expect(text).toContain(`秒杀库存 ${SKU_SECKILL.seckillStock} 件`)
    // 不能再出现「库存 0 件」这种误导展示
    expect(text).not.toContain('库存 0 件')
  })

  it('秒杀商品库存展示与抢购校验同源：availableStock 即 handleSeckill 的判断依据', async () => {
    const { wrapper } = await mountView(ProductDetail, { path: `/product/${SKU_SECKILL.id}` })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())

    // 有货时点抢购 → 不应被「已抢完」拦截（会去拉地址并打开弹窗）
    await wrapper.vm.handleSeckill()
    const warns = ElMessage.warning.mock.calls.map(c => String(c[0]))
    expect(warns.some(m => m.includes('抢完'))).toBe(false)
  })

  it('秒杀商品数量框禁用且上限为限购 1 件（后端唯一索引限购，前端不得放开多件）', async () => {
    const { wrapper } = await mountView(ProductDetail, { path: `/product/${SKU_SECKILL.id}` })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())

    expect(wrapper.vm.isSeckillProduct).toBe(true)
    expect(wrapper.vm.maxQuantity).toBe(1)
    // 明确展示限购原因，而不是让输入框「神秘地不能调」
    expect(wrapper.text()).toContain('秒杀限购 1 件')
  })

  it('秒杀库存为 0 时：提示「该秒杀商品已抢完」，且不误报普通商品售罄文案', async () => {
    server.use(http.get('/api/product/detail/:id', () =>
      new Response(JSON.stringify({
        code: 200, message: 'ok', success: true,
        data: { ...SKU_SECKILL, seckillStock: 0 },
        timestamp: Date.now(),
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const { wrapper } = await mountView(ProductDetail, { path: `/product/${SKU_SECKILL.id}` })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())

    const warns = ElMessage.warning.mock.calls.map(c => String(c[0]))
    expect(warns.some(m => m.includes('该秒杀商品已抢完'))).toBe(true)
    expect(wrapper.vm.availableStock).toBe(0)
  })

  it('普通商品不受影响：显示「库存 50 件」、数量可调到库存上限、不弹秒杀文案', async () => {
    const { wrapper } = await mountView(ProductDetail, { path: `/product/${SKU_NORMAL.id}` })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())

    expect(wrapper.vm.isSeckillProduct).toBe(false)
    expect(wrapper.vm.availableStock).toBe(SKU_NORMAL.stock)
    expect(wrapper.vm.stockLabel).toBe('库存')
    expect(wrapper.vm.maxQuantity).toBe(SKU_NORMAL.stock)
    expect(wrapper.text()).toContain(`库存 ${SKU_NORMAL.stock} 件`)
    // 普通商品不应该出现秒杀限购提示
    expect(wrapper.text()).not.toContain('秒杀限购')

    const warns = ElMessage.warning.mock.calls.map(c => String(c[0]))
    expect(warns.some(m => m.includes('售罄'))).toBe(false)
  })
})
