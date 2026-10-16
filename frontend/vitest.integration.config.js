import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// 前后端联调配置
//
// 与 vitest.config.js 的唯一区别：**不装载 MSW**，所有请求直接打到真实后端。
// 页面/组件级单元测试请用 `npm test`（走 vitest.config.js + MSW）。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  cacheDir: path.resolve(__dirname, 'node_modules/.vite-int'),
  test: {
    environment: 'happy-dom',
    // 让相对路径请求基准落在后端，模拟浏览器里通过代理访问后端的场景
    environmentOptions: { happyDOM: { url: 'http://localhost:8080/' } },
    globals: true,
    setupFiles: ['./test/integration/setup.js'], // 关键：不装 MSW，只把 axios 指向真实后端
    include: ['test/integration/**/*.test.js', 'test/regression/**/*.test.js'],
    css: false,
    testTimeout: 30000,
    hookTimeout: 30000,
    fileParallelism: false,
    cache: { dir: path.resolve(__dirname, 'node_modules/.cache/vitest-int') },
  },
})
