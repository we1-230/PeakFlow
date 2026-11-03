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
  // 后端地址可用环境变量覆盖，默认直连本机跑的 8080：
  //   · 本机直跑后端 / 叠加 docker-compose.dev.yml  → 默认值即可
  //   · 只暴露 nginx 的部署形态（80）               → PEAKFLOW_API_BASE=http://localhost/api
  //     （nginx 会把 /api 前缀剥掉再转发给 backend1/backend2）
  http.defaults.baseURL = process.env.PEAKFLOW_API_BASE || 'http://localhost:8080'
  http.defaults.adapter = 'fetch'
}
