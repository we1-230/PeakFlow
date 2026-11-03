// 管理端页面覆盖测试
//
// 除了「能加载数据」之外，重点锁定两处与后端契约强相关的改造：
//   1. 秒杀商品新增/更新的报文字段名（后端 Add/UpdateSeckillProductDto 要求 seckillPrice/seckillStock/时间窗）
//   2. 秒杀商品「调库存」走 /seckill/update 全量更新（后端没有单独的改库存接口）
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { ElMessage } from 'element-plus'
import { server } from '../setup.js'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import { categories, seckillProducts, ADMIN_TOKEN, currentUser } from '../fixtures.js'
import Dashboard from '@/views/admin/Dashboard.vue'
import UserManage from '@/views/admin/UserManage.vue'
import ProductManage from '@/views/admin/ProductManage.vue'
import CategoryManage from '@/views/admin/CategoryManage.vue'
import SeckillManage from '@/views/admin/SeckillManage.vue'
import OrderManage from '@/views/admin/OrderManage.vue'
import RefundManage from '@/views/admin/RefundManage.vue'
import AdminLogin from '@/views/admin/AdminLogin.vue'

const json = (data, message = '操作成功') =>
  HttpResponse.json({ code: 200, message, data, timestamp: Date.now() })

describe('管理端 · 各页面数据加载', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin({ admin: true })
  })

  it('仪表盘：加载统计与最近订单', async () => {
    const { wrapper } = await mountView(Dashboard, { path: '/admin' })
    await waitFor(() => expect(wrapper.vm.stats.userTotal).toBeGreaterThan(0))
    expect(wrapper.vm.recentOrders.length).toBeGreaterThan(0)
  })

  it('用户管理：加载用户分页列表', async () => {
    const { wrapper } = await mountView(UserManage, { path: '/admin/users' })
    await waitFor(() => expect(wrapper.vm.list.length).toBeGreaterThan(0))
    expect(wrapper.vm.total).toBeGreaterThan(0)
  })

  it('商品管理：加载商品分页列表', async () => {
    const { wrapper } = await mountView(ProductManage, { path: '/admin/products' })
    await waitFor(() => expect(wrapper.vm.list.length).toBeGreaterThan(0))
  })

  it('分类管理：加载分类列表', async () => {
    const { wrapper } = await mountView(CategoryManage, { path: '/admin/categories' })
    await waitFor(() => expect(wrapper.vm.list.length).toBeGreaterThan(0))
    expect(wrapper.vm.list[0].name).toBe(categories[0].name)
  })

  it('秒杀管理：加载秒杀商品列表', async () => {
    const { wrapper } = await mountView(SeckillManage, { path: '/admin/seckill' })
    await waitFor(() => expect(wrapper.vm.list.length).toBeGreaterThan(0))
  })

  it('订单管理：加载订单分页列表', async () => {
    const { wrapper } = await mountView(OrderManage, { path: '/admin/orders' })
    await waitFor(() => expect(wrapper.vm.list.length).toBeGreaterThan(0))
  })

  it('退款管理：加载退款单分页列表', async () => {
    const { wrapper } = await mountView(RefundManage, { path: '/admin/refunds' })
    await waitFor(() => expect(wrapper.vm.loading).toBe(false))
    expect(Array.isArray(wrapper.vm.list)).toBe(true)
  })
})

describe('管理端 · 秒杀商品契约适配', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin({ admin: true })
  })

  async function mountSeckill() {
    const ctx = await mountView(SeckillManage, { path: '/admin/seckill' })
    await waitFor(() => expect(ctx.wrapper.vm.list.length).toBeGreaterThan(0))
    return ctx
  }

  it('新增秒杀商品：报文用 seckillPrice / seckillStock / 时间窗（不含旧的 currentPrice / stock）', async () => {
    let body = null
    server.use(http.post('/api/seckill/add', async ({ request }) => {
      body = await request.json()
      return json(null, '新增成功')
    }))

    const { wrapper } = await mountSeckill()
    wrapper.vm.openAdd()
    Object.assign(wrapper.vm.form, {
      name: '测试秒杀商品', image: 'https://oss.example.com/a.png',
      price: 199, seckillPrice: 99, seckillStock: 10, categoryId: 1,
      seckillStartTime: '2026-10-15 00:00:00', seckillEndTime: '2026-12-31 23:59:59',
    })
    // 弹窗内容需要一帧才渲染出来，formRef 才有值
    await waitFor(() => expect(wrapper.vm.formRef).toBeTruthy())

    await wrapper.vm.handleSave()
    await waitFor(() => expect(body).toBeTruthy())

    expect(body).toMatchObject({
      name: '测试秒杀商品',
      seckillPrice: 99,
      seckillStock: 10,
      categoryId: 1,
      seckillStartTime: '2026-10-15 00:00:00',
      seckillEndTime: '2026-12-31 23:59:59',
    })
    // 旧的错误字段名不应再出现
    expect(body.currentPrice).toBeUndefined()
    expect(body.stock).toBeUndefined()
    expect(ElMessage.success).toHaveBeenCalledWith('新增成功')
  })

  it('更新秒杀商品：报文同样带 id 与时间窗', async () => {
    let body = null
    server.use(http.put('/api/seckill/update', async ({ request }) => {
      body = await request.json()
      return json(null, '更新成功')
    }))

    const { wrapper } = await mountSeckill()
    const row = { ...seckillProducts[0], seckillStartTime: '2026-10-14T00:00:00', seckillEndTime: '2026-12-31T23:59:59' }
    wrapper.vm.openEdit(row)

    // 回填时要把 ISO 的 T 规范成后端要求的空格格式
    expect(wrapper.vm.form.seckillStartTime).toBe('2026-10-14 00:00:00')
    await waitFor(() => expect(wrapper.vm.formRef).toBeTruthy())

    await wrapper.vm.handleSave()
    await waitFor(() => expect(body).toBeTruthy())

    expect(body).toMatchObject({
      id: row.id,
      seckillStartTime: '2026-10-14 00:00:00',
      seckillEndTime: '2026-12-31 23:59:59',
    })
  })

  it('调库存：走 /seckill/update 全量更新，只改 seckillStock', async () => {
    let body = null
    server.use(http.put('/api/seckill/update', async ({ request }) => {
      body = await request.json()
      return json(null, '更新成功')
    }))

    const { wrapper } = await mountSeckill()
    const row = seckillProducts[0]
    wrapper.vm.openStock(row)
    expect(wrapper.vm.stockForm.newStock).toBe(row.seckillStock)

    wrapper.vm.stockForm.newStock = 66
    await wrapper.vm.handleSaveStock()
    await waitFor(() => expect(body).toBeTruthy())

    expect(body.seckillStock).toBe(66)
    // 其余字段要原样带回，否则全量更新会把商品信息清空
    expect(body).toMatchObject({ id: row.id, name: row.name, price: row.price, seckillPrice: row.seckillPrice })
    expect(ElMessage.success).toHaveBeenCalledWith('库存调整成功')
  })

  it('秒杀商品上下架：页面不再提供切换（后端无该接口），状态为只读展示', async () => {
    const { wrapper } = await mountSeckill()
    // 遗留的 toggleStatus 已移除——它调用的 /seckill/status 后端并不存在
    expect(wrapper.vm.toggleStatus).toBeUndefined()
  })
})

describe('管理端 · 登录页', () => {
  beforeEach(() => localStorage.clear())

  it('用管理员账号登录 → 写入登录态并跳转后台首页', async () => {
    // 覆盖登录接口，返回带 admin:manage 的 token（MSW 默认 token 是普通用户）
    server.use(http.post('/api/auth/login', () =>
      HttpResponse.json({
        code: 200, message: '登录成功', timestamp: Date.now(),
        data: { token: ADMIN_TOKEN, tokenType: 'Bearer', user: currentUser },
      })))

    const { wrapper, router } = await mountView(AdminLogin, { path: '/admin/login' })
    await waitFor(() => expect(wrapper.vm.formRef).toBeTruthy())
    wrapper.vm.form.account = 'pfadmin'
    wrapper.vm.form.password = 'Admin123456'

    await wrapper.vm.handleLogin()

    await waitFor(() => expect(localStorage.getItem('mall_jwt')).toBe(ADMIN_TOKEN))
    await waitFor(() => expect(router.currentRoute.value.path).toBe('/admin'))
  })

  it('用无管理员权限的账号登录 → 清登录态并提示无权限', async () => {
    const { wrapper } = await mountView(AdminLogin, { path: '/admin/login' })
    await waitFor(() => expect(wrapper.vm.formRef).toBeTruthy())
    // MSW 默认返回的 USER_TOKEN 不含 admin:manage
    wrapper.vm.form.account = 'zhouay'
    wrapper.vm.form.password = '123456'

    await wrapper.vm.handleLogin()

    await waitFor(() => expect(ElMessage.error).toHaveBeenCalledWith('该账号无管理员权限'))
    expect(localStorage.getItem('mall_jwt')).toBeNull()
  })
})
