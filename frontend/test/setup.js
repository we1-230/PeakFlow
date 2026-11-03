// 组件测试全局装配：MSW 服务端 + happy-dom 缺失 API 补齐 + Element Plus 消息组件 spy
import { beforeAll, beforeEach, afterEach, afterAll, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { handlers } from './handlers.js'
import { ElMessage, ElMessageBox } from 'element-plus'

// ---------- MSW：忠实还原后端契约的 mock 服务 ----------
export const server = setupServer(...handlers)

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'warn' })
})

// Element Plus 消息类组件：默认 spy，避免测试里真实弹窗。
// 必须放在 beforeEach 里——afterEach 的 restoreAllMocks() 会把 spy 还原，
// 只在模块顶层装一次的话，从第 2 个用例开始就会弹出真实弹窗并卡住用例。
function installElementPlusSpies() {
  vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: () => {} }))
  vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }))
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: () => {} }))
  vi.spyOn(ElMessage, 'info').mockImplementation(() => ({ close: () => {} }))
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
  vi.spyOn(ElMessageBox, 'alert').mockResolvedValue('confirm')
  // 申请退款 / 作废退款单 会弹输入框收集原因，默认返回一个合法值，用例里可覆写
  vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '拍错了，不想要了', action: 'confirm' })
}

beforeEach(() => {
  installElementPlusSpies()
})

afterEach(() => {
  server.resetHandlers()
  localStorage.clear()
  vi.restoreAllMocks()
})

afterAll(() => {
  server.close()
})

// ---------- happy-dom 缺失 API 补齐 ----------
if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}
if (globalThis.Element && !globalThis.Element.prototype.scrollIntoView) {
  globalThis.Element.prototype.scrollIntoView = () => {}
}
