// MSW handlers：忠实还原后端当前契约
//
// 忠实原则（第一阶段测试的核心）：
//   1. 每个端点 → 按后端真实契约返回 Result 包装（code/message/data/timestamp）
//   2. 前端已按后端契约完成适配：调用不存在的端点的情况已全部修正
//      （购物车勾选改本地维护、支付改走 /pay/prepay、秒杀上下架改为只读）
//   3. 秒杀新增/更新 → 复刻后端 DTO 校验（缺 seckillPrice / seckillStock / 时间窗即业务错误）
//   4. 异常分支（业务错误码）按后端 ResultCode 精确返回，便于断言
import { http, HttpResponse } from 'msw'
import {
  USER_TOKEN, ADMIN_TOKEN, currentUser, users, categories,
  normalProducts, seckillProducts, addresses, cartItems,
  orderDetail, orderWaitPay, orderShipped, orders,
  prepayVo, payRecord, dashboardStats, pageVo,
} from './fixtures.js'

// ---------- 响应助手 ----------
const ok = (data, message = '操作成功') =>
  HttpResponse.json({ code: 200, message, data, timestamp: Date.now() })

const bizError = (message, code = 3005) =>
  HttpResponse.json({ code, message, data: null, timestamp: Date.now() })

// Spring MVC 未映射路径的默认 404（无 message 字段 → 前端提示"网络错误"）
const spring404 = () =>
  HttpResponse.json({ timestamp: Date.now(), status: 404, error: 'Not Found', path: '' }, { status: 404 })

// ---------- 可变状态（同一测试文件内跨请求保持） ----------
const cartStore = [...cartItems]
const seckillResultStore = new Map() // requestId -> SeckillResultVo

export const setSeckillResult = (rid, vo) => seckillResultStore.set(rid, vo)

// 登录风控：复刻后端行为 —— 同一账号密码失败 >=2 次后，必须带正确图形验证码才能继续登录
const LOGIN_FAIL_MAX = 2
const MOCK_CAPTCHA = 'AB12'
const loginFailStore = new Map() // account -> failCount
export const resetLoginRisk = () => loginFailStore.clear()

// 复刻后端 AddSeckillProductDto / UpdateSeckillProductDto 的必填校验（脱节 #6 的依据）
function validateSeckillProductBody(b, isUpdate) {
  if (!b.name || !String(b.name).trim()) return '商品名称不能为空'
  if (!b.image) return '商品图片不能为空'
  if (b.price == null || Number(b.price) <= 0) return '商品原价不能为空'
  if (b.seckillPrice == null || Number(b.seckillPrice) <= 0) return '秒杀价格不能为空'
  if (!isUpdate && (b.seckillStock == null || Number(b.seckillStock) < 0)) return '秒杀库存不能为空'
  if (!b.categoryId) return '商品分类ID不能为空'
  if (!isUpdate && !b.seckillStartTime) return '秒杀开始时间不能为空'
  if (!isUpdate && !b.seckillEndTime) return '秒杀结束时间不能为空'
  if (isUpdate && (!b.seckillStartTime || !b.seckillEndTime)) return '秒杀开始时间不能为空'
  return null
}

export const handlers = [
  // ==================== auth ====================
  http.post('/api/auth/login', async ({ request }) => {
    const b = await request.json()
    const failCount = loginFailStore.get(b.account) || 0
    // 风控：失败 >=2 次后，未带验证码 → 提示「请输入图形验证码」；带错 → 「验证码错误」
    if (failCount >= LOGIN_FAIL_MAX) {
      if (!b.captchaUuid || !b.captchaCode) return bizError('请输入图形验证码')
      if (b.captchaCode !== MOCK_CAPTCHA) return bizError('验证码错误')
    }
    if (b.account === 'zhouay' && b.password === '123456') {
      loginFailStore.delete(b.account)
      return ok({ token: USER_TOKEN, tokenType: 'Bearer', user: currentUser }, '登录成功')
    }
    loginFailStore.set(b.account, failCount + 1)
    return bizError('账号或密码错误', 4001)
  }),
  http.post('/api/auth/register', async ({ request }) => {
    const b = await request.json()
    if (!b.username || !b.password) return bizError('用户名或密码不能为空')
    return ok({ token: USER_TOKEN, tokenType: 'Bearer', user: { ...currentUser, username: b.username } }, '注册成功')
  }),
  http.post('/api/auth/logout', () => ok(null, '登出成功')),
  http.post('/api/auth/refresh', () => ok({ token: USER_TOKEN, tokenType: 'Bearer', user: currentUser }, '刷新成功')),
  http.post('/api/auth/changePassword', () => ok(null, '修改成功，请重新登录')),
  http.get('/api/auth/image', () => ok({ captchaUuid: 'cap-mock-1', imageBase64: 'data:image/png;base64,mock' })),

  // ==================== user ====================
  http.get('/api/user/info', () => ok(currentUser)),
  http.post('/api/user/password', () => ok(null, '密码修改成功，请重新登录')),
  http.post('/api/user/avatar', () => ok('https://oss.example.com/avatar/new.png', '上传头像成功')),
  http.post('/api/user/nickname', () => ok(null, '昵称修改成功')),
  // ⚠️ 必须与后端真实契约一致：login_log 表只有
  //    id / user_id / username / ip / login_time / remark
  //    没有 status / location / device。
  //    历史上桩数据臆造了 status:1，导致前端读 row.status 的缺陷一直测不出来
  //    （线上表现为「登录成功却显示失败」）。
  http.post('/api/user/loginLog', () => ok(pageVo([
    { id: 1, userId: 100, username: 'tester', loginTime: '2026-10-14 18:00:00', ip: '127.0.0.1', remark: '登录成功' },
  ]))),

  // ==================== admin ====================
  http.post('/api/admin/list', () => ok(pageVo(users))),
  http.post('/api/admin/ban', () => ok(null, '封号成功')),
  http.post('/api/admin/unban', () => ok(null, '解封成功')),
  http.post('/api/admin/resetPassword', () => ok('Abc123456', '密码重置成功，请尽快告知用户')),
  http.get('/api/admin/dashboard/stats', () => ok(dashboardStats)),

  // ==================== product（普通商品）====================
  http.post('/api/product/page', async ({ request }) => {
    const b = await request.json().catch(() => ({}))
    let list = normalProducts.filter(p => p.status === 1)
    if (b.categoryId) list = list.filter(p => p.categoryId === Number(b.categoryId))
    if (b.keyword) list = list.filter(p => p.name.includes(b.keyword))
    return ok(pageVo(list, b.pageNum || 1, b.pageSize || 10))
  }),
  http.get('/api/product/detail/:id', ({ params }) => {
    // 详情接口对「普通商品」和「秒杀商品」都要能返回：
    // 秒杀商品的 stock=0 / seckillStock>0，是详情页库存口径 bug 的复现数据
    const id = Number(params.id)
    const p = normalProducts.find(x => x.id === id) || seckillProducts.find(x => x.id === id)
    if (!p) return bizError('商品不存在')
    return ok(p)
  }),
  http.post('/api/product/add', () => ok(null, '新增成功')),
  http.put('/api/product/update', () => ok(null, '更新成功')),
  http.delete('/api/product/delete/:id', () => ok(null, '删除成功')),
  http.put('/api/product/status/:id', () => ok(null, '操作成功')),

  // ==================== category ====================
  http.get('/api/category/list', () => ok(categories)),
  http.post('/api/category/add', () => ok(null, '新增成功')),
  http.put('/api/category/update', () => ok(null, '更新成功')),
  http.delete('/api/category/delete/:id', () => ok(null, '删除成功')),

  // ==================== cart ====================
  http.post('/api/cart/add', async ({ request }) => {
    const url = new URL(request.url)
    const productId = Number(url.searchParams.get('productId'))
    const quantity = Number(url.searchParams.get('quantity') || 1)
    const p = [...normalProducts, ...seckillProducts].find(x => x.id === productId)
    if (!p) return bizError('商品不存在')
    // 与后端 CartServiceImpl#addToCart 一致：秒杀商品只能走秒杀通道下单，
    // 加购在服务端就会被拒绝（前端隐藏按钮只是体验层，业务规则以后端为准）
    if (Number(p.isSeckill) === 1) {
      return bizError('秒杀商品仅支持秒杀通道购买，不可加入购物车')
    }
    cartStore.push({
      productId, quantity, selected: 1,
      productName: p.name, price: p.seckillPrice ?? p.price,
      imageUrl: p.image, isSeckill: p.isSeckill,
    })
    return ok(null, '添加成功')
  }),
  // ⚠️ 这三个必须真的改桩数据，不能只回 "成功"：
  //    早期它们只返回成功码而不动 cartStore，导致「删完就空」「改数量生效」
  //    这类行为在单测里永远测不出来（与登录日志桩臆造字段同一类问题）。
  http.post('/api/cart/updateQuantity', async ({ request }) => {
    const url = new URL(request.url)
    const productId = Number(url.searchParams.get('productId'))
    const quantity = Number(url.searchParams.get('quantity') || 1)
    const hit = cartStore.find(i => i.productId === productId)
    if (!hit) return bizError('购物车中不存在该商品')
    hit.quantity = quantity
    return ok(null, '修改成功')
  }),
  http.delete('/api/cart/delete/:productId', ({ params }) => {
    const productId = Number(params.productId)
    const idx = cartStore.findIndex(i => i.productId === productId)
    if (idx >= 0) cartStore.splice(idx, 1)
    return ok(null, '删除成功')
  }),
  http.delete('/api/cart/clear', () => {
    cartStore.length = 0
    return ok(null, '清空成功')
  }),
  http.get('/api/cart/list', () => ok(cartStore)),
  // 注：后端没有修改购物车勾选的接口，前端已改为本地维护勾选态（stores/cart.js），
  //     所以这里不再需要 /cart/select、/cart/selected 的占位 handler。

  // ==================== order ====================
  http.post('/api/order/create', async ({ request }) => {
    const b = await request.json().catch(() => ({}))
    if (!b.addressId) return bizError('请先设置收货地址')
    if (!b.items || !b.items.length) return bizError('订单商品不能为空')
    return ok({ id: 1001, orderNo: 'ORD2026101400001', userId: 100, status: 0 })
  }),
  http.post('/api/order/cancel/:orderId', () => ok(null, '取消成功')),
  http.post('/api/order/refund/:orderId', () => ok(1001, '申请已提交，请等待审核')),
  http.post('/api/order/confirm/:orderId', () => ok(null, '确认收货成功')),
  http.get('/api/order/detail/:orderId', ({ params }) => {
    const id = Number(params.orderId)
    const o = orders.find(x => x.id === id) || orderDetail
    return ok(o)
  }),
  http.post('/api/order/list', () => ok(pageVo(orders))),
  http.put('/api/order/admin/update', () => ok(null, '更新成功')),
  http.post('/api/order/admin/list', () => ok(pageVo(orders))),

  // ==================== address ====================
  http.get('/api/address/list', () => ok(addresses)),
  http.get('/api/address/default', () => ok(addresses[0])),
  http.post('/api/address/add', () => ok(null, '新增成功')),
  http.put('/api/address/update', () => ok(null, '更新成功')),
  http.delete('/api/address/delete/:id', () => ok(null, '删除成功')),
  http.put('/api/address/default/:id', () => ok(null, '设置成功')),

  // ==================== upload ====================
  http.post('/api/upload/image', () => ok('https://oss.example.com/upload/new.png', '图片上传成功')),

  // ==================== pay ====================
  http.post('/api/pay/prepay', async ({ request }) => {
    const b = await request.json().catch(() => ({}))
    if (!b.orderId) return bizError('订单ID不能为空')
    return ok(prepayVo, '支付单创建成功')
  }),
  http.get('/api/pay/query/:outTradeNo', () => ok(payRecord)),
  http.post('/api/pay/notify', () => new HttpResponse('success')),

  // ==================== refund ====================
  http.post('/api/refund/apply', () => ok(1001, '申请已提交，请等待审核')),
  http.get('/api/refund/detail/:refundId', () => ok({
    id: 1001, refundNo: 'REF2026101400001', orderId: 1001, orderNo: 'ORD2026101400001',
    refundAmount: 199.00, refundType: 1, refundTypeDesc: '仅退款', refundChannel: 'MOCK',
    reason: '拍错了', status: 0, statusDesc: '待审核', auditorName: null, auditTime: null,
    auditRemark: null, failReason: null, refundTradeNo: null, applyTime: '2026-10-14 19:00:00',
    refundTime: null, canConfirmReturn: false, canCancel: true, canRetry: false, canClose: false,
  })),
  http.post('/api/refund/list', () => ok(pageVo([]))),
  http.post('/api/refund/cancel/:refundId', () => ok(null, '已撤销退款申请')),
  http.post('/api/refund/notify', () => new HttpResponse('success')),
  http.post('/api/refund/admin/list', () => ok(pageVo([]))),
  http.post('/api/refund/admin/confirm-return/:refundId', () => ok(null, '已确认收到退货')),
  http.post('/api/refund/admin/audit', () => ok(null, '审核完成')),
  http.post('/api/refund/admin/retry/:refundId', () => ok(null, '已重新发起退款')),
  http.post('/api/refund/admin/close/:refundId', () => ok(null, '退款单已作废')),

  // ==================== seckill ====================
  http.post('/api/seckill/order', () => {
    const rid = 'req-mock-001'
    if (!seckillResultStore.has(rid)) setSeckillResult(rid, { status: 0, orderId: null, orderNo: null, failReason: null })
    return ok(rid, '队伍等待中')
  }),
  http.post('/api/seckill/result/:rid', ({ params }) => {
    const r = seckillResultStore.get(params.rid)
    return ok(r || { status: 0, orderId: null, orderNo: null, failReason: null })
  }),
  http.post('/api/seckill/list', () => ok(pageVo(seckillProducts))),
  http.get('/api/seckill/detail/:id', ({ params }) => {
    const p = seckillProducts.find(x => x.id === Number(params.id))
    return p ? ok(p) : bizError('商品不存在')
  }),
  http.post('/api/seckill/add', async ({ request }) => {
    const b = await request.json()
    const err = validateSeckillProductBody(b, false)
    return err ? bizError(err) : ok(null, '新增成功')
  }),
  http.put('/api/seckill/update', async ({ request }) => {
    const b = await request.json()
    const err = validateSeckillProductBody(b, true)
    return err ? bizError(err) : ok(null, '更新成功')
  }),
  http.delete('/api/seckill/delete/:id', () => ok(null, '删除成功')),
  http.get('/api/seckill/preheat/all', () => ok(null, '预热成功')),
  // 注：后端没有秒杀商品「上下架」「单独改库存」的接口，
  //     前端已改为「状态只读展示 + 调库存走 /seckill/update 全量更新」，
  //     所以这里不再需要 /seckill/status、/seckill/stock 的占位 handler。
]
