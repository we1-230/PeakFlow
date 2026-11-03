// 种子数据：与后端契约逐字段对齐（来源：PeakFlow-前后端接口文档.md）
// 所有金额单位为元（BigDecimal 序列化为数字），时间格式 yyyy-MM-dd HH:mm:ss

const b64url = (obj) =>
  Buffer.from(JSON.stringify(obj)).toString('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

// 构造带 role 权限数组的 JWT（auth store 只解 payload 的 role 字段做前端展示）
export function makeJwt(roles, userId = 100) {
  const header = b64url({ alg: 'HS256', typ: 'JWT' })
  const payload = b64url({ sub: String(userId), role: roles, exp: 9999999999 })
  return `${header}.${payload}.mock-signature`
}

// 与后端 permission 表逐字对齐的 11 个权限码（注意：后端不存在 'admin:manage'，
// 前端旧代码曾按它判定管理员，导致任何账号都进不了后台，已改为按下列真实权限码判定）
export const ALL_PERMISSIONS = [
  'user:manage', 'stats:view', 'product:manage', 'category:manage',
  'order:ship', 'order:read', 'file:upload', 'seckill:manage',
  'refund:read', 'refund:audit', 'refund:close',
]

export const USER_TOKEN = makeJwt([], 100)
export const ADMIN_TOKEN = makeJwt(ALL_PERMISSIONS, 1)

// ==================== 用户 ====================
export const currentUser = {
  id: 100, username: 'zhouay', nickname: '周奥宇', email: 'zhou@example.com',
  phone: '13800138000', avatar: 'https://oss.example.com/avatar/zhou.png', status: 1,
}

export const users = [
  { id: 100, username: 'zhouay', nickname: '周奥宇', email: 'zhou@example.com', phone: '13800138000', avatar: '', status: 1, createTime: '2026-09-01 10:00:00' },
  { id: 101, username: 'testuser', nickname: '测试用户', email: 'test@example.com', phone: '13900139000', avatar: '', status: 1, createTime: '2026-09-10 12:00:00' },
  { id: 102, username: 'banneduser', nickname: '被封用户', email: 'ban@example.com', phone: '13700137000', avatar: '', status: 0, createTime: '2026-09-15 08:00:00' },
]

// ==================== 分类 ====================
export const categories = [
  { id: 1, name: '数码电子', sort: 1, status: 1 },
  { id: 2, name: '家居生活', sort: 2, status: 1 },
  { id: 3, name: '食品饮料', sort: 3, status: 1 },
]

// ==================== 商品 ====================
export const normalProducts = [
  { id: 10, name: '无线蓝牙耳机', description: '降噪长续航', detail: '<p>商品详情</p>', image: 'https://oss.example.com/p10.jpg', price: 199.00, stock: 50, soldCount: 120, categoryId: 1, status: 1, version: 0, isSeckill: 0 },
  { id: 11, name: '机械键盘', description: '青轴 87 键', detail: '<p>商品详情</p>', image: 'https://oss.example.com/p11.jpg', price: 329.00, stock: 20, soldCount: 45, categoryId: 1, status: 1, version: 0, isSeckill: 0 },
  { id: 12, name: '保温杯', description: '316 不锈钢', detail: '<p>商品详情</p>', image: 'https://oss.example.com/p12.jpg', price: 59.00, stock: 100, soldCount: 300, categoryId: 2, status: 1, version: 0, isSeckill: 0 },
]

export const seckillProducts = [
  {
    id: 20, name: '秒杀款耳机', description: '限时五折', detail: '<p>秒杀商品</p>',
    image: 'https://oss.example.com/s20.jpg', price: 199.00, seckillPrice: 99.00,
    stock: 0, seckillStock: 8, soldCount: 32, categoryId: 1, status: 1, version: 0, isSeckill: 1,
    seckillStartTime: '2026-10-14 00:00:00', seckillEndTime: '2026-12-31 23:59:59',
  },
  {
    id: 21, name: '秒杀款键盘', description: '限量抢购', detail: '<p>秒杀商品</p>',
    image: 'https://oss.example.com/s21.jpg', price: 329.00, seckillPrice: 169.00,
    stock: 0, seckillStock: 3, soldCount: 57, categoryId: 1, status: 1, version: 0, isSeckill: 1,
    seckillStartTime: '2026-10-14 00:00:00', seckillEndTime: '2026-12-31 23:59:59',
  },
]

// ==================== 地址 ====================
export const addresses = [
  { id: 50, userId: 100, receiverName: '周奥宇', receiverPhone: '13800138000', province: '江苏省', city: '南京市', district: '栖霞区', detailAddress: '仙林大道 100 号', isDefault: 1 },
  { id: 51, userId: 100, receiverName: '周奥宇', receiverPhone: '13800138000', province: '江苏省', city: '南京市', district: '鼓楼区', detailAddress: '中山北路 200 号', isDefault: 0 },
]

// ==================== 购物车 ====================
export const cartItems = [
  { productId: 10, quantity: 2, selected: 1, productName: '无线蓝牙耳机', price: 199.00, imageUrl: 'https://oss.example.com/p10.jpg', isSeckill: 0 },
  { productId: 12, quantity: 1, selected: 1, productName: '保温杯', price: 59.00, imageUrl: 'https://oss.example.com/p12.jpg', isSeckill: 0 },
  { productId: 20, quantity: 1, selected: 0, productName: '秒杀款耳机', price: 99.00, imageUrl: 'https://oss.example.com/s20.jpg', isSeckill: 1 },
]

// ==================== 订单 ====================
const orderItem = (p) => ({
  productId: p.id, productName: p.name, productImage: p.image,
  productPrice: p.price, quantity: 1, subtotal: p.price,
})

export const orderDetail = {
  id: 1001, orderNo: 'ORD2026101400001', userId: 100, addressId: 50,
  address: { receiverName: '周奥宇', receiverPhone: '138****8000', fullAddress: '江苏省 南京市 栖霞区 仙林大道 100 号' },
  totalAmount: 199.00, payAmount: 199.00,
  status: 1, statusDesc: '已付款', orderType: 0,
  payTime: '2026-10-14 18:30:00', createTime: '2026-10-14 18:20:00',
  trackingNo: null, logisticsCompany: null, remark: null, timeoutRemaining: null,
  items: [orderItem(normalProducts[0])],
  refundId: null, refundNo: null, refundStatus: null, refundStatusDesc: null, refundProcessing: null,
}

export const orderWaitPay = { ...orderDetail, id: 1002, status: 0, statusDesc: '待付款', payTime: null, timeoutRemaining: 1800 }
export const orderShipped = { ...orderDetail, id: 1003, status: 2, statusDesc: '已发货', trackingNo: 'SF1234567890', logisticsCompany: '顺丰速运', timeoutRemaining: null }

export const orders = [orderDetail, orderWaitPay, orderShipped]

// ==================== 支付 ====================
export const prepayVo = {
  outTradeNo: 'PAYORD2026101400001-1760433000000',
  orderNo: 'ORD2026101400001',
  amount: 199.00,
  payChannel: 'MOCK',
  cashierUrl: 'http://localhost:8080/mockpay/cashier?out_trade_no=PAYORD2026101400001-1760433000000&amount=199.00&timestamp=1760433000000&sign=mock',
}

export const payRecord = {
  outTradeNo: prepayVo.outTradeNo, orderId: 1001, orderNo: 'ORD2026101400001',
  amount: 199.00, payChannel: 'MOCK', status: 1, tradeNo: 'TRADE20261014001',
  payTime: '2026-10-14 18:30:00', version: 1,
}

// ==================== 仪表盘 ====================
export const dashboardStats = {
  userTotal: 128, userActive: 120, userBanned: 8,
  productTotal: 56, seckillTotal: 6, categoryTotal: 12,
  orderTotal: 1024, gmv: 888666.50, todayOrders: 32, todayGmv: 12345.60,
  orderStatusDist: [
    { status: 0, statusDesc: '待付款', count: 15 },
    { status: 1, statusDesc: '已付款', count: 42 },
    { status: 2, statusDesc: '已发货', count: 18 },
    { status: 3, statusDesc: '已完成', count: 900 },
  ],
  trend7: [
    { date: '2026-10-08', count: 120, amount: 9999.99 },
    { date: '2026-10-09', count: 98, amount: 8765.43 },
  ],
}

// ==================== 分页工具 ====================
export function pageVo(list, pageNum = 1, pageSize = 10) {
  const total = list.length
  const pages = Math.max(1, Math.ceil(total / pageSize))
  const start = (pageNum - 1) * pageSize
  return { list: list.slice(start, start + pageSize), total, pages, pageNum, pageSize }
}
