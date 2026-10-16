// 头像上传 & 回显链路（UserCenter）
//
// 覆盖：上传前校验（类型/大小）→ 上传成功拿到 URL → 追加时间戳破缓存 → 页面头像立即回显；
// 以及上传失败的兜底提示。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ElMessage } from 'element-plus'
import { mountView, seedLogin, waitFor } from '../helpers.js'
import UserCenter from '@/views/mall/UserCenter.vue'

const pngFile = (name = 'avatar.png', size = 1024) =>
  new File([new Uint8Array(size)], name, { type: 'image/png' })

describe('头像上传 & 回显', () => {
  beforeEach(() => {
    localStorage.clear()
    seedLogin()
  })

  async function mountUserCenter() {
    const ctx = await mountView(UserCenter, { path: '/user' })
    await waitFor(() => expect(ctx.wrapper.vm.userInfo.username).toBeTruthy())
    return ctx
  }

  it('上传前校验：非图片文件被拒绝', async () => {
    const { wrapper } = await mountUserCenter()
    const txt = new File(['hello'], 'a.txt', { type: 'text/plain' })

    expect(wrapper.vm.beforeAvatarUpload(txt)).toBe(false)
    expect(ElMessage.error).toHaveBeenCalledWith('只能上传图片文件')
  })

  it('上传前校验：超过 2MB 被拒绝', async () => {
    const { wrapper } = await mountUserCenter()
    const big = pngFile('big.png', 2.5 * 1024 * 1024)

    expect(wrapper.vm.beforeAvatarUpload(big)).toBe(false)
    expect(ElMessage.error).toHaveBeenCalledWith('图片大小不能超过 2MB')
  })

  it('上传前校验：合法图片放行', async () => {
    const { wrapper } = await mountUserCenter()
    expect(wrapper.vm.beforeAvatarUpload(pngFile())).toBe(true)
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('上传成功 → 头像 URL 追加时间戳破缓存，并同步到页面（回显）', async () => {
    const { wrapper } = await mountUserCenter()
    const oldAvatar = wrapper.vm.userInfo.avatar

    await wrapper.vm.handleAvatarUpload({ file: pngFile() })

    await waitFor(() => expect(wrapper.vm.userInfo.avatar).not.toBe(oldAvatar))
    // MSW 返回 https://oss.example.com/avatar/new.png，前端应追加 ?t= 时间戳
    expect(wrapper.vm.userInfo.avatar).toContain('https://oss.example.com/avatar/new.png?t=')
    expect(ElMessage.success).toHaveBeenCalledWith('头像更新成功')
  })

  it('回显：el-avatar 的 src 跟着更新（不是只改数据不渲染）', async () => {
    const { wrapper } = await mountUserCenter()

    await wrapper.vm.handleAvatarUpload({ file: pngFile() })
    await waitFor(() => expect(wrapper.vm.userInfo.avatar).toContain('avatar/new.png'))

    const avatars = wrapper.findAllComponents({ name: 'ElAvatar' })
    expect(avatars.length).toBeGreaterThan(0)
    const srcs = avatars.map(a => a.props('src'))
    expect(srcs.some(s => String(s).includes('avatar/new.png'))).toBe(true)
  })

  it('头像同步进 localStorage，刷新后仍是新头像', async () => {
    const { wrapper } = await mountUserCenter()

    await wrapper.vm.handleAvatarUpload({ file: pngFile() })
    await waitFor(() => expect(wrapper.vm.userInfo.avatar).toContain('avatar/new.png'))

    const saved = JSON.parse(localStorage.getItem('mall_user'))
    expect(saved.avatar).toContain('avatar/new.png')
  })

  it('上传失败 → 兜底提示，头像保持原样', async () => {
    const { wrapper } = await mountUserCenter()
    const oldAvatar = wrapper.vm.userInfo.avatar

    // 用一个不会被 MSW handlers 捕获的调用方式模拟失败：直接让 api 抛错
    const api = await import('@/api')
    const spy = vi.spyOn(api.User, 'uploadAvatar').mockRejectedValue(new Error('上传失败'))

    await wrapper.vm.handleAvatarUpload({ file: pngFile() })

    expect(ElMessage.error).toHaveBeenCalled()
    expect(wrapper.vm.userInfo.avatar).toBe(oldAvatar)
    spy.mockRestore()
  })
})
