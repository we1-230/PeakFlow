// 通用组件与布局 · 渲染 / props / 事件 / 状态变化 / 边界
//
// 补齐此前缺失的「组件级」覆盖：品牌组件（props 派生）、商城布局（搜索规范化、
// 退出登录、角标、后台入口）、后台布局（按权限点渲染菜单、角色推断、窄屏折叠）。
// 这些都属于「不是业务主流程，但一改就容易悄悄坏掉」的地方。
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { makeJwt, ALL_PERMISSIONS } from '../fixtures.js'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import BrandMark from '@/components/BrandMark.vue'
import BrandLogo from '@/components/BrandLogo.vue'
import MallLayout from '@/layouts/MallLayout.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'

// 只给后台所需权限，模拟真实角色（与后端 role_permission 一致）
const PRODUCT_OPS_PERMS = ['stats:view', 'product:manage', 'category:manage', 'seckill:manage', 'file:upload']
const ORDER_SVC_PERMS = ['stats:view', 'order:read', 'order:ship', 'refund:read', 'refund:audit']

function seedAs(perms, { username = 'tester', nickname = '测试员', id = 100 } = {}) {
  localStorage.setItem('mall_jwt', makeJwt(perms, id))
  localStorage.setItem('mall_user', JSON.stringify({ id, username, nickname }))
}

describe('BrandMark · props 派生尺寸/圆角/字号', () => {
  it('size 决定宽高、圆角与字号（按比例缩放，不是固定值）', () => {
    const small = mount(BrandMark, { props: { size: 30 } })
    const style = small.element.getAttribute('style')
    expect(style).toContain('width: 30px')
    expect(style).toContain('height: 30px')
    expect(style).toContain(`font-size: ${Math.round(30 * 0.54)}px`)
    expect(style).toContain(`border-radius: ${Math.round(30 * 0.28)}px`)

    const large = mount(BrandMark, { props: { size: 60 } })
    expect(large.element.getAttribute('style')).toContain('width: 60px')
    expect(large.element.getAttribute('style')).toContain(`font-size: ${Math.round(60 * 0.54)}px`)
  })

  it('size >= 36 时加 is-lg 类（大尺寸走不同视觉权重）', () => {
    expect(mount(BrandMark, { props: { size: 35 } }).classes()).not.toContain('is-lg')
    expect(mount(BrandMark, { props: { size: 36 } }).classes()).toContain('is-lg')
  })

  it('deep 切换底色，且始终是装饰性元素（aria-hidden）', () => {
    const light = mount(BrandMark, { props: { size: 30, deep: false } })
    const deep = mount(BrandMark, { props: { size: 30, deep: true } })
    expect(light.element.getAttribute('style')).not.toBe(deep.element.getAttribute('style'))
    expect(light.attributes('aria-hidden')).toBe('true')
  })

  it('内容固定为商标字母 A（不随 props 变化，避免被误当图标组件复用）', () => {
    expect(mount(BrandMark).text()).toBe('A')
  })
})

describe('BrandLogo · 尺寸映射与深色变体', () => {
  it('size 三档映射到不同字体尺寸，默认 md', () => {
    const sm = mount(BrandLogo, { props: { size: 'sm' } })
    const md = mount(BrandLogo, { props: { size: 'md' } })
    const lg = mount(BrandLogo, { props: { size: 'lg' } })
    expect(sm.classes()).toContain('size-sm')
    expect(md.classes()).toContain('size-md')
    expect(lg.classes()).toContain('size-lg')
    // markSize: sm=22 / md=30 / lg=42 —— 连锁品牌标随字号放大
    expect(sm.find('.brand-mark').element.getAttribute('style')).toContain('width: 22px')
    expect(md.find('.brand-mark').element.getAttribute('style')).toContain('width: 30px')
  })

  it('variant=dark 时品牌标走深色底（deep）且根元素带 dark 类', () => {
    const dark = mount(BrandLogo, { props: { variant: 'dark' } })
    expect(dark.classes()).toContain('dark')
    const light = mount(BrandLogo, { props: { variant: 'light' } })
    expect(light.find('.brand-mark').element.getAttribute('style'))
      .not.toBe(dark.find('.brand-mark').element.getAttribute('style'))
  })

  it('非法 size 回落到 30px，不产生 NaN 样式', () => {
    const weird = mount(BrandLogo, { props: { size: 'xxx' } })
    expect(weird.find('.brand-mark').element.getAttribute('style')).toContain('width: 30px')
    expect(weird.html()).not.toContain('NaN')
  })
})

describe('MallLayout · 搜索关键词规范化（后端多词匹配依赖它）', () => {
  beforeEach(() => localStorage.clear())

  it('全角空格转半角、连续空白压成一个空格再跳转', async () => {
    const { wrapper, router } = await mountView(MallLayout, { path: '/' })
    await waitFor(() => expect(wrapper.find('.mall-search-box input').exists()).toBe(true))

    await wrapper.find('.mall-search-box input').setValue('  机械\u3000键盘   青轴  ')
    await wrapper.find('.mall-search-box button').trigger('click')
    await waitFor(() => expect(router.currentRoute.value.path).toBe('/products'))
    expect(router.currentRoute.value.query.keyword).toBe('机械 键盘 青轴')
  })

  it('空白关键词不跳转（避免空搜索刷接口）', async () => {
    const { wrapper, router } = await mountView(MallLayout, { path: '/' })
    await waitFor(() => expect(wrapper.find('.mall-search-box input').exists()).toBe(true))

    await wrapper.find('.mall-search-box input').setValue('   \u3000  ')
    await wrapper.find('.mall-search-box button').trigger('click')
    // 仍停在首页，且没带 keyword 查询参数
    expect(router.currentRoute.value.path).toBe('/')
    expect(router.currentRoute.value.query.keyword).toBeUndefined()
  })

  it('回车与点按钮等价（同一个 doSearch）', async () => {
    const { wrapper, router } = await mountView(MallLayout, { path: '/' })
    await waitFor(() => expect(wrapper.find('.mall-search-box input').exists()).toBe(true))

    await wrapper.find('.mall-search-box input').setValue('保温杯')
    await wrapper.find('.mall-search-box input').trigger('keyup.enter')
    await waitFor(() => expect(router.currentRoute.value.path).toBe('/products'))
    expect(router.currentRoute.value.query.keyword).toBe('保温杯')
  })
})

describe('MallLayout · 登录态相关渲染', () => {
  beforeEach(() => localStorage.clear())

  it('未登录：显示登录/注册入口，不显示昵称与退出', async () => {
    const { wrapper } = await mountView(MallLayout, { path: '/' })
    const text = wrapper.text()
    expect(text).toContain('登录')
    expect(text).toContain('注册')
    expect(text).not.toContain('退出')
  })

  it('已登录普通用户：显示昵称与退出，但不显示「管理后台」入口', async () => {
    seedAs([], { nickname: '普通用户' })
    const { wrapper } = await mountView(MallLayout, { path: '/' })
    await waitFor(() => expect(wrapper.find('.topbar-nickname').exists()).toBe(true))
    const text = wrapper.text()
    expect(text).toContain('普通用户')
    expect(text).toContain('退出')
    expect(text).not.toContain('管理后台')
  })

  it('已登录管理员：额外出现「管理后台」入口（按权限判定，不看用户名）', async () => {
    seedAs(ALL_PERMISSIONS, { nickname: '管理员' })
    const { wrapper } = await mountView(MallLayout, { path: '/' })
    await waitFor(() => expect(wrapper.find('.topbar-nickname').exists()).toBe(true))
    expect(wrapper.text()).toContain('管理后台')
  })

  it('点退出：清空本地登录态并回到首页', async () => {
    seedAs([], { nickname: '普通用户' })
    const { wrapper, router } = await mountView(MallLayout, { path: '/cart' })
    await waitFor(() => expect(wrapper.find('.topbar-nickname').exists()).toBe(true))

    const logout = wrapper.findAll('.topbar a, .topbar .right a').find(a => a.text() === '退出')
    expect(logout, '顶栏应存在「退出」链接').toBeTruthy()
    await logout.trigger('click')

    await waitFor(() => expect(localStorage.getItem('mall_jwt')).toBeNull())
    expect(localStorage.getItem('mall_user')).toBeNull()
    await waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
  })
})

describe('AdminLayout · 菜单按权限点渲染（三角色画像）', () => {
  beforeEach(() => localStorage.clear())

  it('超级管理员：7 个菜单项全开', async () => {
    seedAs(ALL_PERMISSIONS, { nickname: '超级管理员' })
    const { wrapper } = await mountView(AdminLayout, { path: '/admin' })
    await waitFor(() => expect(wrapper.findAll('.menu-label').length).toBeGreaterThan(0))

    const labels = wrapper.findAll('.menu-label').map(m => m.text())
    for (const expected of ['仪表盘', '商品管理', '分类管理', '秒杀管理', '用户管理', '订单管理', '退款管理']) {
      expect(labels, `缺少菜单：${expected}`).toContain(expected)
    }
    expect(wrapper.find('.user-role').text()).toBe('超级管理员')
  })

  it('商品运营：只有 仪表盘/商品/分类/秒杀，订单与退款不可见', async () => {
    seedAs(PRODUCT_OPS_PERMS, { nickname: '商品运营' })
    const { wrapper } = await mountView(AdminLayout, { path: '/admin' })
    await waitFor(() => expect(wrapper.findAll('.menu-label').length).toBeGreaterThan(0))

    const labels = wrapper.findAll('.menu-label').map(m => m.text())
    expect(labels).toEqual(expect.arrayContaining(['仪表盘', '商品管理', '分类管理', '秒杀管理']))
    expect(labels).not.toContain('订单管理')
    expect(labels).not.toContain('退款管理')
    expect(labels).not.toContain('用户管理')
    expect(wrapper.find('.user-role').text()).toBe('商品运营')
  })

  it('订单客服：只有 仪表盘/订单/退款，商品类不可见', async () => {
    seedAs(ORDER_SVC_PERMS, { nickname: '订单客服' })
    const { wrapper } = await mountView(AdminLayout, { path: '/admin' })
    await waitFor(() => expect(wrapper.findAll('.menu-label').length).toBeGreaterThan(0))

    const labels = wrapper.findAll('.menu-label').map(m => m.text())
    expect(labels).toEqual(expect.arrayContaining(['仪表盘', '订单管理', '退款管理']))
    expect(labels).not.toContain('商品管理')
    expect(labels).not.toContain('用户管理')
    expect(wrapper.find('.user-role').text()).toBe('订单客服')
  })

  it('无任何后台权限：布局整体不渲染（双保险，防直改 URL 进入）', async () => {
    seedAs([], { nickname: '普通用户' })
    const { wrapper } = await mountView(AdminLayout, { path: '/admin' })
    // canEnter=false → 整个模板不渲染
    expect(wrapper.find('.admin-sidebar').exists()).toBe(false)
  })

  it('「刷新」按钮向上抛 refresh 事件（页面据此重拉数据）', async () => {
    seedAs(ALL_PERMISSIONS)
    const { wrapper } = await mountView(AdminLayout, { path: '/admin' })
    await waitFor(() => expect(wrapper.find('.header-action').exists()).toBe(true))

    await wrapper.find('.header-action').trigger('click')
    expect(wrapper.emitted('refresh')).toBeTruthy()
    expect(wrapper.emitted('refresh').length).toBe(1)
  })

  it('点折叠按钮：侧栏加 collapsed 类（宽度交给 CSS，64px 图标态）', async () => {
    seedAs(ALL_PERMISSIONS)
    const { wrapper } = await mountView(AdminLayout, { path: '/admin' })
    await waitFor(() => expect(wrapper.find('.collapse-btn').exists()).toBe(true))

    expect(wrapper.vm.collapsed).toBe(false)
    await wrapper.find('.collapse-btn').trigger('click')
    expect(wrapper.vm.collapsed).toBe(true)
    expect(wrapper.find('.admin-sidebar').classes()).toContain('collapsed')
  })
})

describe('AdminLayout · 窄屏自动折叠（移动端适配关键逻辑）', () => {
  const originalWidth = window.innerWidth
  beforeEach(() => localStorage.clear())

  afterEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: originalWidth })
  })

  it('≤768px 首次进入后台：自动折叠为图标态（不靠用户手点）', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 375 })
    seedAs(ALL_PERMISSIONS)
    const { wrapper } = await mountView(AdminLayout, { path: '/admin' })
    await waitFor(() => expect(wrapper.find('.admin-sidebar').exists()).toBe(true))
    expect(wrapper.vm.collapsed).toBe(true)
  })

  it('>768px 桌面端：默认不折叠（桌面布局保持不变）', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1366 })
    seedAs(ALL_PERMISSIONS)
    const { wrapper } = await mountView(AdminLayout, { path: '/admin' })
    await waitFor(() => expect(wrapper.find('.admin-sidebar').exists()).toBe(true))
    expect(wrapper.vm.collapsed).toBe(false)
  })
})
