/**
 * 图片 URL 解析（2026-10-17 性能优化）
 *
 * 背景：线上首屏慢排查发现两处浪费
 *   ① 商品图全部来自 `images.unsplash.com`，且统一用 `w=800`。
 *      但列表卡片实际展示宽度只有 200~260px（2x 屏按 400 已足够），
 *      传 800px 等于多传 60~75% 的字节。服务在境外、国内吞吐实测约 60KB/s，
 *      这部分浪费直接变成几秒钟的白屏。
 *   ② 详情页大图确实需要 800px，不该被列表尺寸拖累。
 *
 * 做法：按「展示场景」声明尺寸，而不是所有地方发同一张图。
 * 只对 unsplash 的 URL 追加参数（它的 CDN 支持动态缩放）；其他来源（OSS / 本地
 * uploads）原样返回，绝不瞎改第三方 URL。
 */

/** 统一质量参数：unsplash 默认 q 较高，65 在肉眼上基本无差别但体积小很多 */
const DEFAULT_QUALITY = 65

/**
 * 按场景预置的目标宽度。
 *
 * 用常量而不是各处硬编码数字，是为了让「哪里用了多大图」一目了然，
 * 也方便将来统一调整（比如换更好的图床时只改这里）。
 */
export const IMG_SIZE = {
  /** 列表/卡片缩略图：展示位约 200~260px，2x 屏取 400 */
  thumb: 400,
  /** 购物车/订单里的小缩略：展示位约 56~80px，2x 屏取 200 */
  mini: 200,
  /** 详情页主图：展示位最大约 420px，2x 屏取 800 */
  large: 800,
}

const UNSPLASH_HOST = 'images.unsplash.com'

/**
 * 把图片 URL 解析成「适合当前展示场景」的地址。
 *
 * @param {string} url 原始地址（可能是 unsplash / OSS / 本地 /uploads）
 * @param {number} size 目标宽度（px），建议用 {@link IMG_SIZE} 里的值
 * @returns {string} 处理后的地址；入参为空时返回空字符串（模板里 :src="" 不会发请求）
 */
export function resolveImage(url, size = IMG_SIZE.thumb) {
  if (!url || typeof url !== 'string') return ''

  // 只处理 unsplash：它的 CDN 支持 w/q 动态缩放。
  // 其他域名（OSS、本地 uploads）原样返回 —— 改动未知图床的参数可能导致 404。
  let parsed
  try {
    parsed = new URL(url, typeof window !== 'undefined' ? window.location.origin : 'http://localhost')
  } catch {
    return url
  }
  if (parsed.host !== UNSPLASH_HOST) return url

  parsed.searchParams.set('w', String(size))
  parsed.searchParams.set('q', String(DEFAULT_QUALITY))
  // auto=format 让 CDN 按浏览器能力给 webp/avif，进一步省 25~35%
  parsed.searchParams.set('auto', 'format')
  if (!parsed.searchParams.has('fit')) parsed.searchParams.set('fit', 'crop')

  // 相对地址（同源）时返回相对形式，避免 http/https 混杂
  const isAbsolute = /^https?:\/\//i.test(url)
  return isAbsolute ? parsed.toString() : parsed.pathname + parsed.search
}
