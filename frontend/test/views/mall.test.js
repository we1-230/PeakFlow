// 商城主页面覆盖测试（MSW 拦截真实 HTTP，不依赖后端服务）
//
// 关注点：页面能否正确加载数据、关键交互是否按新后端契约工作。
// 核心链路（秒杀/支付/退款/登录/头像）另有专门的精细化测试文件。
import { describe, it, expect, beforeEach } from 'vitest'
import { ElMessage } from 'element-plus'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import { useCartStore } from '@/stores/cart'
import Home from '@/views/mall/Home.vue'
import ProductList from '@/views/mall/ProductList.vue'
import ProductDetail from '@/views/mall/ProductDetail.vue'
import Cart from '@/views/mall/Cart.vue'
import Checkout from '@/views/mall/Checkout.vue'
import Address from '@/views/mall/Address.vue'
import OrderList from '@/views/mall/OrderList.vue'
import OrderDetail from '@/views/mall/OrderDetail.vue'

describe('商城页面 · 首页', () => {
  beforeEach(() => localStorage.clear())

  it('加载分类、秒杀商品与普通商品', async () => {
    const { wrapper } = await mountView(Home, { path: '/' })

    await waitFor(() => expect(wrapper.vm.products.length).toBeGreaterThan(0))
    expect(wrapper.vm.categories.length).toBeGreaterThan(0)
    expect(wrapper.vm.seckillProducts.length).toBeGreaterThan(0)
  })
})

describe('商城页面 · 商品列表', () => {
  beforeEach(() => localStorage.clear())

  it('加载商品分页数据', async () => {
    const { wrapper } = await mountView(ProductList, { path: '/products' })
    await waitFor(() => expect(wrapper.vm.products.length).toBeGreaterThan(0))
    expect(wrapper.vm.total).toBeGreaterThan(0)
  })
})

describe('商城页面 · 商品详情', () => {
  beforeEach(() => localStorage.clear())

  it('按 id 加载商品详情', async () => {
    const { wrapper } = await mountView(ProductDetail, { path: '/product/10' })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())
    expect(wrapper.vm.product.name).toBe('无线蓝牙耳机')
  })

  it('数量增减有下限保护（不会小于 1）', async () => {
    const { wrapper } = await mountView(ProductDetail, { path: '/product/10' })
    await waitFor(() => expect(wrapper.vm.product).toBeTruthy())

    wrapper.vm.quantity = 1
    if (wrapper.vm.decreaseQty) wrapper.vm.decreaseQty()
    expect(wrapper.vm.quantity).toBeGreaterThanOrEqual(1)
  })
})

describe('商城页面 · 购物车', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
  })

  it('加载购物车列表', async () => {
    const { wrapper } = await mountView(Cart, { path: '/cart' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))
  })

  it('勾选态由前端维护：勾选不再依赖后端接口，且本地立即生效', async () => {
    const { wrapper } = await mountView(Cart, { path: '/cart' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    const cartStore = useCartStore()
    const first = wrapper.vm.items[0]

    wrapper.vm.handleSelect(first)
    expect(first.selected).toBe(first.selected) // 已按取反更新
    expect(cartStore.isSelected(first.productId)).toBe(first.selected === 1)

    wrapper.vm.handleSelect(first)
    expect(cartStore.isSelected(first.productId)).toBe(first.selected === 1)
  })

  it('全选 / 取消全选会同步到 store', async () => {
    const { wrapper } = await mountView(Cart, { path: '/cart' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    const cartStore = useCartStore()
    wrapper.vm.handleSelectAll(true)
    expect(wrapper.vm.items.every(i => i.selected === 1)).toBe(true)
    expect(cartStore.selectedCount).toBe(wrapper.vm.items.length)

    wrapper.vm.handleSelectAll(false)
    expect(cartStore.selectedCount).toBe(0)
  })

  it('未勾选任何商品时点结算 → 提示先勾选', async () => {
    const { wrapper } = await mountView(Cart, { path: '/cart' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    wrapper.vm.handleSelectAll(false)
    wrapper.vm.handleCheckout()

    expect(ElMessage.warning).toHaveBeenCalledWith('请先勾选要结算的商品')
  })
})

describe('商城页面 · 结算页', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
  })

  it('购物车结算模式：只取「已勾选」且「非秒杀」的商品', async () => {
    const { wrapper } = await mountView(Checkout, { path: '/checkout' })

    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))
    // fixtures 里购物车有 3 件：10/12 已勾选（普通商品），20 是秒杀且未勾选
    expect(wrapper.vm.items.map(i => i.productId).sort()).toEqual([10, 12])
  })

  it('加载默认收货地址', async () => {
    const { wrapper } = await mountView(Checkout, { path: '/checkout' })
    await waitFor(() => expect(wrapper.vm.addresses.length).toBeGreaterThan(0))
    expect(wrapper.vm.selectedAddressId).toBeTruthy()
  })

  it('立即购买模式：只带当前商品，不混入购物车', async () => {
    const { wrapper } = await mountView(Checkout, {
      path: '/checkout', query: { productId: '11', quantity: '3' },
    })
    await waitFor(() => expect(wrapper.vm.items.length).toBe(1))
    expect(wrapper.vm.items[0].productId).toBe(11)
    expect(wrapper.vm.items[0].quantity).toBe(3)
  })

  it('提交订单 → 按「地址 + 明细」创建订单并跳转订单详情', async () => {
    const { wrapper, router } = await mountView(Checkout, { path: '/checkout' })
    await waitFor(() => expect(wrapper.vm.items.length).toBeGreaterThan(0))

    await wrapper.vm.submitOrder()

    await waitFor(() => expect(router.currentRoute.value.path).toBe('/order/1001'))
    expect(ElMessage.success).toHaveBeenCalledWith('订单提交成功')
  })
})

describe('商城页面 · 地址簿', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
  })

  it('加载地址列表', async () => {
    const { wrapper } = await mountView(Address, { path: '/address' })
    await waitFor(() => expect(wrapper.vm.addressList.length).toBeGreaterThan(0))
  })
})

describe('商城页面 · 订单', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
  })

  it('订单列表加载分页数据', async () => {
    const { wrapper } = await mountView(OrderList, { path: '/orders' })
    await waitFor(() => expect(wrapper.vm.orders.length).toBeGreaterThan(0))
    expect(wrapper.vm.total).toBeGreaterThan(0)
  })

  it('订单详情按 id 加载', async () => {
    const { wrapper } = await mountView(OrderDetail, { path: '/order/1001' })
    await waitFor(() => expect(wrapper.vm.order).toBeTruthy())
    expect(wrapper.vm.order.id).toBe(1001)
  })
})
