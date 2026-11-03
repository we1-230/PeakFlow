// 支付链路 · MockPay 收银台页面（backend/src/main/resources/static/mock-pay-cashier.html）
//
// 背景（线上事故固化）：收银台脚本曾把收款接口写死为根相对路径 '/mock-pay/pay'。
// 生产环境收银台经 nginx 以 /api/mock-pay/cashier 暴露，fetch('/mock-pay/pay')
// 会打到 nginx 根路径（无该 location）→ 405 HTML → r.json() 抛 SyntaxError，
// 用户看到「网络异常 / JSON 格式不支持」。修复后改为文档相对路径 'pay'，
// 由浏览器按页面自身 URL 解析，任何挂载前缀下都落在正确位置。
//
// 测试方式：读真实 HTML、提取内联 <script>，在 happy-dom 的 Window 里执行，
// 用可编程的 fetch 桩完整驱动「确认支付」按钮，断言请求地址与错误容错行为。
import { describe, it, expect, vi } from 'vitest'
import { Window } from 'happy-dom'
import { readFileSync } from 'node:fs'
import path from 'node:path'

// vitest 工作目录固定为 frontend/，用 cwd 解析后端静态资源路径
// （import.meta.url 在 vitest 转换后不可靠，指向目录而非文件）
const CASHIER_HTML = path.resolve(
  process.cwd(),
  '../backend/src/main/resources/static/mock-pay-cashier.html',
)

const html = readFileSync(CASHIER_HTML, 'utf-8')
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/)
expect(scriptMatch, '收银台页面必须包含内联 <script>').toBeTruthy()
const script = scriptMatch[1]

const PAYLOAD_QUERY = '?out_trade_no=PAY1001-1700000000000&amount=99.00&timestamp=1700000000000&sign=abc'

/** 在指定页面 URL 下装载收银台脚本，返回 window 与 fetch 桩 */
function loadCashier(pageUrl, fetchImpl) {
  // disableJavaScriptEvaluation：阻止 happy-dom 在 document.write 时自行执行内联脚本，
  // 保证脚本只被下面 new Function 的受控方式执行一次（否则会双绑定监听器）
  const win = new Window({ url: pageUrl, settings: { disableJavaScriptEvaluation: true } })
  win.document.write(html)            // 空文档没有页面元素，先解析 HTML
  const fetchSpy = vi.fn(fetchImpl)
  // 脚本内只引用 window / document / fetch / URLSearchParams 四个自由变量
  new Function('window', 'document', 'fetch', 'URLSearchParams', script)(
    win, win.document, fetchSpy, URLSearchParams,
  )
  return { win, fetchSpy }
}

// 脚本只消费响应的 ok / status / json()，用纯对象桩模拟（脚本直接对 fetch 返回值 .then()，
// 桩必须返回 Promise），不用全局 Response —— happy-dom 环境的 Response polyfill 状态码不可靠
const jsonResponse = async (body, { ok = true, status = 200 } = {}) => ({
  ok, status,
  json: async () => body,
})

const htmlResponse = async ({ ok, status }) => ({
  ok, status,
  // 与浏览器一致：对 HTML 响应调用 r.json() 抛 SyntaxError
  json: async () => { throw new SyntaxError('Unexpected token \'<\', "<html>" is not valid JSON') },
})

describe('MockPay 收银台页面 · 确认支付请求地址', () => {
  it('生产环境（/api 前缀挂载）：fetch 必须解析到 /api/mock-pay/pay，而不是根路径', async () => {
    const pageUrl = 'https://peakflow.top/api/mock-pay/cashier' + PAYLOAD_QUERY
    const { win, fetchSpy } = loadCashier(pageUrl, () => jsonResponse({ success: true, message: '支付成功' }))

    win.document.getElementById('payBtn').click()
    await vi.waitFor(() => expect(fetchSpy).toHaveBeenCalled())

    const requested = fetchSpy.mock.calls[0][0]
    // 文档相对路径：浏览器按收银台页面自身 URL 解析
    expect(requested).not.toMatch(/^\//)                       // 不能是根相对路径（事故根因）
    expect(new URL(requested, pageUrl).href).toBe('https://peakflow.top/api/mock-pay/pay')
  })

  it('本地环境（后端域名根路径直挂）：同样解析到 /mock-pay/pay，两环境通吃', () => {
    const pageUrl = 'http://127.0.0.1:8080/mock-pay/cashier' + PAYLOAD_QUERY
    const { win, fetchSpy } = loadCashier(pageUrl, () => jsonResponse({ success: true }))

    win.document.getElementById('payBtn').click()
    expect(new URL(fetchSpy.mock.calls[0][0], pageUrl).href).toBe('http://127.0.0.1:8080/mock-pay/pay')
  })

  it('支付成功响应：切换到已完成面板', async () => {
    const pageUrl = 'https://peakflow.top/api/mock-pay/cashier' + PAYLOAD_QUERY
    const { win, fetchSpy } = loadCashier(pageUrl, () => jsonResponse({ success: true, message: '支付成功' }))

    win.document.getElementById('payBtn').click()
    await vi.waitFor(() =>
      expect(win.document.getElementById('donePanel').className).not.toContain('hidden'))
    expect(win.document.getElementById('payPanel').className).toContain('hidden')
  })
})

describe('MockPay 收银台页面 · 非 JSON / 异常响应容错', () => {
  it('接口返回 405 HTML（本次线上事故场景）：给可读提示，不裸抛解析错误', async () => {
    const pageUrl = 'https://peakflow.top/api/mock-pay/cashier' + PAYLOAD_QUERY
    const { win } = loadCashier(pageUrl, () => htmlResponse({ ok: false, status: 405 }))

    win.document.getElementById('payBtn').click()
    await vi.waitFor(() => {
      const text = win.document.getElementById('status').textContent
      expect(text).toContain('HTTP 405')
    })
    // 按钮必须恢复可点，允许用户重试
    expect(win.document.getElementById('payBtn').disabled).toBe(false)
  })

  it('HTTP 200 但响应体是 HTML：按「异常响应」提示而不是裸 SyntaxError', async () => {
    const pageUrl = 'https://peakflow.top/api/mock-pay/cashier' + PAYLOAD_QUERY
    const { win } = loadCashier(pageUrl, () => htmlResponse({ ok: true, status: 200 }))

    win.document.getElementById('payBtn').click()
    await vi.waitFor(() => {
      const text = win.document.getElementById('status').textContent
      expect(text).toContain('支付接口返回异常响应')
    })
  })

  it('业务失败响应：展示后端 message 且按钮可重试', async () => {
    const pageUrl = 'https://peakflow.top/api/mock-pay/cashier' + PAYLOAD_QUERY
    const { win } = loadCashier(pageUrl, () => jsonResponse({ success: false, message: '商户签名校验失败' }))

    win.document.getElementById('payBtn').click()
    await vi.waitFor(() => {
      const text = win.document.getElementById('status').textContent
      expect(text).toContain('商户签名校验失败')
    })
    expect(win.document.getElementById('payBtn').disabled).toBe(false)
  })
})
