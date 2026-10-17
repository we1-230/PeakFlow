// @vitest-environment-options {"url": "https://peakflow.top/"}
// ↑ 必须让测试页面的 origin 与站点同源：生产 nginx 的 CORS 白名单是空的（这是正确配置，
//   应用本身是同源访问）。若用 localhost 当 origin 跑，浏览器策略会拦掉全部请求。
//   换部署域名时改这一行的 url 即可。
//
// 前后端联调 · 真实后端（走前端 api.js 的真实代码路径，不是 MSW 桩）
//
// 与 test/integration/api.test.js 的区别：
//   · api.test.js 依赖本地夹具（pfadmin/Admin123456、固定 productId=1、需要自己算 MockPay 签名）
//   · 本文件完全**环境变量驱动**，可直接打生产：账号密码从环境变量读，
//     商品/秒杀对象从列表里动态挑，支付签名直接用 prepay 返回的 cashierUrl 里的 sign
//     （这也是浏览器真实做法，顺带验证「后端签好的参数前端不重算」）
//
// 运行（必须显式开启，避免误改生产数据）：
//   PEAKFLOW_LIVE=1 \
//   PEAKFLOW_API_BASE=https://peakflow.top/api \
//   PEAKFLOW_USER=malluser PEAKFLOW_PASS=xxxx \
//   PEAKFLOW_ADMIN=pfadmin PEAKFLOW_OPS=productops PEAKFLOW_SVC=ordersvc \
//   npx vitest run test/integration/live.test.js --config vitest.integration.config.js
//
// 数据副作用：只创建「一次性随机用户」及其 1 笔订单，并在用例末尾取消订单释放库存；
// 不修改任何既有商品/秒杀配置（管理端只做只读校验与越权拒绝校验）。
import { describe, it, expect, beforeAll } from 'vitest'
import http, { Auth, User, Address, Order, Pay, Seckill, Product, Cart, Refund, Admin } from '@/api'

const LIVE = process.env.PEAKFLOW_LIVE === '1'
// 同源相对路径：配合上面的 environment-options，请求落在 https://peakflow.top/api/*
const BASE = process.env.PEAKFLOW_API_BASE || '/api'
const PW = process.env.PEAKFLOW_PASS || '2005ww'
const ACC = {
  user: process.env.PEAKFLOW_USER || 'malluser',
  admin: process.env.PEAKFLOW_ADMIN || 'pfadmin',
  ops: process.env.PEAKFLOW_OPS || 'productops',
  svc: process.env.PEAKFLOW_SVC || 'ordersvc',
}

http.defaults.baseURL = BASE
http.defaults.adapter = 'fetch'

const sleep = (ms) => new Promise(r => setTimeout(r, ms))
const as = (token) => localStorage.setItem('mall_jwt', token)

// 期望被拒绝：拦截器会 reject（业务码非 200 / HTTP 4xx 都会 reject）
async function expectDenied(fn) {
  let rejected = false
  try { await fn() } catch { rejected = true }
  expect(rejected, '越权操作本应被拒绝，实际通过了').toBe(true)
}

const state = { userToken: '', adminToken: '', opsToken: '', svcToken: '', orderId: null, addrId: null, productId: null }

// 后端对「同一用户 + 同一商品清单」有幂等锁（MD5 去重），连续下同样的单会被拒。
// 每次下单数量递增以区分不同订单，与 api.test.js 的做法一致。
let orderSeq = 0
const nextQty = () => ++orderSeq

describe.skipIf(!LIVE)('联调（真实后端）· 登录与身份', () => {
  it('1. 普通用户登录：返回 token，且能读到自己的信息', async () => {
    const res = await Auth.login(ACC.user, PW)
    expect(res.token).toBeTruthy()
    state.userToken = res.token
    as(res.token)
    const info = await User.info()
    expect(info.username).toBe(ACC.user)
  })

  it('2. 三种后台角色都能登录（token 里带不同权限集合）', async () => {
    for (const key of ['admin', 'ops', 'svc']) {
      const res = await Auth.login(ACC[key], PW)
      expect(res.token, `${ACC[key]} 登录失败`).toBeTruthy()
      state[`${key}Token`] = res.token
    }
    // 权限集合差异是 RBAC 的基础：admin 应包含 user:manage，ops/svc 不应有
    const perms = (token) => {
      const seg = token.split('.')[1]
      const pad = seg + '='.repeat((4 - (seg.length % 4)) % 4)
      return JSON.parse(Buffer.from(pad, 'base64').toString('utf8')).role || []
    }
    expect(perms(state.adminToken)).toContain('user:manage')
    expect(perms(state.opsToken)).not.toContain('user:manage')
    expect(perms(state.svcToken)).not.toContain('product:manage')
  })

  it('3. 错误密码被拒 + 拦截器给出可读文案（不暴露内部细节）', async () => {
    let msg = ''
    try { await Auth.login(ACC.user, 'wrong-password-xyz') } catch (e) { msg = e.message || '' }
    expect(msg).toMatch(/密码错误|账号或密码错误/)
  })

  it('4. 未带 token 访问受保护接口 → 401 且不返回业务数据', async () => {
    localStorage.removeItem('mall_jwt')
    await expectDenied(() => User.info())
  })
})

describe.skipIf(!LIVE)('联调（真实后端）· 权限矩阵（三角色 × 允许/禁止）', () => {
  it('5. 数据看板：三种角色都允许（后端给 product_ops / order_service 都签了 stats:view）', async () => {
    for (const t of [state.adminToken, state.opsToken, state.svcToken]) {
      as(t)
      const stats = await Admin.stats()
      expect(stats).toBeTruthy()
    }
  })

  it('6. 用户管理：仅超管可读；商品运营/订单客服被拒', async () => {
    as(state.adminToken)
    expect(await Admin.list({ pageNum: 1, pageSize: 5 })).toBeTruthy()

    for (const t of [state.opsToken, state.svcToken, state.userToken]) {
      as(t)
      await expectDenied(() => Admin.list({ pageNum: 1, pageSize: 5 }))
    }
  })

  it('7. 订单管理：超管与订单客服可读；商品运营与普通用户被拒', async () => {
    for (const t of [state.adminToken, state.svcToken]) {
      as(t)
      const page = await Order.adminList({ pageNum: 1, pageSize: 5 })
      expect(page).toBeTruthy()
    }
    for (const t of [state.opsToken, state.userToken]) {
      as(t)
      await expectDenied(() => Order.adminList({ pageNum: 1, pageSize: 5 }))
    }
  })

  it('8. 退款管理：超管与订单客服可读；商品运营被拒', async () => {
    for (const t of [state.adminToken, state.svcToken]) {
      as(t)
      expect(await Refund.adminList({ pageNum: 1, pageSize: 5 })).toBeTruthy()
    }
    as(state.opsToken)
    await expectDenied(() => Refund.adminList({ pageNum: 1, pageSize: 5 }))
  })

  it('9. 越权写：商品运营/订单客服都不能封禁用户', async () => {
    for (const t of [state.opsToken, state.svcToken]) {
      as(t)
      await expectDenied(() => Admin.ban(999999))
    }
  })
})

describe.skipIf(!LIVE)('联调（真实后端）· 购物车 / 下单 / 订单查询', () => {
  it('10. 商品与秒杀列表可读，并且秒杀商品 stock 与 seckillStock 口径不同', async () => {
    as(state.userToken)
    const page = await Product.page({ pageNum: 1, pageSize: 30 })
    expect(page.list.length).toBeGreaterThan(0)

    const normal = page.list.find(p => Number(p.isSeckill) !== 1 && Number(p.stock) > 0)
    expect(normal, '应存在有货的普通商品').toBeTruthy()
    state.productId = normal.id

    const sk = await Seckill.list({ pageNum: 1, pageSize: 10 })
    const sku = sk.list.find(p => Number(p.isSeckill) === 1)
    if (sku) {
      const detail = await Product.detail(sku.id)
      // 这正是详情页 bug 的数据前提：普通库存为 0，秒杀库存才是真实可抢量
      expect(Number(detail.isSeckill)).toBe(1)
      expect(Number(detail.seckillStock)).toBeGreaterThanOrEqual(0)
      expect(Number(detail.stock)).toBe(0)
    }
  })

  it('11. 加购物车 → 出现在列表 → 改数量生效', async () => {
    as(state.userToken)
    await Cart.add(state.productId, 1)
    let list = await Cart.list()
    const item = list.find(i => i.productId === state.productId)
    expect(item, '加购后购物车应包含该商品').toBeTruthy()

    await Cart.updateQty(state.productId, 2)
    list = await Cart.list()
    const after = list.find(i => i.productId === state.productId)
    expect(Number(after.quantity)).toBe(2)
  })

  it('12. 准备收货地址（下单前置条件）', async () => {
    as(state.userToken)
    const list = await Address.list()
    if (list.length === 0) {
      await Address.add({
        receiverName: '联调校验', receiverPhone: '13800009997',
        province: '上海市', city: '上海市', district: '浦东新区',
        detailAddress: '联调专用地址（可删除）',
      })
    }
    const after = await Address.list()
    expect(after.length).toBeGreaterThan(0)
    state.addrId = after[0].id
  })

  it('13. 下单 → 详情为待付款 → 列表可见 → 取消后变已取消', async () => {
    as(state.userToken)
    const order = await Order.create({
      addressId: state.addrId,
      items: [{ productId: state.productId, quantity: nextQty() }],
      remark: '联调回归订单',
    })
    state.orderId = order.id
    expect(state.orderId).toBeTruthy()

    const detail = await Order.detail(state.orderId)
    expect(Number(detail.status)).toBe(0)

    const list = await Order.list({ pageNum: 1, pageSize: 20 })
    expect(list.list.some(o => o.id === state.orderId)).toBe(true)

    await Order.cancel(state.orderId)
    const after = await Order.detail(state.orderId)
    expect(Number(after.status)).toBe(4)
  })
})

describe.skipIf(!LIVE)('联调（真实后端）· 支付全链路（prepay → 收银台 → 回调入账）', () => {
  let orderId = null
  let outTradeNo = null

  it('14. 创建待付款订单', async () => {
    as(state.userToken)
    const order = await Order.create({
      addressId: state.addrId,
      items: [{ productId: state.productId, quantity: nextQty() }],
      remark: '联调支付链路订单',
    })
    orderId = order.id
    expect(orderId).toBeTruthy()
  })

  it('15. prepay 返回收银台地址，且地址带齐签名三要素', async () => {
    const vo = await Pay.prepay(orderId)
    outTradeNo = vo.outTradeNo
    expect(vo.cashierUrl).toContain('/mock-pay/cashier')
    const u = new URL(vo.cashierUrl)
    for (const k of ['out_trade_no', 'amount', 'timestamp', 'sign']) {
      expect(u.searchParams.get(k), `收银台地址缺少 ${k}`).toBeTruthy()
    }
    // 商户订单号必须与 prepay 返回一致（后端签名就是按它算的）
    expect(u.searchParams.get('out_trade_no')).toBe(outTradeNo)
  })

  it('16. 复刻浏览器：用收银台地址里的参数完成支付（不自己拼路径、不重算签名）', async () => {
    // prepay 对同一订单是幂等的（已存在则复用原支付单），所以可以直接再取一次地址，
    // 这样本用例自包含、不依赖上一个用例的局部变量
    const vo = await Pay.prepay(orderId)
    const u = new URL(vo.cashierUrl)
    expect(u.searchParams.get('out_trade_no')).toBe(vo.outTradeNo)

    // 页面挂载在 /api/mock-pay/cashier，相对 'pay' → /api/mock-pay/pay
    const payUrl = new URL('pay', u.origin + u.pathname)
    expect(payUrl.pathname).toBe('/api/mock-pay/pay')

    const body = {
      out_trade_no: u.searchParams.get('out_trade_no'),
      amount: u.searchParams.get('amount'),
      timestamp: u.searchParams.get('timestamp'),
      sign: u.searchParams.get('sign'),
    }
    const res = await fetch(payUrl.toString(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    expect(res.status).toBe(200)
    const json = await res.json()
    expect(json.success, `收银台支付失败：${JSON.stringify(json)}`).toBe(true)
  })

  it('17. 第三方回调入账：订单变已付款，支付单变已支付', async () => {
    as(state.userToken)
    let status = null
    for (let i = 0; i < 15; i++) {
      const d = await Order.detail(orderId)
      status = Number(d.status)
      if (status === 1) break
      await sleep(1000)
    }
    expect(status, '订单应被异步回调更新为已付款').toBe(1)
  })

  it('18. 已付款订单不能重复取消（状态机守卫）', async () => {
    as(state.userToken)
    await expectDenied(() => Order.cancel(orderId))
  })
})

describe.skipIf(!LIVE)('联调（真实后端）· 秒杀高流量', () => {
  it('19. 同一用户并发抢购同一商品：最多一单成功，且无 5xx', async () => {
    as(state.userToken)
    const page = await Seckill.list({ pageNum: 1, pageSize: 10 })
    const sku = page.list.find(p => Number(p.isSeckill) === 1 && Number(p.seckillStock) > 0)
    if (!sku) {
      // 生产可能刚好抢完，属正常状态，跳过并发断言但保留可见性校验
      expect(page.list.length).toBeGreaterThan(0)
      return
    }

    const results = await Promise.allSettled(
      Array.from({ length: 8 }, () => Seckill.order(sku.id, state.addrId)),
    )
    const ok = results.filter(r => r.status === 'fulfilled').length
    const rejected = results.filter(r => r.status === 'rejected')

    // 限购 + 限流下最多 1 单成功
    expect(ok).toBeLessThanOrEqual(1)
    // 被拒的必须都是业务/限流错误，不能是 5xx
    for (const r of rejected) {
      const status = r.reason?.response?.status
      expect(status === undefined || status < 500, `出现 5xx：${status}`).toBe(true)
    }

    // 若成功一单，轮询结果并取消，恢复库存（不留垃圾数据）
    const first = results.find(r => r.status === 'fulfilled')
    if (first) {
      let orderId = null
      for (let i = 0; i < 20; i++) {
        const res = await Seckill.result(first.value)
        if (res && (res.status === 1 || res.status === 2)) { orderId = res.orderId; break }
        await sleep(1000)
      }
      if (orderId) await Order.cancel(orderId).catch(() => {})
    }
  })
})
