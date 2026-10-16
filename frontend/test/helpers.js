// 视图测试公共助手：统一补齐 vue-router（内存路由）+ pinia + Element Plus + 图标，
// 与 src/main.js 的装配保持一致，避免每个用例重复样板代码。
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import { vi, afterEach } from 'vitest'
import { makeJwt, USER_TOKEN, ADMIN_TOKEN, currentUser } from './fixtures.js'

// 视图里可能跳转到的全部路由，用空组件占位即可（只关心跳转目标）
const ROUTES = [
  '/', '/login', '/products', '/product/:id', '/seckill', '/cart', '/checkout',
  '/orders', '/order/:id', '/address', '/user',
  '/admin', '/admin/login', '/admin/users', '/admin/products',
  '/admin/categories', '/admin/seckill', '/admin/orders', '/admin/refunds',
]

const placeholder = { template: '<div />' }

export async function mountView(Component, { path = '/', query = {}, props = {} } = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ROUTES.map(p => ({ path: p, component: placeholder })),
  })
  const pinia = createPinia()

  router.push({ path, query })
  await router.isReady()

  const wrapper = mount(Component, {
    props,
    global: {
      plugins: [router, pinia, ElementPlus],
      stubs: {
        // 弹窗默认 Teleport 到 body，测试里就地渲染，便于用 wrapper 查询
        teleport: true,
        // el-pagination 内置的“每页条数”下拉、以及工具栏里的 el-select / el-date-picker，
        // 在 happy-dom 下都会因 popper 定位反复触发自身更新（Maximum recursive updates）。
        // 它们属于筛选控件、不属于业务逻辑，统一桩掉以保证测试输出干净。
        'el-pagination': true,
        'el-select': true,
        'el-option': true,
        'el-date-picker': true,
      },
      components: { ...ElementPlusIconsVue },
    },
  })

  return { wrapper, router, pinia }
}

// 直接给 localStorage 塞登录态（登录链路用例之外的场景用）
export function seedLogin({ admin = false, user = currentUser } = {}) {
  const token = admin ? ADMIN_TOKEN : USER_TOKEN
  localStorage.setItem('mall_jwt', token)
  localStorage.setItem('mall_user', JSON.stringify(user))
  return token
}

// 等待条件成立（MSW 有真实网络往返，需要轮询等待响应式更新落定）
export function waitFor(fn, options = {}) {
  return vi.waitFor(fn, { timeout: 3000, interval: 20, ...options })
}

// 替换 window.location：支付会跳到第三方收银台，测试里只需要捕获跳转地址。
// ⚠️ 初始 href 必须是合法的绝对地址——axios 的 baseURL 是相对路径（/api），
// 靠 location 解析；给空字符串会让 XHR 解析失败，接口全部打不通。
const REAL_LOCATION = globalThis.window?.location

export function stubLocation(pathname = '/') {
  const state = { href: 'http://localhost:3000' + pathname }
  const stub = {
    pathname,
    search: '',
    hash: '',
    assign(v) { state.href = v },
    replace(v) { state.href = v },
    reload() {},
    toString() { return state.href },
  }
  Object.defineProperty(stub, 'href', {
    get: () => state.href,
    set: (v) => { state.href = v },
    enumerable: true,
    configurable: true,
  })
  Object.defineProperty(window, 'location', { configurable: true, writable: true, value: stub })
  return stub
}

// 每个用例结束后还原真实 location：拦截器会把 href 改成相对路径（/login），
// 不还原的话下一个用例里 axios 解析相对 URL 会抛 "Invalid URL"
afterEach(() => {
  if (REAL_LOCATION && globalThis.window) {
    Object.defineProperty(window, 'location', { configurable: true, writable: true, value: REAL_LOCATION })
  }
})

export { makeJwt, USER_TOKEN, ADMIN_TOKEN, currentUser }
