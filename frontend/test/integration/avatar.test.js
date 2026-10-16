// @vitest-environment node
//
// 头像上传联调（真实后端）
//
// 为什么这里直接用 fetch 而不是 User.uploadAvatar：
//   axios 只有在「标准浏览器环境」下才会把 multipart 的 Content-Type 交给运行时生成
//   （浏览器里带上正确的 boundary）。在 node / happy-dom 等非标准浏览器环境，
//   axios 会自己去算 boundary，算出来的报文后端不认（MultipartException）。
//   这不是前端代码的问题——浏览器里就是这样发的。所以联调这里用浏览器等价的
//   fetch + FormData 复刻同一条请求；前端 api 层的封装另有单元测试覆盖。
import { describe, it, expect, beforeAll } from 'vitest'

const BASE = 'http://localhost:8080'

// 最小合法 PNG（1x1），保证后端 ImageIO 能识别为真实图片
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
)

let token = ''
let username = ''

const postJson = (path, body) =>
  fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then(r => r.json())

// 与浏览器里 <input type=file> 提交后 axios 发出的请求等价
async function uploadAvatar(file) {
  const fd = new FormData()
  fd.append('file', file)
  const resp = await fetch(BASE + '/user/avatar', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token },
    body: fd,
  })
  return resp.json()
}

describe('联调 · 头像上传与回显', () => {
  beforeAll(async () => {
    username = 'feav' + Date.now()
    await postJson('/auth/register', {
      username, password: 'It123456', email: `${username}@itest.com`, nickname: '联调头像',
    })
    const login = await postJson('/auth/login', { account: username, password: 'It123456' })
    token = login.data.token
    expect(token).toBeTruthy()
  })

  it('上传头像 → 返回可访问直链（multipart/form-data）', async () => {
    const file = new File([PNG_1X1], 'fe-avatar.png', { type: 'image/png' })
    const res = await uploadAvatar(file)

    expect(res.code).toBe(200)
    expect(typeof res.data).toBe('string')
    // 必须是绝对地址：前端 <img src> 不能依赖当前页面的相对路径
    expect(res.data).toMatch(/^https?:\/\//)
    // 存储后端两种都算正常：配了凭证走阿里云 OSS，没配则降级到本地 /uploads/
    // （不去锁死供应商——换 OSS/CDN 不该让用例变红）
    expect(res.data).toMatch(/aliyuncs\.com|\/uploads\//)
  })

  it('回显可用性：上传后的直链能被匿名访问且是真实图片', async () => {
    const file = new File([PNG_1X1], 'fe-avatar-echo.png', { type: 'image/png' })
    const res = await uploadAvatar(file)
    expect(res.code).toBe(200)

    // 前端 el-avatar 只认能直接 GET 到的图片地址，这里模拟浏览器加载
    const resp = await fetch(res.data)
    expect(resp.status).toBe(200)
    expect(String(resp.headers.get('content-type'))).toContain('image')
    const bytes = Buffer.from(await resp.arrayBuffer())
    expect(bytes.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a') // PNG 魔数
  })

  it('头像写入用户资料：再查 /user/info 能读回同一地址（刷新后仍显示）', async () => {
    const file = new File([PNG_1X1], 'fe-avatar-profile.png', { type: 'image/png' })
    const res = await uploadAvatar(file)

    const info = await fetch(BASE + '/user/info', { headers: { Authorization: 'Bearer ' + token } }).then(r => r.json())
    expect(info.data.avatar).toBe(res.data)
  })

  it('把任意文件改名成 .png 会被后端拒绝（防伪造图片）', async () => {
    const fake = new File([Buffer.from('definitely not an image')], 'fake.png', { type: 'image/png' })
    const res = await uploadAvatar(fake)

    expect(res.code).not.toBe(200)
    expect(String(res.message)).toContain('图片')
  })

  it('未登录上传被拒绝', async () => {
    const saved = token
    token = ''
    const file = new File([PNG_1X1], 'x.png', { type: 'image/png' })
    const res = await uploadAvatar(file)
    token = saved

    expect(res.code).toBe(401)
  })
})
