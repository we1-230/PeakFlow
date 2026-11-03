// 用户操作模拟配置的通用执行器
//
// 配置（user-flow.json）只描述「谁、按什么顺序、调用了哪个接口、期望什么」，
// 具体怎么发请求全部复用前端 src/api.js —— 这样回归用例永远和线上代码走同一套封装。
//
// 步骤字段：
//   id            步骤标识（失败时用于定位）
//   desc          说明
//   actor         admin | user | anon（决定用哪个 token）
//   api           api.js 里的调用，如 "Order.create"
//   args          参数数组，支持 {{变量}} 占位（从上下文取值）
//   save          把响应字段存进上下文，如 {"orderId": "id"}
//   saveFind      列表里按字段找一条再取值，如
//                 {"categoryId": {"list": "", "by": "name", "equals": "{{catName}}", "value": "id"}}
//   expectTruthy  响应里必须非空的字段路径
//   expectEquals  响应里必须等于指定值的字段路径
//   expectContains 响应里指定字段必须包含某子串（如 message 里的幂等文案）
//   expectError   期望这次调用失败（负向用例）
//   transport     "multipart" 时走浏览器等价的 fetch + FormData（文件上传接口）
//   skip / skipReason  跳过并在报告里说明
import http, * as api from '@/api'
import crypto from 'node:crypto'

export const sleep = (ms) => new Promise(r => setTimeout(r, ms))

// MockPay 是「第三方支付平台」，不属于前端 api.js；这里按后端 PaySignUtil 的规则本地签名调用
const MOCK_PAY_SECRET = 'peakflow-mock-pay-secret-key-2026'

export function signMockPay(params) {
  const content = Object.keys(params)
    .filter(k => params[k] !== null && params[k] !== undefined && String(params[k]) !== '')
    .sort()
    .map(k => `${k}=${params[k]}`)
    .join('&')
  return crypto.createHmac('sha256', MOCK_PAY_SECRET).update(content).digest('base64')
}

// 最小合法 PNG（1x1），用于文件上传类接口
export const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
)

/** 上下文变量占位替换：支持 "{{orderId}}"、"order-{{seq}}" */
export function interpolate(value, ctx) {
  if (typeof value === 'string') {
    return value.replace(/\{\{(\w+)\}\}/g, (_, k) => (ctx[k] === undefined ? '' : ctx[k]))
  }
  if (Array.isArray(value)) return value.map(v => interpolate(v, ctx))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, interpolate(v, ctx)]))
  }
  return value
}

/** 按 "a.b.c" 取深层字段 */
export function pick(obj, path) {
  if (path === '' || path == null) return obj
  return String(path).split('.').reduce((acc, k) => (acc == null ? acc : acc[k]), obj)
}

function resolveApi(callPath) {
  const [mod, fn] = String(callPath).split('.')
  const target = api[mod]
  if (!target || typeof target[fn] !== 'function') {
    throw new Error(`配置里的接口不存在：${callPath}`)
  }
  return target[fn]
}

// 第三方收款：模拟 MockPay 收银台收到付款（会触发后端异步回调入账）
async function runMockPay(step, ctx) {
  const base = http.defaults.baseURL
  const outTradeNo = interpolate(step.outTradeNo, ctx)
  const amount = String(interpolate(step.amount, ctx))
  const timestamp = String(Date.now())
  const sign = signMockPay({ out_trade_no: outTradeNo, amount, timestamp })

  const resp = await fetch(base + '/mock-pay/pay', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ out_trade_no: outTradeNo, amount, timestamp, sign }),
  })
  const json = await resp.json()
  if (json.success !== true) throw new Error(json.message || 'MockPay 收款失败')
  // 回调是异步的，稍等一下再让后续步骤查询
  await sleep(600)
  return json
}

function applyToken(actor, ctx) {
  if (actor === 'admin' && ctx.adminToken) localStorage.setItem('mall_jwt', ctx.adminToken)
  else if (actor === 'user' && ctx.userToken) localStorage.setItem('mall_jwt', ctx.userToken)
  else if (actor === 'forged') localStorage.setItem('mall_jwt', 'forged.jwt.token.invalid')
  else if (actor === 'anon') localStorage.removeItem('mall_jwt')
}

// 文件上传：手工拼 multipart 报文（等价于浏览器提交表单时发出的内容）。
// 不用 FormData 的原因：axios 与 happy-dom 在非标准浏览器环境会算出错误的 boundary，
// 后端直接报 MultipartException；手工拼 boundary 后行为与浏览器一致。
async function runMultipart(step, ctx) {
  const base = http.defaults.baseURL
  const token = step.actor === 'admin' ? ctx.adminToken : ctx.userToken
  const boundary = '----PeakFlowRegression' + Date.now()
  const field = step.field || 'file'
  const filename = step.fileName || 'regression.png'

  const head = `--${boundary}\r\nContent-Disposition: form-data; name="${field}"; filename="${filename}"\r\nContent-Type: image/png\r\n\r\n`
  const tail = `\r\n--${boundary}--\r\n`
  const body = new Blob([head, PNG_1X1, tail], { type: 'multipart/form-data' })

  const resp = await fetch(base + step.path, {
    method: 'POST',
    headers: {
      Authorization: 'Bearer ' + token,
      'Content-Type': `multipart/form-data; boundary=${boundary}`,
    },
    body,
  })
  const json = await resp.json()
  if (json.code !== 200) throw Object.assign(new Error(json.message), { code: json.code })
  return json.data
}

// 纯粹的时间等待步骤（用于规避「同一秒内签发相同 JWT」这类竞态）
async function runDelay(step) {
  await sleep(step.ms || 1100)
  return {}
}

/**
 * 执行一个步骤，返回 { response, error }
 * @param step 配置里的步骤
 * @param ctx  上下文（token + 前面步骤 save 下来的值）
 */
export async function runStep(step, ctx) {
  applyToken(step.actor, ctx)

  try {
    const response = step.transport === 'multipart'
      ? await runMultipart(step, ctx)
      : step.transport === 'mockpay'
        ? await runMockPay(step, ctx)
        : step.transport === 'delay'
          ? await runDelay(step)
          : await resolveApi(step.api)(...interpolate(step.args || [], ctx))

    for (const [key, path] of Object.entries(step.save || {})) {
      ctx[key] = pick(response, path)
    }
    for (const [key, rule] of Object.entries(step.saveFind || {})) {
      const list = pick(response, rule.list) || []
      const wanted = interpolate(rule.equals, ctx)
      const hit = list.find(it => String(pick(it, rule.by)) === String(wanted))
      if (!hit) throw new Error(`步骤 ${step.id} 在列表里找不到 ${rule.by}=${wanted}`)
      ctx[key] = pick(hit, rule.value)
    }
    return { response }
  } catch (error) {
    return { error }
  }
}

/** 断言步骤结果符合配置期望（expectEquals 的值同样支持 {{变量}} 占位） */
export function assertStep(step, result, expect, ctx = {}) {
  if (step.expectError) {
    expect(result.error, `步骤 ${step.id} 期望失败，但调用成功了`).toBeTruthy()
    if (step.expectErrorCode !== undefined) {
      const e = result.error || {}
      const actual = e.code !== undefined ? e.code : e.response?.data?.code
      expect(actual, `步骤 ${step.id} 期望失败码 ${step.expectErrorCode}，实际 ${actual}（${e.message || ''}）`)
        .toBe(step.expectErrorCode)
    }
    return
  }
  expect(result.error, `步骤 ${step.id} 调用失败：${result.error?.message || result.error}`).toBeFalsy()

  for (const path of step.expectTruthy || []) {
    const v = pick(result.response, path)
    expect(v, `步骤 ${step.id} 期望 ${path || '(整个响应)'} 非空，实际为 ${JSON.stringify(v)}`).toBeTruthy()
  }
  for (const [path, rawExpected] of Object.entries(step.expectEquals || {})) {
    const v = pick(result.response, path)
    const expected = interpolate(rawExpected, ctx)
    // 后端数值字段可能序列化成 number 或 string，这里按字符串比对
    expect(String(v), `步骤 ${step.id} 期望 ${path} = ${expected}，实际为 ${JSON.stringify(v)}`).toBe(String(expected))
  }
  for (const [path, rawExpected] of Object.entries(step.expectContains || {})) {
    const v = String(pick(result.response, path) ?? '')
    const expected = String(interpolate(rawExpected, ctx))
    expect(v, `步骤 ${step.id} 期望 ${path} 包含「${expected}」，实际为 ${JSON.stringify(v)}`).toContain(expected)
  }
}

export { http }
