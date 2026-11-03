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
    expect(ElMessage.error).toHaveBeenCalledWith('只能上传图片文件（jpg / png / webp）')
  })

  // 上限从 2MB 放宽到 5MB（2026-10-18）：
  // 服务端会把图压到最长边 400px（实测 1.6MB → 43KB），
  // 而手机原图普遍 3~8MB —— 卡 2MB 会让大量真实用户在选图时就被拒绝。
  it('上传前校验：2.5MB 的手机原图现在放行（旧限制 2MB 会拒掉）', async () => {
    const { wrapper } = await mountUserCenter()
    const phonePhoto = pngFile('photo.png', 2.5 * 1024 * 1024)

    expect(wrapper.vm.beforeAvatarUpload(phonePhoto)).toBe(true)
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('上传前校验：刚好 5MB 放行（边界含等号）', async () => {
    const { wrapper } = await mountUserCenter()
    expect(wrapper.vm.beforeAvatarUpload(pngFile('edge.png', 5 * 1024 * 1024))).toBe(true)
  })

  it('上传前校验：超过 5MB 被拒绝，且提示里给出可行建议', async () => {
    const { wrapper } = await mountUserCenter()
    const tooBig = pngFile('huge.png', 5.1 * 1024 * 1024)

    expect(wrapper.vm.beforeAvatarUpload(tooBig)).toBe(false)
    const msg = ElMessage.error.mock.calls.map(c => String(c[0])).join(' ')
    expect(msg).toContain('5MB')
    expect(msg).toContain('截图')   // 不只说"不能超过"，还要告诉用户怎么办
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
