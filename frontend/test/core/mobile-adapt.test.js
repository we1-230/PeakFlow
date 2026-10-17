// 移动端适配 · 关键逻辑与「不影响桌面端」的结构性守卫
//
// 本文件的重点是**约束**而不是像素：
//   1. mobile.css 的所有覆盖必须写在 @media (max-width: 768px) 里 —— 一旦有人把规则
//      挪到媒体查询外面，桌面端就会被动变化，这条断言会立刻红。
//   2. 涉及 scoped 样式的覆盖必须带 !important —— scoped 特异性 0,2,0 高于全局单类，
//      漏了 !important 手机端改了没效果（详情页 420px 图片溢出就是这么来的）。
//   3. 需要按屏宽切换**结构**的地方（订单信息列数、后台侧栏）用 JS 判定，
//      并保证桌面宽度下行为与改造前一致。
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import { makeJwt, ALL_PERMISSIONS } from '../fixtures.js'
import OrderDetail from '@/views/mall/OrderDetail.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'

const CSS = readFileSync(path.resolve(process.cwd(), 'src/styles/mobile.css'), 'utf-8')
const MOBILE_MAX = 768

/** 去掉注释，便于结构分析（注释里出现的关键词不算规则） */
const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '')

describe('mobile.css · 只做 ≤768px 的增量覆盖', () => {
  const clean = stripComments(CSS)

  it('整份文件只有一个 @media 块，断点为 max-width: 768px', () => {
    const mediaBlocks = clean.match(/@media[^{]*\{/g) || []
    expect(mediaBlocks.length).toBe(1)
    expect(mediaBlocks[0]).toMatch(new RegExp(`max-width:\\s*${MOBILE_MAX}px`))
  })

  it('媒体查询之外没有任何样式规则（否则会污染桌面端）', () => {
    const open = clean.indexOf('{', clean.indexOf('@media'))
    // 从 @media 的左花括号开始按层级找到它的闭合位置
    let depth = 0
    let end = -1
    for (let i = open; i < clean.length; i++) {
      if (clean[i] === '{') depth++
      else if (clean[i] === '}') { depth--; if (depth === 0) { end = i; break } }
    }
    expect(end).toBeGreaterThan(0)
    const outside = clean.slice(0, clean.indexOf('@media')) + clean.slice(end + 1)
    expect(outside.trim(), `媒体查询外不应有规则：\n${outside.trim()}`).toBe('')
  })

  it('覆盖了各个核心页面/布局的关键选择器', () => {
    // 头部/导航、商品网格、详情、购物车、结算、订单、登录、后台
    const required = [
      '.mall-header-inner', '.mall-search', '.mall-cart-btn',
      '.product-grid', '.detail-card', '.large-img-wrap',
      '.cart-table', '.checkout-footer', '.item-row',
      '.auth-card', '.login-card', '.admin-content', '.category-sidebar',
    ]
    for (const sel of required) {
      expect(clean, `mobile.css 缺少选择器：${sel}`).toContain(sel)
    }
  })

  it('覆盖 scoped 样式的规则都带 !important（否则特异性不足、改了不生效）', () => {
    // 这些选择器在视图里是 <style scoped>，编译后带 [data-v-xxx]，特异性 0,2,0
    const scopedSelectors = [
      '.detail-card', '.detail-image', '.large-img-wrap',
      '.category-sidebar', '.checkout-footer', '.auth-card', '.login-card',
    ]
    for (const sel of scopedSelectors) {
      // 抓该选择器所在的声明块，断言块内至少有一个 !important
      const re = new RegExp(`\\${sel}[^{]*\\{([^}]*)\\}`, 'g')
      const blocks = [...clean.matchAll(re)].map(m => m[1])
      expect(blocks.length, `未找到 ${sel} 的规则`).toBeGreaterThan(0)
      const all = blocks.join(' ')
      expect(all, `${sel} 的覆盖缺少 !important`).toContain('!important')
    }
  })

  it('商品网格在手机端为 2 列（来自 taobao.css 既有断点，不重复定义冲突值）', () => {
    const taobao = readFileSync(path.resolve(process.cwd(), 'src/styles/taobao.css'), 'utf-8')
    expect(taobao).toMatch(/@media\(max-width:768px\)\{\s*\.product-grid\s*\{\s*grid-template-columns:repeat\(2,1fr\)/)
    // mobile.css 只调整间距，不应再改写列数（避免两处冲突）
    expect(clean).not.toMatch(/\.product-grid\s*\{[^}]*grid-template-columns/)
  })
})

describe('响应式行为 · 订单详情信息列数', () => {
  const originalWidth = window.innerWidth
  afterEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: originalWidth })
  })

  beforeEach(() => {
    localStorage.clear()
    seedLogin()
  })

  it('手机宽度（375）：订单信息表降为单列', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 375 })
    const { wrapper } = await mountView(OrderDetail, { path: '/order/1001' })
    await waitFor(() => expect(wrapper.vm.order).toBeTruthy())
    expect(wrapper.vm.isMobile).toBe(true)
    // el-descriptions 的 column 走 props，桌面/手机两套值
    const desc = wrapper.findComponent({ name: 'ElDescriptions' })
    if (desc.exists()) expect(desc.props('column')).toBe(1)
  })

  it('桌面宽度（1366）：仍是两列（保持原布局）', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1366 })
    const { wrapper } = await mountView(OrderDetail, { path: '/order/1001' })
    await waitFor(() => expect(wrapper.vm.order).toBeTruthy())
    expect(wrapper.vm.isMobile).toBe(false)
    const desc = wrapper.findComponent({ name: 'ElDescriptions' })
    if (desc.exists()) expect(desc.props('column')).toBe(2)
  })
})

describe('响应式行为 · 后台侧栏折叠阈值', () => {
  const originalWidth = window.innerWidth
  afterEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: originalWidth })
  })

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('mall_jwt', makeJwt(ALL_PERMISSIONS, 1))
    localStorage.setItem('mall_user', JSON.stringify({ id: 1, username: 'admin', nickname: '管理员' }))
  })

  it('768px 恰好算手机端（边界闭区间）：自动折叠', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 768 })
    const { wrapper } = await mountView(AdminLayout, { path: '/admin' })
    await waitFor(() => expect(wrapper.find('.admin-sidebar').exists()).toBe(true))
    expect(wrapper.vm.collapsed).toBe(true)
  })

  it('769px 起算桌面端：不折叠（阈值不能误伤小尺寸笔记本）', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 769 })
    const { wrapper } = await mountView(AdminLayout, { path: '/admin' })
    await waitFor(() => expect(wrapper.find('.admin-sidebar').exists()).toBe(true))
    expect(wrapper.vm.collapsed).toBe(false)
  })
})
