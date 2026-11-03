// 个人中心 · 登录日志状态（2026-10-17 线上问题回归）
//
// 线上现象：登录日志里明明每次登录都成功，状态列却全部显示「失败」。
// 根因：后端 login_log 表只有 id/user_id/username/ip/login_time/remark，
//       UserLoginLog 实体同样只有这 6 个字段，根本没有 status。
//       前端按 row.status === 1 判断 ⇒ undefined === 1 恒 false ⇒ 显示「失败」。
// 修复：按真实契约渲染（remark 即结果，日志本就只在登录成功后写入），
//       并删除永远为空的「登录地点 / 设备」两列。
import { describe, it, expect, beforeEach } from 'vitest'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import UserCenter from '@/views/mall/UserCenter.vue'

describe('个人中心 · 登录日志', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
  })

  it('日志行按真实契约渲染：显示「登录成功」，不得显示「失败」', async () => {
    const { wrapper } = await mountView(UserCenter, { path: '/user' })
    await waitFor(() => expect(wrapper.vm.loginLogs.length).toBeGreaterThan(0))

    // 切到「登录日志」页签并等待数据
    wrapper.vm.activeTab = 'logs'
    if (wrapper.vm.fetchLoginLogs) await wrapper.vm.fetchLoginLogs()
    await waitFor(() => expect(wrapper.vm.loginLogs.length).toBeGreaterThan(0))

    const text = wrapper.text()
    expect(text).toContain('登录成功')
    // 回归点：不能出现「失败」标签（status 字段不存在，恒为 undefined 导致的误判）
    expect(text).not.toContain('失败')
  })

  it('不再渲染后端不存在的字段列（登录地点 / 设备）', async () => {
    const { wrapper } = await mountView(UserCenter, { path: '/user' })
    await waitFor(() => expect(wrapper.vm.loginLogs.length).toBeGreaterThan(0))
    wrapper.vm.activeTab = 'logs'
    await waitFor(() => expect(wrapper.vm.loginLogs.length).toBeGreaterThan(0))

    const text = wrapper.text()
    // 这两列对应的后端字段不存在，永远是空，已从表头移除
    expect(text).not.toContain('登录地点')
    expect(text).not.toContain('设备')
  })
})
