// 图片 URL 解析 · 按场景取尺寸（2026-10-17 性能优化）
//
// 背景：线上首屏慢，商品图全部来自境外 unsplash 且统一 w=800。
// 列表卡片实际展示宽度只有 200~260px，传 800px 等于多传 60~75% 字节。
// 本文件守住三点：
//   1. 不同场景真的下发了不同尺寸（否则优化等于没做）
//   2. 不改动非 unsplash 的地址（OSS / 本地 uploads 加了参数会 404）
//   3. 边界输入不产生 "undefined" 这类会发请求的坏 URL
import { describe, it, expect } from 'vitest'
import { resolveImage, IMG_SIZE } from '@/utils/image'

const UNSPLASH = 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=800&q=70'

describe('resolveImage · 按场景取尺寸', () => {
  it('缩略图 400px / 小图 200px / 大图 800px 各取所需', () => {
    const thumb = new URL(resolveImage(UNSPLASH, IMG_SIZE.thumb))
    const mini = new URL(resolveImage(UNSPLASH, IMG_SIZE.mini))
    const large = new URL(resolveImage(UNSPLASH, IMG_SIZE.large))

    expect(thumb.searchParams.get('w')).toBe('400')
    expect(mini.searchParams.get('w')).toBe('200')
    expect(large.searchParams.get('w')).toBe('800')
  })

  it('默认（不传 size）按缩略图处理，避免误传大图', () => {
    const u = new URL(resolveImage(UNSPLASH))
    expect(u.searchParams.get('w')).toBe(String(IMG_SIZE.thumb))
  })

  it('轮播 banner 档位 = 1000px（首屏最大的图，单独一档）', () => {
    const u = new URL(resolveImage(UNSPLASH, IMG_SIZE.banner))
    expect(IMG_SIZE.banner).toBe(1000)
    expect(u.searchParams.get('w')).toBe('1000')
  })

  it('轮播 banner 档位 = 1000px（首屏最大的图，单独一档）', () => {
    const u = new URL(resolveImage(UNSPLASH, IMG_SIZE.banner))
    expect(IMG_SIZE.banner).toBe(1000)
    expect(u.searchParams.get('w')).toBe('1000')
  })

  it('质量参数统一降到 65（肉眼无差别，体积小很多）', () => {
    const u = new URL(resolveImage(UNSPLASH, IMG_SIZE.thumb))
    expect(u.searchParams.get('q')).toBe('65')
  })

  it('保留 auto=format（CDN 按浏览器能力给 webp/avif，再省 25~35%）', () => {
    const u = new URL(resolveImage(UNSPLASH, IMG_SIZE.thumb))
    expect(u.searchParams.get('auto')).toBe('format')
  })

  it('覆盖原有 w 参数而不是重复追加（否则 CDN 取第一个值，优化失效）', () => {
    const out = resolveImage(UNSPLASH, 400)
    // 只能出现一次 w=
    expect(out.match(/[?&]w=/g).length).toBe(1)
    expect(new URL(out).searchParams.get('w')).toBe('400')
  })
})

describe('resolveImage · 不误改其他来源', () => {
  it('本地 uploads 原样返回（加了 w/q 参数后端会 404）', () => {
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

  it('非字符串输入安全返回空串，不抛异常也不产生 "undefined" 字样', () => {
    for (const bad of [0, false, {}, []]) {
      const out = resolveImage(bad)
      expect(typeof out).toBe('string')
      expect(out).not.toContain('undefined')
    }
  })

  it('非法 URL 原样返回，不抛异常', () => {
    const weird = 'https://images.unsplash.com/%%%'
    expect(() => resolveImage(weird)).not.toThrow()
  })
})
