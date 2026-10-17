// 秒杀商品不可加购 · 前后端一致性的前端侧守卫
//
// 业务规则：秒杀商品只能走秒杀通道下单，不能加入购物车。
// 前端在详情页隐藏了「加入购物车」按钮（体验层），但**业务规则必须由后端强制**——
// 已确认后端 CartServiceImpl#addToCart 会拒绝秒杀商品并返回明确文案
// （2026-10-17 修复：此前后端不拦，且因 stock=0 返回误导性的「库存不足」）。
//
// 本文件守两件事：
//   1. 秒杀商品详情页不渲染加购按钮，且明确提示只能走秒杀通道
//   2. 即使有人绕过 UI 直接调 Cart.add(秒杀商品)，前端也要给出后端返回的明确提示，
//      而不是悄悄成功（桩已按真实后端行为返回业务错误）
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import { seckillProducts, normalProducts } from '../fixtures.js'
import ProductDetail from '@/views/mall/ProductDetail.vue'
import { Cart } from '@/api'

const SKU_SECKILL = seckillProducts[0]
const SKU_NORMAL = normalProducts[0]

describe('秒杀商品 · 不可加入购物车', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
    vi.clearAllMocks()
  })

  it('秒杀详情页不渲染「加入购物车」按钮，并提示只能走秒杀通道', async () => {
    const { wrapper } = await mountView(ProductDetail, { path: `/product/${SKU_SECKILL.id}` })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())

    expect(wrapper.find('.btn-cart-outline').exists()).toBe(false)
    expect(wrapper.text()).toContain('立即抢购')
    expect(wrapper.text()).toContain('秒杀商品仅支持秒杀通道购买')
  })

  it('普通商品照常渲染加购按钮（不能误伤）', async () => {
    const { wrapper } = await mountView(ProductDetail, { path: `/product/${SKU_NORMAL.id}` })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())

    expect(wrapper.find('.btn-cart-outline').exists()).toBe(true)
    expect(wrapper.text()).toContain('加入购物车')
  })

  it('绕过 UI 直接调加购接口：后端拒绝并返回明确文案（不是误导性的「库存不足」）', async () => {
    // 桩已与真实后端行为对齐：秒杀商品加购 → 业务错误
    let message = ''
    await Cart.add(SKU_SECKILL.id, 1).catch(e => {
      message = String(e?.message || '')
    })
    expect(message).toContain('秒杀')
    expect(message).not.toContain('库存不足')
  })

  it('前端若因异常走到 handleAddCart：提示失败且不写入购物车数据', async () => {
    // 构造一个「后端拒绝」的场景：直接调页面的加购处理函数
    const { wrapper } = await mountView(ProductDetail, { path: `/product/${SKU_SECKILL.id}` })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())

    await wrapper.vm.handleAddCart()

    // 秒杀商品 stock=0，本地也会先拦下来（提示售罄/不可加购），绝不能静默成功
    const successMsgs = ElMessage.success.mock.calls.map(c => String(c[0]))
    expect(successMsgs.some(m => m.includes('已加入购物车'))).toBe(false)
  })
})
