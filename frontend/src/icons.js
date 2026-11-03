// ---------------------------------------------------------------------------
// Element Plus 图标 · 按需注册
// ---------------------------------------------------------------------------
// 背景（2026-10-17 性能排查）：
//   main.js 原来写的是
//       import * as ElementPlusIconsVue from '@element-plus/icons-vue'
//       for (const [k, c] of Object.entries(ElementPlusIconsVue)) app.component(k, c)
//   图标库共 293 个导出，全部注册意味着【293 个图标组件都进了首屏 bundle】。
//   而项目实际只用到 28 个。
//
// 为什么这份名单可以放心收窄：
//   全局注册的唯一作用是「模板里能直接写 <Search /> 而不 import」。
//   所以名单必须覆盖两类用法：
//     ① .vue 里显式 `import { X } from '@element-plus/icons-vue'` 的（这些其实
//        不依赖全局注册，但一并注册可保持行为与改造前完全一致）
//     ② 模板里直接写 <X /> 但没 import 的（**只靠全局注册才生效**，漏一个就会显示空白）
//   名单由脚本对 src/ 全量扫描后与图标库实际导出取交集得出，不是手抄。
//
// ⚠️ 新增图标时：如果只在模板里写 <NewIcon /> 而不 import，必须同时加进下面这个对象，
//    否则该图标不会渲染（Vue 会报 Failed to resolve component 警告）。
//    更推荐的做法：在用到它的 .vue 里显式 import —— 那样就不依赖全局注册了。
// ---------------------------------------------------------------------------

import {
  ArrowLeft, Camera, Check, Close, DataAnalysis, Edit, Expand, Fold,
  Goods, Lightning, List, Loading, Lock, Menu, Message, Money, Odometer,
  Phone, Plus, Postcard, Refresh, RefreshLeft, Search, Shop, ShoppingCart,
  SwitchButton, Timer, User,
} from '@element-plus/icons-vue'

/** 全局注册的图标（键名 = 组件名，模板里可直接用 <键名 />） */
export const GLOBAL_ICONS = {
  ArrowLeft, Camera, Check, Close, DataAnalysis, Edit, Expand, Fold,
  Goods, Lightning, List, Loading, Lock, Menu, Message, Money, Odometer,
  Phone, Plus, Postcard, Refresh, RefreshLeft, Search, Shop, ShoppingCart,
  SwitchButton, Timer, User,
}

/**
 * 把图标注册到 app 上。
 *
 * 抽成独立文件而不是塞在 main.js 里，是为了让单测能够校验
 * 「名单覆盖了源码里所有实际使用的图标」（见 test/core/icons-coverage.test.js）——
 * 否则将来有人加图标时漏改这份名单，线上就是一片空白图标，而这种问题
 * 编译期、类型检查、普通单测都发现不了。
 */
export function registerIcons(app) {
  for (const [name, component] of Object.entries(GLOBAL_ICONS)) {
    app.component(name, component)
  }
}
