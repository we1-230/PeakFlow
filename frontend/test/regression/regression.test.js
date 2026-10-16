// 回归测试入口：读取 user-flow.json（用户操作模拟配置）逐条执行
//
// 每个配置步骤生成一个独立用例，失败时能直接定位到具体操作；
// 步骤之间通过上下文传递 token 与业务 id（与真实用户操作顺序一致）。
// 运行：npm run test:regression（需后端已启动 + 夹具已重置）
import { describe, it, expect, beforeAll } from 'vitest'
import flow from './user-flow.json'
import { runStep, assertStep } from './run-flow.js'

const ctx = {}
const covered = new Set()

beforeAll(() => {
  const suffix = String(Date.now()).slice(-8)
  const pad = n => String(n).padStart(2, '0')
  const fmt = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`

  Object.assign(ctx, {
    suffix,
    userName: 'reg' + suffix,
    pwdUserName: 'regpw' + suffix,
    banUserName: 'regban' + suffix,
    ban2Name: 'regban2' + suffix,
    nickSuffix: suffix.slice(-6),
    // 分类名下限是 2 个字，没法带唯一后缀，这里生成随机 2 字名保证可重复执行
    edgeCatName: Math.random().toString(36).slice(2, 4),
    adminAccount: 'pfadmin',
    adminPassword: 'Admin123456',
    // 秒杀时间窗动态生成：开始时间取 1 小时前，保证商品处于「活动进行中」
    skStart: fmt(new Date(Date.now() - 3600 * 1000)),
    skEnd: fmt(new Date(Date.now() + 26 * 3600 * 1000)),
  })
})

describe(`回归 · ${flow.name}`, () => {
  for (const step of flow.steps) {
    it(`${step.id} — ${step.desc || ''}`, async () => {
      const result = await runStep(step, ctx)
      assertStep(step, result, expect, ctx)
      if (step.api) covered.add(step.api)
    })
  }

  it('覆盖率自查：配置里的接口调用与前端 api.js 一致', async () => {
    const api = await import('@/api')
    const declared = new Set()
    for (const [modName, mod] of Object.entries(api)) {
      if (modName === 'default' || !mod || typeof mod !== 'object') continue
      for (const fnName of Object.keys(mod)) {
        if (typeof mod[fnName] === 'function') declared.add(`${modName}.${fnName}`)
      }
    }
    const missing = [...declared].filter(name => !covered.has(name)).sort()
    // 这些通过 multipart / 第三方通道覆盖，不走 api.js 的普通调用
    expect(missing, `以下 api.js 接口没有被回归配置覆盖：\n${missing.join('\n')}`).toEqual([])
  })
})
