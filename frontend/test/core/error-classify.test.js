// 统一错误提示 · HTTP 错误分类
//
// 背景：原拦截器把「断网」「403 无权限」「500 服务器错误」都显示成笼统的
// 「网络错误」，用户无法判断该重试还是该找管理员。现在 api.js 导出
// classifyHttpError 把错误分成四类：network / permission / server / format。
import { describe, it, expect } from 'vitest'
import { classifyHttpError } from '@/api'

const errWith = (status, data) => ({ response: { status, data } })

describe('classifyHttpError · 错误分类', () => {
  it('无 response（断网/DNS/超时）→ network，提示检查网络', () => {
    const c = classifyHttpError(new Error('Network Error'))
    expect(c.type).toBe('network')
    expect(c.message).toContain('网络')
  })

  it('401 → permission（由拦截器统一跳登录）', () => {
    const c = classifyHttpError(errWith(401, {}))
    expect(c.type).toBe('permission')
    expect(c.message).toContain('登录')
  })

  it('403 → permission，优先透传后端文案', () => {
    const c = classifyHttpError(errWith(403, { message: '没有订单读取权限' }))
    expect(c.type).toBe('permission')
    expect(c.message).toBe('没有订单读取权限')
  })

  it('403 无后端文案 → 默认「没有操作权限」', () => {
    const c = classifyHttpError(errWith(403, null))
    expect(c.type).toBe('permission')
    expect(c.message).toBe('没有操作权限')
  })

  it('404 → server，提示接口不存在', () => {
    const c = classifyHttpError(errWith(404, {}))
    expect(c.type).toBe('server')
    expect(c.message).toContain('不存在')
  })

  it('4xx 参数错误 → server，透传后端 message', () => {
    const c = classifyHttpError(errWith(400, { message: '库存不足' }))
    expect(c.type).toBe('server')
    expect(c.message).toBe('库存不足')
  })

  it('500/502/503 → server，提示稍后重试（不暴露堆栈细节）', () => {
    for (const s of [500, 502, 503]) {
      const c = classifyHttpError(errWith(s, { message: 'Internal Error xxx' }))
      expect(c.type).toBe('server')
      expect(c.message).not.toContain('Internal')
      expect(c.message).toContain('稍后重试')
    }
  })
})
