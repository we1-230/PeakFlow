// 前后端联调全局装配：把 axios 指向真实后端，并改用 fetch 适配器
//
// 为什么要换适配器：happy-dom 的 XMLHttpRequest 实现不会把 Authorization
// 这类自定义请求头带给服务端（后端只看到"Token无效或不存在"），
// 而原生 fetch 正常。因此联调统一走 axios 的 fetch 适配器。
//
// ⚠️ 该 setup 会被联调目录下所有用例执行，包括 `@vitest-environment node` 的文件；
// node 环境没有 window/DOM，此时不要加载 api 模块（它依赖 element-plus）。
if (typeof window !== 'undefined') {
  const { default: http } = await import('@/api')
  http.defaults.baseURL = 'http://localhost:8080'
  http.defaults.adapter = 'fetch'
}
