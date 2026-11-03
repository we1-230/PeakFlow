// 图片 URL 解析 · 按场景取尺寸
//
// 2026-10-17 建立，2026-10-18 扩展为「本机图床」。
//
// ## 现在的图在哪
// 商品图、分类图标、轮播图已全部下载到本机，nginx 从
// `/uploads/products/<id>-{400|800|1000}.jpg` 提供，数据库存 `-800` 那一档。
//
// ## 本文件守住的四件事
//   1. 不同场景真的取到不同档位（否则优化等于没做）
//   2. **只返回盘上真实存在的档位**（400/800/1000）—— 返回不存在的档位就是 404 破图
//   3. 不改动无关来源（OSS / 老上传目录加参数会 404）
//   4. 边界输入不产生 "undefined" 这类会发请求的坏 URL
import { describe, it, expect } from 'vitest'
import { resolveImage, IMG_SIZE } from '@/utils/image'

const UNSPLASH = 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=800&q=70'
// 数据库里存的实际形态（绝对地址 + -800 档）
const LOCAL_ABS = 'https://peakflow.top/uploads/products/1505740420928-5e560c06d30e-800.jpg'
// 相对地址形态（同源）
const LOCAL_REL = '/uploads/products/1505740420928-5e560c06d30e-800.jpg'

describe('resolveImage · 本机图床按场景换档', () => {
  it('列表 thumb=400 → 取 -400 档', () => {
    expect(resolveImage(LOCAL_ABS, IMG_SIZE.thumb))
      .toBe('https://peakflow.top/uploads/products/1505740420928-5e560c06d30e-400.jpg')
  })

  it('详情 large=800 → 保持 -800 档（不能为了省流量降到 400，详情会发虚）', () => {
    expect(resolveImage(LOCAL_ABS, IMG_SIZE.large)).toBe(LOCAL_ABS)
  })

  it('轮播 banner=1000 → 取 -1000 档', () => {
    expect(resolveImage(LOCAL_ABS, IMG_SIZE.banner))
      .toBe('https://peakflow.top/uploads/products/1505740420928-5e560c06d30e-1000.jpg')
  })

  it('缩略 mini=400（本机最小档）→ 取 -400，不会产生 404', () => {
    expect(IMG_SIZE.mini).toBe(400)
    expect(resolveImage(LOCAL_ABS, IMG_SIZE.mini))
      .toContain('-400.jpg')
  })

  it('相对地址同样能换档（同源部署时不引入绝对域名）', () => {
    expect(resolveImage(LOCAL_REL, IMG_SIZE.thumb))
      .toBe('/uploads/products/1505740420928-5e560c06d30e-400.jpg')
  })

  it('任意目标宽度都映射到盘上存在的档位（永不返回 404 档）', () => {
    const allowed = new Set(['400', '800', '1000'])
    for (const w of [100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 2000]) {
      const out = resolveImage(LOCAL_ABS, w)
      const m = out.match(/-(\d+)\.jpg$/)
      expect(m, `宽度 ${w} 生成了非法地址: ${out}`).toBeTruthy()
      expect(allowed.has(m[1]), `宽度 ${w} 映射到了不存在的档位 ${m[1]}`).toBe(true)
    }
  })

  it('默认（不传 size）按 thumb=400，避免列表误拉大图', () => {
    expect(resolveImage(LOCAL_ABS)).toContain('-400.jpg')
  })

  it('分类图标（很小）也能正确换档', () => {
    const icon = 'https://peakflow.top/uploads/products/1447933601403-0c6688de566e-400.jpg'
    expect(resolveImage(icon, IMG_SIZE.thumb)).toBe(icon)
  })

  it('不认识的扩展名不瞎替换（避免把 URL 改坏）', () => {
    const weird = 'https://peakflow.top/uploads/products/x-800.gif'
    expect(resolveImage(weird, 400)).toBe(weird)
  })
})

describe('resolveImage · 历史 unsplash 地址仍兼容', () => {
  it('缩略图 400 / 大图 800 各取所需', () => {
    expect(new URL(resolveImage(UNSPLASH, IMG_SIZE.thumb)).searchParams.get('w')).toBe('400')
    expect(new URL(resolveImage(UNSPLASH, IMG_SIZE.large)).searchParams.get('w')).toBe('800')
  })

  it('质量参数降到 65（肉眼无差别，体积小很多）', () => {
    expect(new URL(resolveImage(UNSPLASH, 400)).searchParams.get('q')).toBe('65')
  })

  it('保留 auto=format（CDN 按浏览器能力给 webp/avif）', () => {
    expect(new URL(resolveImage(UNSPLASH, 400)).searchParams.get('auto')).toBe('format')
  })

  it('覆盖原有 w 参数而不是重复追加', () => {
    const out = resolveImage(UNSPLASH, 400)
    expect(out.match(/[?&]w=/g).length).toBe(1)
  })
})

describe('resolveImage · 不误改其他来源', () => {
  it('老上传目录（非 products 子目录）原样返回', () => {
    const local = 'https://peakflow.top/uploads/abc-123.png'
    expect(resolveImage(local, 400)).toBe(local)
  })

  it('OSS 地址原样返回', () => {
    const oss = 'https://peakflow-bucket.oss-cn-hangzhou.aliyuncs.com/avatar/x.jpg'
    expect(resolveImage(oss, 400)).toBe(oss)
  })

  it('相对路径原样返回', () => {
    expect(resolveImage('/uploads/x.png', 400)).toBe('/uploads/x.png')
  })
})

describe('resolveImage · 边界输入', () => {
  it('空值返回空字符串（模板 :src="" 不会触发请求）', () => {
    expect(resolveImage('')).toBe('')
    expect(resolveImage(null)).toBe('')
    expect(resolveImage(undefined)).toBe('')
  })

  it('非字符串输入安全返回空串，不产生 "undefined" 字样', () => {
    for (const bad of [0, false, {}, []]) {
      const out = resolveImage(bad)
      expect(typeof out).toBe('string')
      expect(out).not.toContain('undefined')
    }
  })

  it('非法 URL 原样返回，不抛异常', () => {
    expect(() => resolveImage('https://images.unsplash.com/%%%')).not.toThrow()
  })
})
