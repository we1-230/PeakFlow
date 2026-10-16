import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({ baseURL: '/api', timeout: 30000 })

// 请求拦截：自动注入 JWT
http.interceptors.request.use(cfg => {
  const token = localStorage.getItem('mall_jwt')
  if (token) cfg.headers.Authorization = 'Bearer ' + token
  return cfg
})

// ---------------------------------------------------------------------------
// HTTP 层错误分类（导出便于单元测试）
// 目标：杜绝「网络异常」这类模糊提示，把错误分成用户能理解的四类——
//   network    网络错误：请求根本没到达服务器（断网 / DNS / 超时 / 被代理拦截）
//   permission 权限错误：401 未登录或登录过期、403 已登录但无权限
//   server     接口错误：4xx 参数/资源问题、5xx 服务器内部错误
//   format     数据格式错误：HTTP 200 但响应体不是预期的 JSON 结构
// 401 的跳转逻辑不在这里处理，由拦截器负责（要区分商城/后台登录页）。
// ---------------------------------------------------------------------------
export function classifyHttpError(err) {
  if (!err.response) {
    return { type: 'network', message: '网络连接失败，请检查网络后重试' }
  }
  const { status, data } = err.response
  if (status === 401) {
    return { type: 'permission', message: '登录已过期，请重新登录' }
  }
  if (status === 403) {
    return { type: 'permission', message: data?.message || '没有操作权限' }
  }
  if (status === 404) {
    return { type: 'server', message: '请求的接口不存在' }
  }
  // 4xx：后端 GlobalExceptionHandler 通常带业务文案（如参数校验错误），优先透传
  if (status < 500) {
    return { type: 'server', message: data?.message || data?.msg || '请求参数有误' }
  }
  return { type: 'server', message: '服务器开小差了，请稍后重试' }
}

// 响应拦截：统一错误处理
http.interceptors.response.use(
  res => {
    const d = res.data
    // 数据格式错误：后端返回了 200 但响应体不是 JSON 对象（如被网关/错误页拦截
    // 返回 HTML，或 Content-Type 漂移）。axios 会把非 JSON 解析成字符串。
    if (typeof d === 'string') {
      ElMessage.error('数据格式异常，请稍后重试')
      return Promise.reject({ type: 'format', message: '数据格式异常', __classified: true })
    }
    if (d.code !== undefined && d.code !== 200) {
      // 登录态失效/未登录（HTTP 200 + 业务码 401）：静默清登录态并跳登录，不弹错误
      if (d.code === 401) {
        localStorage.removeItem('mall_jwt')
        localStorage.removeItem('mall_user')
        const isAdmin = location.pathname.startsWith('/admin') && location.pathname !== '/admin/login'
        const target = isAdmin ? '/admin/login' : '/login'
        if (!location.pathname.endsWith('/login') && location.pathname !== target) {
          location.href = target
        }
        return Promise.reject(d)
      }
      ElMessage.error(d.message || '操作失败')
      return Promise.reject(d)
    }
    return d.data !== undefined ? d.data : d
  },
  err => {
    const c = classifyHttpError(err)
    if (c.type === 'permission' && err.response?.status === 401) {
      localStorage.removeItem('mall_jwt')
      localStorage.removeItem('mall_user')
      const isAdmin = location.pathname.startsWith('/admin') && location.pathname !== '/admin/login'
      const target = isAdmin ? '/admin/login' : '/login'
      if (!location.pathname.endsWith('/login') && location.pathname !== target) {
        location.href = target
      }
    } else {
      ElMessage.error(c.message)
    }
    err.__classified = true
    err.errorType = c.type
    return Promise.reject(err)
  }
)

// ============ API 定义 ============
export const Auth = {
  login: (account, password, captcha) => http.post('/auth/login', { account, password, ...(captcha || {}) }),
  register: (d) => http.post('/auth/register', d),
  logout: () => http.post('/auth/logout'),
  refresh: () => http.post('/auth/refresh'),
  changePassword: (d) => http.post('/auth/changePassword', d),
  captcha: () => http.get('/auth/image'),
}

export const User = {
  info: () => http.get('/user/info'),
  changePassword: (d) => http.post('/user/password', d),
  uploadAvatar: (file) => { const fd = new FormData(); fd.append('file', file); return http.post('/user/avatar', fd) },
  changeNickname: (nickname) => http.post('/user/nickname', null, { params: { nickname } }),
  loginLog: (d) => http.post('/user/loginLog', d),
}

export const Admin = {
  list: (d) => http.post('/admin/list', d),
  ban: (userId) => http.post('/admin/ban', null, { params: { userId } }),
  unban: (userId) => http.post('/admin/unban', null, { params: { userId } }),
  resetPassword: (userId) => http.post('/admin/resetPassword', null, { params: { userId } }),
  stats: () => http.get('/admin/dashboard/stats'),
}

export const Product = {
  page: (d) => http.post('/product/page', d),
  detail: (id) => http.get('/product/detail/' + id),
  add: (d) => http.post('/product/add', d),
  update: (d) => http.put('/product/update', d),
  remove: (id) => http.delete('/product/delete/' + id),
  status: (id, st) => http.put(`/product/status/${id}?status=${st}`),
}

export const Category = {
  list: () => http.get('/category/list'),
  add: (d) => http.post('/category/add', d),
  update: (d) => http.put('/category/update', d),
  remove: (id) => http.delete('/category/delete/' + id),
}

// 购物车：后端 CartController 只提供 加购/改数量/删单品/清空/列表。
// 勾选态在服务端 Redis 里有存储（/cart/list 返回 selected），但后端未暴露修改勾选的接口，
// 因此「勾选」由前端本地维护（见 stores/cart.js），不要调用不存在的 /cart/select。
export const Cart = {
  add: (pid, qty) => http.post('/cart/add', null, { params: { productId: pid, quantity: qty } }),
  updateQty: (pid, qty) => http.post('/cart/updateQuantity', null, { params: { productId: pid, quantity: qty } }),
  remove: (pid) => http.delete('/cart/delete/' + pid),
  clear: () => http.delete('/cart/clear'),
  list: () => http.get('/cart/list'),
}

export const Order = {
  create: (d) => http.post('/order/create', d),
  cancel: (id) => http.post('/order/cancel/' + id),
  // 申请退款/售后：返回退款单 id，进入「待审核」，不等同于退款到账
  refund: (id, d) => http.post('/order/refund/' + id, d || null),
  confirm: (id) => http.post('/order/confirm/' + id),
  detail: (id) => http.get('/order/detail/' + id),
  list: (d) => http.post('/order/list', d),
  adminUpdate: (d) => http.put('/order/admin/update', d),
  adminList: (d) => http.post('/order/admin/list', d),
}

// 支付：下单后调 prepay 拿收银台地址，收款结果由第三方回调 /pay/notify 入账
export const Pay = {
  prepay: (orderId) => http.post('/pay/prepay', { orderId }),
  query: (outTradeNo) => http.get('/pay/query/' + outTradeNo),
}

// 退款（售后）：用户侧 申请/详情/列表/撤销，管理员侧 列表/审核/确认退货/重试/作废
export const Refund = {
  apply: (d) => http.post('/refund/apply', d),
  detail: (id) => http.get('/refund/detail/' + id),
  list: (d) => http.post('/refund/list', d),
  cancel: (id) => http.post('/refund/cancel/' + id),
  adminList: (d) => http.post('/refund/admin/list', d),
  adminDetail: (id) => http.get('/refund/admin/detail/' + id),
  adminAudit: (d) => http.post('/refund/admin/audit', d),
  adminConfirmReturn: (id) => http.post('/refund/admin/confirm-return/' + id),
  adminRetry: (id) => http.post('/refund/admin/retry/' + id),
  adminClose: (id, remark) => http.post('/refund/admin/close/' + id, null, remark ? { params: { remark } } : undefined),
}

export const Address = {
  list: () => http.get('/address/list'),
  default: () => http.get('/address/default'),
  add: (d) => http.post('/address/add', d),
  update: (d) => http.put('/address/update', d),
  remove: (id) => http.delete('/address/delete/' + id),
  setDefault: (id) => http.put('/address/default/' + id),
}

export const Upload = {
  image: (file) => { const fd = new FormData(); fd.append('file', file); return http.post('/upload/image', fd) },
}

// 秒杀：后端只提供 下单/结果轮询/列表/详情/预热/新增/更新/删除。
// 注意：秒杀商品的「上下架」后端没有对应接口（/product/status 会明确拒绝秒杀商品），
// 库存调整只能走 /seckill/update 全量更新，因此这里不再定义 status/stock。
export const Seckill = {
  order: (pid, addrId) => http.post('/seckill/order', { productId: pid, addressId: addrId }),
  result: (rid) => http.post('/seckill/result/' + rid),
  list: (d) => http.post('/seckill/list', d),
  detail: (id) => http.get('/seckill/detail/' + id),
  add: (d) => http.post('/seckill/add', d),
  update: (d) => http.put('/seckill/update', d),
  remove: (id) => http.delete('/seckill/delete/' + id),
  preheatAll: () => http.get('/seckill/preheat/all'),
}

export default http
