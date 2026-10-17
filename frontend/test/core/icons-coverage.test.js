// 图标注册覆盖率守卫（2026-10-17 性能优化配套）
//
// 背景：main.js 原来 `import * as ElementPlusIconsVue` 全量注册 293 个图标组件，
// 它们全部进了首屏 bundle。改为按需注册 28 个（src/icons.js）后，必须有测试保证
// 「源码里用的每一个图标都在注册名单里」。
//
// 为什么这条测试不能省：
//   漏注册的后果是**运行时图标不渲染**（Vue 只打一条 Failed to resolve component 警告）。
//   编译不报错、类型不报错、页面也不崩 —— 只有肉眼看截图才能发现。
//   这种「静默降级」正是最容易被发上线的缺陷。
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import * as iconLib from '@element-plus/icons-vue'
import { GLOBAL_ICONS } from '@/icons'

const SRC = resolve(process.cwd(), 'src')

function walk(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, acc)
    else if (name.endsWith('.vue') || name.endsWith('.js')) acc.push(p)
  }
  return acc
}

/** 图标库中真实存在的导出名集合 */
const ICON_NAMES = new Set(
  Object.keys(iconLib).filter(k => /^[A-Z]/.test(k) && k !== 'default'),
)

/** 扫描源码：显式 import 的 + 模板里直接当标签用的 */
function collectUsedIcons() {
  const used = new Set()
  for (const file of walk(SRC)) {
    // icons.js 自己就是注册名单，跳过（否则永远自洽）
    if (file.endsWith('icons.js')) continue
    const src = readFileSync(file, 'utf8')

    // import { A, B } from '@element-plus/icons-vue'
    const reImport = /import\s*\{([^};]*?)\}\s*from\s*['"]@element-plus\/icons-vue['"]/g
    let m
    while ((m = reImport.exec(src))) {
      for (const part of m[1].split(',')) {
        const name = part.trim().split(/\s+as\s+/).pop().trim()
        if (name && ICON_NAMES.has(name)) used.add(name)
      }
    }

    // 模板中的 <IconName /> —— 这类只靠全局注册才生效
    const reTag = /<([A-Z][A-Za-z0-9]*)/g
    while ((m = reTag.exec(src))) {
      if (ICON_NAMES.has(m[1])) used.add(m[1])
    }

    // <component :is="IconName" />
    const reIs = /:is="([A-Z][A-Za-z0-9]*)"/g
    while ((m = reIs.exec(src))) {
      if (ICON_NAMES.has(m[1])) used.add(m[1])
    }
  }
  return used
}

describe('图标注册 · 覆盖率守卫', () => {
  it('源码里用到的每个图标都在 GLOBAL_ICONS 里（漏一个就是线上空白图标）', () => {
    const used = collectUsedIcons()
    const registered = new Set(Object.keys(GLOBAL_ICONS))
    const missing = [...used].filter(n => !registered.has(n)).sort()

    expect(missing, `以下图标被使用但未注册，需补进 src/icons.js：${missing.join(', ')}`)
      .toEqual([])
  })

  it('注册名单里没有多余图标（不用的图标会白占首屏体积）', () => {
    const used = collectUsedIcons()
    const extra = Object.keys(GLOBAL_ICONS).filter(n => !used.has(n)).sort()

    // 允许少量冗余（比如预留给动态菜单的图标），但不能任其膨胀
    expect(extra.length, `有 ${extra.length} 个图标注册了但没用到：${extra.join(', ')}`)
      .toBeLessThanOrEqual(3)
  })

  it('注册数量远小于全量（这就是优化的意义）', () => {
    const registeredCount = Object.keys(GLOBAL_ICONS).length
    expect(registeredCount).toBeLessThan(ICON_NAMES.size / 5)
    // 记录当前基线，防止无意识膨胀
    expect(registeredCount).toBeLessThanOrEqual(40)
  })

  it('名单里每个值都是真实的图标组件（不是 undefined）', () => {
    for (const [name, comp] of Object.entries(GLOBAL_ICONS)) {
      expect(comp, `GLOBAL_ICONS.${name} 不是有效组件`).toBeTruthy()
    }
  })
})
