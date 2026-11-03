// 登录页 · 用户协议 / 隐私政策弹窗（2026-10-17 新增）
//
// 背景：协议名原来是纯 <a> 无任何事件，点了没反应；用户要求「点击有效能弹出且风格匹配」。
// 修复：新增 src/constants/agreements.js 存放协议全文，点击协议名弹出 el-dialog；
//       弹窗内点「我已阅读并同意」直接勾选协议框。
import { describe, it, expect, beforeEach } from 'vitest'
import { mountView, waitFor } from '../helpers.js'
import Login from '@/views/mall/Login.vue'
import { USER_AGREEMENT, PRIVACY_POLICY, AGREEMENTS } from '@/constants/agreements'

describe('登录页 · 协议弹窗', () => {
  beforeEach(() => localStorage.clear())

  it('协议文案文件存在且结构完整（标题/版本/分节）', () => {
    expect(USER_AGREEMENT.title).toBe('用户协议')
    expect(PRIVACY_POLICY.title).toBe('隐私政策')
    expect(USER_AGREEMENT.sections.length).toBeGreaterThan(3)
    expect(PRIVACY_POLICY.sections.length).toBeGreaterThan(3)
    expect(AGREEMENTS.user).toBe(USER_AGREEMENT)
    expect(AGREEMENTS.privacy).toBe(PRIVACY_POLICY)
    // 每节都要有标题与正文，避免弹窗出现空白区域
    for (const sec of [...USER_AGREEMENT.sections, ...PRIVACY_POLICY.sections]) {
      expect(sec.heading.length).toBeGreaterThan(0)
      expect(sec.paragraphs.length).toBeGreaterThan(0)
    }
  })

  it('点击《用户协议》弹出全文，内容与协议文件一致', async () => {
    const { wrapper } = await mountView(Login, { path: '/login' })
    await waitFor(() => expect(wrapper.find('.agreement-link').exists()).toBe(true))

    const links = wrapper.findAll('.agreement-link')
    expect(links.length).toBe(2)
    await links[0].trigger('click')
    await waitFor(() => expect(wrapper.vm.agreementVisible).toBe(true))

    expect(wrapper.vm.currentAgreement.title).toBe('用户协议')
    const text = wrapper.text()
    expect(text).toContain('用户协议')
    expect(text).toContain(USER_AGREEMENT.sections[0].heading)
  })

  it('点击《隐私政策》弹出对应内容（不是复用一个固定文案）', async () => {
    const { wrapper } = await mountView(Login, { path: '/login' })
    await waitFor(() => expect(wrapper.findAll('.agreement-link').length).toBe(2))

    await wrapper.findAll('.agreement-link')[1].trigger('click')
    await waitFor(() => expect(wrapper.vm.agreementVisible).toBe(true))

    expect(wrapper.vm.currentAgreement.title).toBe('隐私政策')
    expect(wrapper.vm.currentAgreement.key).toBe('privacy')
  })

  it('打开协议不自动勾选（避免误触被当成已同意），弹窗内点同意才勾选', async () => {
    const { wrapper } = await mountView(Login, { path: '/login' })
    await waitFor(() => expect(wrapper.findAll('.agreement-link').length).toBe(2))

    expect(wrapper.vm.agreed).toBe(false)
    await wrapper.findAll('.agreement-link')[0].trigger('click')
    await waitFor(() => expect(wrapper.vm.agreementVisible).toBe(true))
    // 只是查看，不应视为同意
    expect(wrapper.vm.agreed).toBe(false)

    wrapper.vm.agreeFromDialog()
    await waitFor(() => expect(wrapper.vm.agreed).toBe(true))
    expect(wrapper.vm.agreementVisible).toBe(false)
  })

  // 回归守卫：曾把协议链接放进 el-checkbox 的 label 内并用 .stop 阻止冒泡，
  // 结果点击勾选框完全失效（用户根本勾不上，也就无法登录）。
  it('勾选框本身可正常勾选（协议链接移出 label 后不互相干扰）', async () => {
    const { wrapper } = await mountView(Login, { path: '/login' })
    await waitFor(() => expect(wrapper.find('.agreement-check').exists()).toBe(true))

    const input = wrapper.find('.agreement-check input[type="checkbox"]')
    expect(input.exists()).toBe(true)
    await input.setValue(true)
    await waitFor(() => expect(wrapper.vm.agreed).toBe(true))

    // 协议链接在 label 之外，点击它不该改变勾选态
    await wrapper.findAll('.agreement-link')[0].trigger('click')
    await waitFor(() => expect(wrapper.vm.agreementVisible).toBe(true))
    expect(wrapper.vm.agreed).toBe(true)
  })
})
