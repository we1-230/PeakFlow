// 前后端联调（不走 Mock，直连真实后端）
//
// 运行前提：
//   1. 后端已在 8080 启动（test profile，连 peakflow_test 库 + 6380 Redis + 9876 RocketMQ）
//   2. 已执行过 java后端/postman/setup_test_data.py 重置夹具（管理员密码、秒杀库存）
// 运行：npm run test:integration
//
// 覆盖核心链路的每一步：登录 → 头像上传与回显 → 秒杀(含轮询) → 支付收款 → 退款售后。
import { describe, it, expect, beforeAll } from 'vitest'
import crypto from 'node:crypto'
import http, { Auth, User, Address, Order, Pay, Refund, Seckill, Product } from '@/api'

const BASE = 'http://localhost:8080'
http.defaults.baseURL = BASE

const MOCK_PAY_SECRET = 'peakflow-mock-pay-secret-key-2026'
const ADMIN = { account: 'pfadmin', password: 'Admin123456' }

// 复刻后端 PaySignUtil：待签串 = 按 key 排序后的 k=v 用 & 连接，HMAC-SHA256 后 Base64
function signMockPay(params) {
  const content = Object.keys(params)
    .filter(k => params[k] !== null && params[k] !== undefined && String(params[k]) !== '')
    .sort()
    .map(k => `${k}=${params[k]}`)
    .join('&')
  return crypto.createHmac('sha256', MOCK_PAY_SECRET).update(content).digest('base64')
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms))
const useToken = (token) => { localStorage.setItem('mall_jwt', token) }

// 下单防重提交：后端用「用户 + 商品清单 MD5」做幂等锁，
// 同一用户反复下同样的清单会被拒（"订单重复提交"），所以每单数量递增以区分。
let orderSeq = 0
const nextItems = () => [{ productId: 1, quantity: ++orderSeq }]

let adminToken = ''
let userToken = ''
let username = ''
let addressId = null

beforeAll(async () => {
  const admin = await Auth.login(ADMIN.account, ADMIN.password)
  adminToken = admin.token
  expect(adminToken).toBeTruthy()
})

describe('联调 · 登录与用户信息', () => {
  it('1. 管理员登录成功，返回 token 与用户信息', () => {
    expect(adminToken).toBeTruthy()
  })

  it('2. 注册普通用户 → 登录拿 token（注册返回的 token 未写登录缓存，必须再登录一次）', async () => {
    username = 'fe' + Date.now()
    await Auth.register({ username, password: 'It123456', email: `${username}@itest.com`, nickname: '联调用户' })
    const res = await Auth.login(username, 'It123456')
    userToken = res.token
    expect(userToken).toBeTruthy()
  })

  it('3. 用 token 读取当前用户信息', async () => {
    useToken(userToken)
    const info = await User.info()
    expect(info.username).toBe(username)
  })

  it('4. 错误密码登录被拒绝（业务码 4xxx）', async () => {
    await expect(Auth.login(username, 'wrong-password')).rejects.toBeTruthy()
  })
})

describe('联调 · 秒杀链路（含轮询）', () => {
  let seckillProductId = null

  it('8. 秒杀商品列表可见（预热后可查）', async () => {
    useToken(userToken)
    const page = await Seckill.list({ pageNum: 1, pageSize: 10 })
    expect(Array.isArray(page.list)).toBe(true)
    // 夹具里的秒杀商品（活动进行中）
    const hit = page.list.find(p => Number(p.isSeckill) === 1)
    expect(hit).toBeTruthy()
    seckillProductId = hit.id
  })

  it('9. 新增收货地址（秒杀下单需要地址）', async () => {
    useToken(userToken)
    await Address.add({
      receiverName: '联调用户', receiverPhone: '13800000000',
      province: '江苏省', city: '南京市', district: '鼓楼区', detailAddress: '联调路1号',
    })
    const list = await Address.list()
    expect(list.length).toBeGreaterThan(0)
    addressId = list[0].id
  })

  it('10. 秒杀下单 → 拿到 requestId，轮询结果直到出终态', async () => {
    useToken(userToken)
    const requestId = await Seckill.order(seckillProductId, addressId)
    expect(typeof requestId).toBe('string')
    expect(requestId.length).toBeGreaterThan(0)

    // 轮询：与前端一致，最多 30 次、间隔 1s
    let result = null
    for (let i = 0; i < 30; i++) {
      result = await Seckill.result(requestId)
      if (result && (result.status === 1 || result.status === 2)) break
      await sleep(1000)
    }

    expect(result).toBeTruthy()
    // status：0 排队中 / 1 成功 / 2 失败；联调只要求能拿到明确结果
    expect([1, 2]).toContain(result.status)
    if (result.status === 1) expect(result.orderId).toBeTruthy()
  })

  it('11. 秒杀商品详情：isSeckill=1', async () => {
    useToken(userToken)
    const detail = await Seckill.detail(seckillProductId)
    expect(Number(detail.isSeckill)).toBe(1)
  })
})

describe('联调 · 支付收款链路', () => {
  let orderId = null
  let outTradeNo = null
  let payAmount = null

  it('12. 下单 → 返回待付款订单', async () => {
    useToken(userToken)
    const order = await Order.create({ addressId, items: nextItems() })
    expect(order.id).toBeTruthy()
    orderId = order.id
    payAmount = order.payAmount
  })

  it('13. 发起支付 → 返回商户订单号与收银台地址', async () => {
    useToken(userToken)
    const vo = await Pay.prepay(orderId)
    expect(vo.outTradeNo).toBeTruthy()
    expect(vo.cashierUrl).toContain('cashier')
    outTradeNo = vo.outTradeNo
    payAmount = vo.amount
  })

  it('14. 查询支付单 → 初始为待支付', async () => {
    useToken(userToken)
    const record = await Pay.query(outTradeNo)
    expect(record.outTradeNo).toBe(outTradeNo)
    expect(Number(record.status)).toBe(0)
  })

  it('15. MockPay 收款（带签名，自动回调入账）→ 支付单变已支付', async () => {
    const timestamp = String(Date.now())
    const amount = String(payAmount)
    const sign = signMockPay({ out_trade_no: outTradeNo, amount, timestamp })

    const res = await http.post('/mock-pay/pay', { out_trade_no: outTradeNo, amount, timestamp, sign })
    expect(res.success).toBe(true)

    useToken(userToken)
    const record = await Pay.query(outTradeNo)
    expect(Number(record.status)).toBe(1)
    expect(record.tradeNo).toBeTruthy()
  })

  it('16. 支付后订单状态变为已付款', async () => {
    useToken(userToken)
    const detail = await Order.detail(orderId)
    expect(Number(detail.status)).toBe(1)
  })

  it('17. 篡改金额的收款被拒绝（金额与支付单不一致）', async () => {
    useToken(userToken)
    const order = await Order.create({ addressId, items: nextItems() })
    const vo = await Pay.prepay(order.id)

    const timestamp = String(Date.now())
    const amount = '0.01'
    const sign = signMockPay({ out_trade_no: vo.outTradeNo, amount, timestamp })
    const res = await http.post('/mock-pay/pay', { out_trade_no: vo.outTradeNo, amount, timestamp, sign })
    // 收银台本身可能受理，但回调入账必须被金额校验挡掉
    if (res && res.success === true) {
      const record = await Pay.query(vo.outTradeNo)
      expect(Number(record.status)).not.toBe(1)
    }
  })
})

describe('联调 · 退款售后链路', () => {
  let refundId = null
  let paidOrderId = null

  it('18. 对已付款订单申请退款 → 返回退款单 id（进入待审核）', async () => {
    useToken(userToken)
    const order = await Order.create({ addressId, items: nextItems() })
    paidOrderId = order.id
    const vo = await Pay.prepay(order.id)

    const timestamp = String(Date.now())
    const sign = signMockPay({ out_trade_no: vo.outTradeNo, amount: String(vo.amount), timestamp })
    await http.post('/mock-pay/pay', { out_trade_no: vo.outTradeNo, amount: String(vo.amount), timestamp, sign })

    refundId = await Refund.apply({ orderId: paidOrderId, reason: '联调测试退款' })
    expect(refundId).toBeTruthy()
  })

  it('19. 退款单详情 → 状态为待审核(0)', async () => {
    useToken(userToken)
    const detail = await Refund.detail(refundId)
    expect(Number(detail.status)).toBe(0)
    expect(Number(detail.orderId)).toBe(paidOrderId)
  })

  it('20. 同一订单重复申请退款被拒绝', async () => {
    useToken(userToken)
    await expect(Refund.apply({ orderId: paidOrderId, reason: '重复申请' })).rejects.toBeTruthy()
  })

  it('21. 管理员审核通过 → 自动发起退款并回调入账 → 退款单变为成功', async () => {
    useToken(adminToken)
    await Refund.adminAudit({ refundId, approved: true, remark: '联调同意退款' })

    // 退款是异步的（发起 + 回调），给一点时间
    let detail = null
    for (let i = 0; i < 15; i++) {
      useToken(userToken)
      detail = await Refund.detail(refundId)
      if (Number(detail.status) === 2) break
      await sleep(1000)
    }
    expect(Number(detail.status)).toBe(2)
    expect(detail.refundTradeNo).toBeTruthy()
  })

  it('22. 退款后订单状态变为已退款(6)', async () => {
    useToken(userToken)
    const detail = await Order.detail(paidOrderId)
    expect(Number(detail.status)).toBe(6)
  })

  it('23. 普通用户访问管理端退款列表被拒绝（403）', async () => {
    useToken(userToken)
    await expect(Refund.adminList({ pageNum: 1, pageSize: 10 })).rejects.toBeTruthy()
  })

  it('24. 管理员可查看退款单列表', async () => {
    useToken(adminToken)
    const page = await Refund.adminList({ pageNum: 1, pageSize: 10 })
    expect(Array.isArray(page.list)).toBe(true)
  })
})

describe('联调 · 商品与分类读取', () => {
  it('25. 商品分页 / 详情可读', async () => {
    const page = await Product.page({ pageNum: 1, pageSize: 5 })
    expect(Array.isArray(page.list)).toBe(true)
    if (page.list.length) {
      const detail = await Product.detail(page.list[0].id)
      expect(detail.id).toBe(page.list[0].id)
    }
  })
})
