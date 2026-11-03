// 工具函数与状态层单元测试
//
// 覆盖：订单领域常量（与后端状态码严格对齐）、购物车勾选 store、api 拦截器的统一错误处理。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import fs from 'node:fs'
import path from 'node:path'
import BrandMark from '@/components/BrandMark.vue'
import BrandLogo from '@/components/BrandLogo.vue'
import { server } from './setup.js'
import { stubLocation } from './helpers.js'
import {
  ORDER_STATUS, ORDER_STATUS_DESC, ORDER_TYPE, ORDER_TYPE_DESC,
  SECKILL_RESULT_STATUS, canPay, canCancel, canConfirm, canRefund,
} from '@/constants/order'
import { useCartStore } from '@/stores/cart'
import { Auth } from '@/api'

describe('订单领域常量', () => {
  it('订单状态码与后端 OrderStatus 完全一致', () => {
    expect(ORDER_STATUS).toEqual({
      WAIT_PAY: 0, PAID: 1, SHIPPED: 2, COMPLETED: 3, CANCELLED: 4, CLOSED: 5, REFUNDED: 6,
    })
    expect(ORDER_STATUS_DESC[ORDER_STATUS.WAIT_PAY]).toBe('待付款')
    expect(ORDER_STATUS_DESC[ORDER_STATUS.REFUNDED]).toBe('已退款')
  })

  it('秒杀结果状态码与后端 SeckillResultVo 一致', () => {
    expect(SECKILL_RESULT_STATUS).toEqual({ WAITING: 0, SUCCESS: 1, FAIL: 2 })
  })

  it('订单类型码与描述一致', () => {
    expect(ORDER_TYPE.NORMAL).toBe(0)
    expect(ORDER_TYPE.SECKILL).toBe(1)
    expect(ORDER_TYPE_DESC[1]).toBe('秒杀订单')
  })

  describe('业务规则：各状态允许的动作', () => {
    it('仅「待付款」可支付与取消', () => {
      expect(canPay(ORDER_STATUS.WAIT_PAY)).toBe(true)
      expect(canCancel(ORDER_STATUS.WAIT_PAY)).toBe(true)
      expect(canPay(ORDER_STATUS.PAID)).toBe(false)
      expect(canPay(ORDER_STATUS.SHIPPED)).toBe(false)
      expect(canCancel(ORDER_STATUS.CANCELLED)).toBe(false)
    })

    it('仅「已发货」可确认收货', () => {
      expect(canConfirm(ORDER_STATUS.SHIPPED)).toBe(true)
      expect(canConfirm(ORDER_STATUS.PAID)).toBe(false)
      expect(canConfirm(ORDER_STATUS.COMPLETED)).toBe(false)
    })

    it('仅「已付款」可申请退款', () => {
      expect(canRefund(ORDER_STATUS.PAID)).toBe(true)
      expect(canRefund(ORDER_STATUS.WAIT_PAY)).toBe(false)
      expect(canRefund(ORDER_STATUS.REFUNDED)).toBe(false)
    })

    it('状态入参为字符串数字时也能正确判定（后端可能返回字符串）', () => {
      expect(canPay('0')).toBe(true)
      expect(canConfirm('2')).toBe(true)
      expect(canRefund('1')).toBe(true)
    })
  })
})

describe('购物车勾选 store（前端本地维护）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('首次用后端返回的 selected 初始化，之后不再覆盖用户勾选', () => {
    const cart = useCartStore()
    cart.initFromList([
      { productId: 10, selected: 1 },
      { productId: 12, selected: 1 },
      { productId: 20, selected: 0 },
    ])
    expect(cart.selectedIds).toEqual([10, 12])
    expect(cart.selectedCount).toBe(2)

    // 用户手动取消勾选后，再次加载购物车不应把后端值盖回来
    cart.setOne(10, false)
    expect(cart.isSelected(10)).toBe(false)
    cart.initFromList([{ productId: 10, selected: 1 }])
    expect(cart.isSelected(10)).toBe(false)
  })

  it('单项勾选/取消勾选', () => {
    const cart = useCartStore()
    cart.setOne(10, true)
    expect(cart.isSelected(10)).toBe(true)
    cart.setOne(10, true) // 幂等：重复勾选不产生重复项
    expect(cart.selectedIds).toEqual([10])
    cart.setOne(10, false)
    expect(cart.isSelected(10)).toBe(false)
  })

  it('全选 / 取消全选', () => {
    const cart = useCartStore()
    cart.setAll([10, 12, 20], true)
    expect(cart.selectedCount).toBe(3)
    cart.setAll([10, 12], false)
    expect(cart.selectedIds).toEqual([20])
  })

  it('remove / clear', () => {
    const cart = useCartStore()
    cart.setAll([10, 12], true)
    cart.remove(10)
    expect(cart.selectedIds).toEqual([12])
    cart.clear()
    expect(cart.selectedCount).toBe(0)
  })
})

describe('api 拦截器', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('成功响应自动解包 data（调用方直接拿到业务数据）', async () => {
    const data = await Auth.login('zhouay', '123456')
    expect(data.token).toBeTruthy()
    expect(data.user.username).toBe('zhouay')
  })

  it('业务错误码 → 弹出后端 message 并 reject', async () => {
    await expect(Auth.login('zhouay', 'bad-password')).rejects.toMatchObject({ code: 4001 })
    expect(ElMessage.error).toHaveBeenCalledWith('账号或密码错误')
  })

  it('业务码 401 → 清登录态并跳登录页（静默，不弹错误）', async () => {
    localStorage.setItem('mall_jwt', 'stale-token')
    localStorage.setItem('mall_user', '{"id":1}')
    const loc = stubLocation('/orders')
    server.use(http.post('/api/auth/login', () =>
      HttpResponse.json({ code: 401, message: '请重新登录', data: null, timestamp: Date.now() })))

    await expect(Auth.login('zhouay', '123456')).rejects.toMatchObject({ code: 401 })

    expect(localStorage.getItem('mall_jwt')).toBeNull()
    expect(localStorage.getItem('mall_user')).toBeNull()
    expect(loc.href).toContain('/login')
    // 401 是预期内的登录态失效，不应该再弹一次错误
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('HTTP 401 → 同样清登录态并跳登录页', async () => {
    localStorage.setItem('mall_jwt', 'stale-token')
    const loc = stubLocation('/orders')
    server.use(http.post('/api/auth/login', () =>
      HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })))

    await expect(Auth.login('zhouay', '123456')).rejects.toBeTruthy()

    expect(localStorage.getItem('mall_jwt')).toBeNull()
    expect(loc.href).toContain('/login')
  })

  it('请求拦截器自动带上 Authorization: Bearer <token>', async () => {
    localStorage.setItem('mall_jwt', 'token-abc')
    let seen = null
    server.use(http.post('/api/auth/login', ({ request }) => {
      seen = request.headers.get('Authorization')
      return HttpResponse.json({ code: 200, message: 'ok', data: null, timestamp: Date.now() })
    }))

    await Auth.login('zhouay', '123456')

    expect(seen).toBe('Bearer token-abc')
  })
})

// 主题配色锁定：商城整体走「淘宝橙」氛围，防止旧蓝色主色被无意改回
describe('主题配色（商城氛围）', () => {
  // vitest 以「前端」目录为根运行，直接用 cwd 定位源码目录
  const SRC = path.join(process.cwd(), 'src')

  // 旧蓝色系（主色 + 衍生 + 浅底 + Element 默认蓝 + 对应 rgba）
  const LEGACY_BLUE = [
    '#1e4fa6', '#153c80', '#2f6bd0', '#60a5fa', '#93c5fd', '#4c7dff',
    '#409eff', '#0ea5e9', 'rgba(30,79,166', 'rgba(30, 79, 166',
  ]

  it('主色与 Element Plus 主色都是淘宝橙 #ff5000', () => {
    const css = fs.readFileSync(path.join(SRC, 'styles/taobao.css'), 'utf8').toLowerCase()
    expect(css).toContain('--primary:#ff5000')
    expect(css).toContain('--el-color-primary:#ff5000')
  })

  it('全量扫描：全部页面/组件/全局样式都不再残留旧蓝色', () => {
    const files = []
    const walk = (d) => {
      for (const e of fs.readdirSync(d, { withFileTypes: true })) {
        const p = path.join(d, e.name)
        if (e.isDirectory()) walk(p)
        else if (/\.(vue|js|css)$/.test(e.name)) files.push(p)
      }
    }
    walk(SRC)

    const hits = []
    for (const f of files) {
      const s = fs.readFileSync(f, 'utf8').toLowerCase()
      for (const c of LEGACY_BLUE) {
        if (s.includes(c)) hits.push(`${path.relative(SRC, f)} → ${c}`)
      }
    }
    expect(hits, `以下文件仍残留旧蓝色：\n${hits.join('\n')}`).toEqual([])
    // 保证扫描确实覆盖到了页面文件，不是空跑
    expect(files.length).toBeGreaterThan(20)
  })
})

// 品牌商标：全站统一为「橙色圆角方块 + 白色 A」
describe('品牌商标（统一 A 标）', () => {
  it('BrandMark 渲染白色 A，并按尺寸生成圆角方块', () => {
    const w = mount(BrandMark, { props: { size: 30 } })
    expect(w.text()).toBe('A')
    // 用 CSSStyleDeclaration 取值：比读 style 属性字符串更稳（不同 DOM 实现对属性回写不一致）
    const el = w.element
    expect(el.style.width).toBe('30px')
    expect(el.style.height).toBe('30px')
    expect(el.style.borderRadius).toBe('8px')   // round(30 * 0.28)
    expect(el.style.fontSize).toBe('16px')      // round(30 * 0.54)
    expect(String(el.style.background || el.style.backgroundImage)).toContain('linear-gradient')
  })

  it('BrandLogo = A 标 + 品牌名「全智能秒杀商城」', () => {
    const w = mount(BrandLogo, {
      props: { size: 'md' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    expect(w.find('.brand-mark').text()).toBe('A')
    expect(w.find('.brand-name').text()).toBe('全智能秒杀商城')
  })
})
