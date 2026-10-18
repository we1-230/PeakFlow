/**
 * 图片 URL 解析（2026-10-17 建立，2026-10-18 扩展为本地图床）
 *
 * ## 图片现在在哪
 * 商品图、分类图标、轮播图**已全部下载到本机**，由 nginx 从
 * `https://域名/uploads/products/<id>-{400|800|1000}.jpg` 直接提供。
 * 数据库里存 `-800` 那一档（详情页用），前端按场景换成更小的档。
 *
 * ## 为什么按场景换档而不是"一张图走天下"
 * 列表卡片展示位只有 200~260px，取 800 档等于多传 60~75% 字节。
 * 服务在境外、国内实测吞吐约 60KB/s，这部分浪费直接变成几秒白屏。
 * 本机 nginx 没编译 image_filter 模块（不能动态裁剪），所以在**下载阶段**
 * 就预生成 400 / 800 / 1000 三档，前端按场景挑文件名 —— 零运行时开销。
 *
 * ## 两种图片来源
 * ① 本机图床 `/uploads/products/xxx-800.jpg` → 换文件名里的档位后缀
 * ② 历史 unsplash 地址 → 走 CDN 的 w=/q= 参数（DB 已换掉，但浏览器缓存、
 *    回滚、或别的环境里仍可能出现，保留这段逻辑）
 * 其他来源（OSS / 老上传目录）一律原样返回 —— 给未知图床加参数可能 404。
 */

/** 统一质量参数：仅在 unsplash 分支使用（本机图在下载阶段已压好） */
const DEFAULT_QUALITY = 65

/**
 * 按场景预置的目标宽度。
 *
 * ⚠️ mini 从 200 改成 400：本机最小档就是 400，请求 200 会得到不存在的文件（404）。
 * 400 档单张约 15~20KB，对 56~80px 的缩略位已经完全够用。
 */
export const IMG_SIZE = {
  /** 列表/卡片缩略图：展示位约 200~260px，2x 屏取 400 */
  thumb: 400,
  /** 购物车/订单小缩略：本机最小档为 400，直接复用 */
  mini: 400,
  /** 详情页主图：展示位最大约 420px，2x 屏取 800 */
  large: 800,
  /** 轮播大图：容器 1200×400（固定高 + 裁剪），按 1000 宽已足够清晰 */
  banner: 1000,
}

const UNSPLASH_HOST = 'images.unsplash.com'

/** 本机图床路径特征与文件名档位后缀 */
const LOCAL_PATH_RE = /^(https?:\/\/[^/]+)?(\/uploads\/products\/.+)$/i
const LOCAL_SIZE_RE = /-(400|800|1000)(\.(?:jpg|jpeg|png|webp))$/i

/**
 * 把图片 URL 解析成「适合当前展示场景」的地址。
 *
 * @param {string} url 原始地址（本机图床 / unsplash / OSS / 上传目录）
 * @param {number} size 目标宽度（px），建议用 {@link IMG_SIZE} 里的值
 * @returns {string} 处理后的地址；入参为空时返回空字符串（模板里 :src="" 不会发请求）
 */
export function resolveImage(url, size = IMG_SIZE.thumb) {
  if (!url || typeof url !== 'string') return ''

  // ---------- ① 本机图床：换文件名里的尺寸档 ----------
  // 兼容绝对地址（https://域名/uploads/...）与相对地址（/uploads/...）
  const local = url.match(LOCAL_PATH_RE)
  if (local) {
    const origin = local[1] || ''
    const path = local[2]
    const swapped = path.replace(LOCAL_SIZE_RE, (whole, cur, ext) => {
      const want = pickLocalTier(size)
      // 档位相同就不动，避免无意义的字符串替换
      return String(want) === String(cur) ? whole : `-${want}${ext}`
    })
    return origin + swapped
  }

  // ---------- ② 历史 unsplash 地址：走 CDN 的 w=/q= 参数 ----------
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

  const isAbsolute = /^https?:\/\//i.test(url)
  return isAbsolute ? parsed.toString() : parsed.pathname + parsed.search
}

/**
 * 把目标宽度映射到本机**实际存在**的档位。
 *
 * 盘上只有 400 / 800 / 1000 三档，请求其它值会 404，所以这里做「向上取整到最近的档」：
 *   要 ≤400  → 400（最小档；购物车 56~80px 的缩略位也够用）
 *   要 ≤800  → 800（详情页主图，保证清晰度）
 *   要 >800  → 1000（轮播）
 * 核心原则：**永不返回盘上不存在的档位**。
 * 注意别为了省流量把 800 降到 400 —— 详情页会明显发虚。
 */
function pickLocalTier(size) {
  const n = Number(size) || IMG_SIZE.thumb
  if (n <= 400) return 400
  if (n <= 800) return 800
  return 1000
}
