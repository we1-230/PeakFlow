import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { Auth } from '@/api'

// 解码 JWT payload（仅用于前端展示角色，后端仍以 token 真实验证为准）
function decodeJwtRoles(token) {
  try {
    const seg = token.split('.')[1]
    const pad = seg + '='.repeat((4 - (seg.length % 4)) % 4)
    const json = JSON.parse(decodeURIComponent(escape(atob(pad))))
    return json.role && Array.isArray(json.role) ? json.role : []
  } catch {
    return []
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('mall_jwt') || '')
  const user = ref(JSON.parse(localStorage.getItem('mall_user') || 'null'))
  // 初始化时从已存 token 补齐角色，避免旧会话刷新后丢失权限
  if (token.value && user.value && (!user.value.roles || !user.value.roles.length)) {
    user.value = { ...user.value, roles: decodeJwtRoles(token.value) }
  }

  const isLoggedIn = computed(() => !!token.value)
  const roles = computed(() => user.value?.roles || [])

  // 后端 permission 表里实际存在的 11 个权限码（见 role_permission / permission 表）。
  // 「能进管理后台」= 至少持有任意一个后台权限点。
  // ⚠️ 旧实现判定的是 'admin:manage'，但后端从未签发过这个权限码（数据库里不存在），
  // 导致任何账号（含 pfadmin/超级管理员）都会被判为「无管理员权限」而进不了后台 —— 已修正。
  const ADMIN_PERMS = [
    'user:manage', 'product:manage', 'category:manage', 'seckill:manage',
    'order:read', 'order:ship', 'refund:read', 'refund:audit', 'refund:close',
    'stats:view',
  ]

  function hasPermission(code) {
    return roles.value.includes(code)
  }

  const isAdmin = computed(() => ADMIN_PERMS.some(p => roles.value.includes(p)))
  const nickname = computed(() => user.value?.nickname || user.value?.username || '游客')

  async function login(account, password, captcha) {
    const data = await Auth.login(account, password, captcha)
    token.value = data.token
    user.value = {
      id: data.user.id,
      username: data.user.username,
      nickname: data.user.nickname,
      avatar: data.user.avatar,
      roles: decodeJwtRoles(data.token),
    }
    localStorage.setItem('mall_jwt', data.token)
    localStorage.setItem('mall_user', JSON.stringify(user.value))
    return data
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('mall_jwt')
    localStorage.removeItem('mall_user')
  }

  // 同步更新昵称：必须改 user.value（nickname 是基于它的 computed，只读不能直接赋值）
  function updateNickname(name) {
    if (!user.value) return
    user.value = { ...user.value, nickname: name }
    localStorage.setItem('mall_user', JSON.stringify(user.value))
  }

  // 同步更新头像：必须改 user.value（顶部导航栏的 src 直接读它，不能局部覆盖）
  // 加 ?t= 时间戳破浏览器对 OSS 同一文件 URL 的缓存，否则 el-avatar 可能不重渲
  function updateAvatar(url) {
    if (!user.value || !url) return url
    const finalUrl = url + (url.includes('?') ? '&' : '?') + 't=' + Date.now()
    user.value = { ...user.value, avatar: finalUrl }
    localStorage.setItem('mall_user', JSON.stringify(user.value))
    return finalUrl
  }

  return { token, user, isLoggedIn, isAdmin, roles, hasPermission, nickname, login, logout, updateNickname, updateAvatar }
})

