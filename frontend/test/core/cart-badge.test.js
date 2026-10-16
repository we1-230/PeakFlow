// 购物车角标 · 计数收进 store 并在变更点实时刷新
//
// 背景：角标原来只在 MallLayout onMounted 拉一次，商品详情页加购、购物车页
// 改数量/删除后顶栏角标不更新。现在 useCartStore 持有 count + refresh()，
// 变更点调用 refresh()，MallLayout 直接消费 store 状态。这里固化该语义。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCartStore } from '@/stores/cart'
import MallLayout from '@/layouts/MallLayout.vue'
import ProductDetail from '@/views/mall/ProductDetail.vue'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import { normalProducts } from '../fixtures.js'

describe('购物车角标 · store 层', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedLogin()
  })

  it('refresh() 用后端购物车条目数更新角标 count', async () => {
    const store = useCartStore()
    expect(store.count).toBe(0)
    await store.refresh()
    // MSW 初始购物车桩数据非空（fixtures.cartItems）
    expect(store.count).toBeGreaterThan(0)
  })

  it('refresh() 失败（未登录/网络异常）时保持原值不抛错', async () => {
    // 清登录态：MSW 的 /api/cart/list 会以 401 拒绝
    localStorage.removeItem('mall_jwt')
    const store = useCartStore()
    store.count = 3
    await expect(store.refresh()).resolves.not.toThrow()
    expect(store.count).toBe(3)
  })
})

describe('购物车角标 · 布局层', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
  })

  it('MallLayout 显示角标且数值来自 store.count', async () => {
    const { wrapper } = await mountView(MallLayout, { path: '/' })
    const store = useCartStore(wrapper.vm.$.appContext.config.globalProperties.$pinia)
    await waitFor(() => expect(store.count).toBeGreaterThan(0))
    const badge = wrapper.find('.mall-cart-badge')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe(String(store.count))
  })

  it('商品详情页加购成功后触发角标刷新', async () => {
    const pid = normalProducts.find(p => !p.isSeckill)?.id ?? normalProducts[0].id
    const { wrapper } = await mountView(ProductDetail, { path: `/product/${pid}` })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())

    const store = useCartStore(wrapper.vm.$.appContext.config.globalProperties.$pinia)
    // 先把角标对齐当前桩数据（MSW 的购物车桩跨用例持久化），再加购断言 +1
    await store.refresh()
    const before = store.count
    const spy = vi.spyOn(store, 'refresh')

    wrapper.vm.quantity = 1
    await wrapper.vm.handleAddCart()

    expect(spy).toHaveBeenCalled()
    // MSW 的 /api/cart/add 会向桩数据 push 一条，条目数应增加
    await waitFor(() => expect(store.count).toBe(before + 1))
  })
})
