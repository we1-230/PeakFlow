// 登录链路（页面 + store）
//
// 覆盖：协议勾选拦截 → 表单校验 → 登录成功落 token/用户信息 → 跳转 redirect；
// 登录失败提示；登出清理；改头像/改昵称的本地同步。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { mountView, seedLogin, waitFor, makeJwt } from '../helpers.js'
import { currentUser, ALL_PERMISSIONS } from '../fixtures.js'
import { resetLoginRisk } from '../handlers.js'
import { Auth } from '@/api'
import Login from '@/views/mall/Login.vue'
import { useAuthStore } from '@/stores/auth'

describe('登录链路 · 页面', () => {
  beforeEach(() => {
    localStorage.clear()
    resetLoginRisk()   // 重置风控失败计数，避免用例间互相污染
  })

  it('未勾选用户协议 → 直接拦截，不发起登录请求', async () => {
    const { wrapper } = await mountView(Login, { path: '/login' })
    wrapper.vm.agreed = false
    wrapper.vm.loginForm.account = 'zhouay'
    wrapper.vm.loginForm.password = '123456'

    await wrapper.vm.handleLogin()

    expect(ElMessage.warning).toHaveBeenCalledWith('请先勾选同意《用户协议》与《隐私政策》')
    expect(localStorage.getItem('mall_jwt')).toBeNull()
  })

  it('账号或密码为空 → 表单校验不通过，不发请求', async () => {
    const { wrapper } = await mountView(Login, { path: '/login' })
    wrapper.vm.agreed = true
    wrapper.vm.loginForm.account = ''
    wrapper.vm.loginForm.password = ''

    await wrapper.vm.handleLogin()

    expect(localStorage.getItem('mall_jwt')).toBeNull()
  })

  it('登录成功 → 写入 token 与用户信息，并跳转到 redirect 指定页', async () => {
    const { wrapper, router } = await mountView(Login, { path: '/login', query: { redirect: '/orders' } })
    wrapper.vm.agreed = true
    wrapper.vm.loginForm.account = 'zhouay'
    wrapper.vm.loginForm.password = '123456'

    await wrapper.vm.handleLogin()

    await waitFor(() => expect(router.currentRoute.value.path).toBe('/orders'))
    expect(ElMessage.success).toHaveBeenCalledWith('登录成功')
    expect(localStorage.getItem('mall_jwt')).toBeTruthy()
    const saved = JSON.parse(localStorage.getItem('mall_user'))
    expect(saved.username).toBe('zhouay')
  })

  it('登录成功但无 redirect → 回到首页', async () => {
    const { wrapper, router } = await mountView(Login, { path: '/login' })
    wrapper.vm.agreed = true
    wrapper.vm.loginForm.account = 'zhouay'
    wrapper.vm.loginForm.password = '123456'

    await wrapper.vm.handleLogin()

    await waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
  })

  it('账号或密码错误 → 提示错误且不落登录态', async () => {
    const { wrapper } = await mountView(Login, { path: '/login' })
    wrapper.vm.agreed = true
    wrapper.vm.loginForm.account = 'zhouay'
    wrapper.vm.loginForm.password = 'wrong-password'

    await wrapper.vm.handleLogin()

    await waitFor(() => expect(ElMessage.error).toHaveBeenCalled())
    expect(localStorage.getItem('mall_jwt')).toBeNull()
  })

  it('风控：连续 2 次密码错误后，第 3 次登录要求验证码并自动展示验证码输入框', async () => {
    // 先用直连接口把风控计数打到阈值（mock 复刻后端：失败 ≥2 次后强制图形验证码）
    await expect(Auth.login('zhouay', 'bad-1')).rejects.toBeTruthy()
    await expect(Auth.login('zhouay', 'bad-2')).rejects.toBeTruthy()

    const { wrapper } = await mountView(Login, { path: '/login' })
    wrapper.vm.agreed = true
    wrapper.vm.loginForm.account = 'zhouay'
    wrapper.vm.loginForm.password = '123456'   // 密码本身正确，但风控要求先过验证码
    await wrapper.vm.handleLogin()

    await waitFor(() => expect(wrapper.vm.captchaImg).toContain('data:image'))
    expect(localStorage.getItem('mall_jwt')).toBeNull()
  })

  it('风控：填对图形验证码 + 正确密码 → 登录成功', async () => {
    await expect(Auth.login('zhouay', 'bad-1')).rejects.toBeTruthy()
    await expect(Auth.login('zhouay', 'bad-2')).rejects.toBeTruthy()

    const { wrapper, router } = await mountView(Login, { path: '/login' })
    wrapper.vm.agreed = true
    wrapper.vm.loginForm.account = 'zhouay'
    wrapper.vm.loginForm.password = '123456'
    await wrapper.vm.handleLogin()             // 触发验证码输入框
    await waitFor(() => expect(wrapper.vm.captchaUuid).toBeTruthy())  // 等 /auth/image 返回 uuid

    wrapper.vm.loginForm.captchaCode = 'AB12'  // mock 验证码（handlers.js）
    await wrapper.vm.handleLogin()

    await waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
    expect(localStorage.getItem('mall_jwt')).toBeTruthy()
  })
})

describe('登录链路 · auth store', () => {
  beforeEach(() => {
    localStorage.clear()
    resetLoginRisk()   // 风控计数按文件隔离，这里再重置一次防跨 describe 污染
    setActivePinia(createPinia())
  })

  it('登录成功：token/user 落地到 localStorage', async () => {
    const auth = useAuthStore()
    expect(auth.isLoggedIn).toBe(false)

    await auth.login('zhouay', '123456')

    expect(auth.isLoggedIn).toBe(true)
    expect(auth.user.username).toBe('zhouay')
    expect(localStorage.getItem('mall_jwt')).toBeTruthy()
  })

  it('后台权限判定：按后端真实权限码（后端不存在 admin:manage）', () => {
    // ① 无任何权限（普通用户）→ 不是后台角色
    localStorage.setItem('mall_jwt', makeJwt([]))
    localStorage.setItem('mall_user', JSON.stringify({ ...currentUser, roles: [] }))
    setActivePinia(createPinia())
    expect(useAuthStore().isAdmin).toBe(false)

    // ② 只带 file:upload（没有任何后台菜单权限）→ 仍不是后台角色
    localStorage.setItem('mall_user', JSON.stringify({ ...currentUser, roles: ['file:upload'] }))
    setActivePinia(createPinia())
    expect(useAuthStore().isAdmin).toBe(false)

    // ③ 商品运营 → 可进后台，但不具备 user:manage
    const opsPerms = ['product:manage', 'category:manage', 'seckill:manage', 'file:upload', 'stats:view']
    localStorage.setItem('mall_user', JSON.stringify({ ...currentUser, roles: opsPerms }))
    setActivePinia(createPinia())
    const ops = useAuthStore()
    expect(ops.isAdmin).toBe(true)
    expect(ops.hasPermission('product:manage')).toBe(true)
    expect(ops.hasPermission('user:manage')).toBe(false)

    // ④ 订单客服 → 可进后台
    localStorage.setItem('mall_user', JSON.stringify({
      ...currentUser, roles: ['order:read', 'order:ship', 'refund:read', 'refund:audit', 'stats:view'],
    }))
    setActivePinia(createPinia())
    expect(useAuthStore().isAdmin).toBe(true)

    // ⑤ 超级管理员（全部 11 个权限）→ 可进后台
    localStorage.setItem('mall_jwt', makeJwt(ALL_PERMISSIONS))
    localStorage.setItem('mall_user', JSON.stringify({ ...currentUser, roles: ALL_PERMISSIONS }))
    setActivePinia(createPinia())
    expect(useAuthStore().isAdmin).toBe(true)
  })

  it('登出：清空内存态与 localStorage', async () => {
    const auth = useAuthStore()
    await auth.login('zhouay', '123456')
    expect(auth.isLoggedIn).toBe(true)

    auth.logout()

    expect(auth.isLoggedIn).toBe(false)
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('mall_jwt')).toBeNull()
    expect(localStorage.getItem('mall_user')).toBeNull()
  })

  it('昵称展示优先级：nickname > username > 游客', () => {
    localStorage.setItem('mall_jwt', makeJwt([]))
    localStorage.setItem('mall_user', JSON.stringify({ ...currentUser, nickname: '周奥宇' }))
    setActivePinia(createPinia())
    expect(useAuthStore().nickname).toBe('周奥宇')

    localStorage.setItem('mall_user', JSON.stringify({ ...currentUser, nickname: '' }))
    setActivePinia(createPinia())
    expect(useAuthStore().nickname).toBe('zhouay')

    localStorage.clear()
    setActivePinia(createPinia())
    expect(useAuthStore().nickname).toBe('游客')
  })

  it('updateNickname / updateAvatar 同步到 store 与 localStorage', async () => {
    const auth = useAuthStore()
    await auth.login('zhouay', '123456')

    auth.updateNickname('新昵称')
    expect(auth.user.nickname).toBe('新昵称')
    expect(JSON.parse(localStorage.getItem('mall_user')).nickname).toBe('新昵称')

    const finalUrl = auth.updateAvatar('https://oss.example.com/avatar/new.png')
    // 追加时间戳破缓存
    expect(finalUrl).toContain('https://oss.example.com/avatar/new.png?t=')
    expect(auth.user.avatar).toBe(finalUrl)
    expect(JSON.parse(localStorage.getItem('mall_user')).avatar).toBe(finalUrl)
  })
})
