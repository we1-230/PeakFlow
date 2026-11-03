import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// 组件测试配置：happy-dom 模拟浏览器环境 + MSW 在 Node 层拦截 HTTP
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  // 缓存目录固定到项目内，避免某些受限环境（只读系统临时目录）下写缓存失败
  cacheDir: path.resolve(__dirname, 'node_modules/.vite-test'),
  test: {
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./test/setup.js'],
    include: ['test/**/*.test.js'],
    // 联调 / 回归用例要连真实后端、且不能装 MSW，必须从单元测试里排除
    exclude: ['**/node_modules/**', '**/dist/**', 'test/integration/**', 'test/regression/**'],
    css: false,
    testTimeout: 15000,
    cache: { dir: path.resolve(__dirname, 'node_modules/.cache/vitest') },
  },
})
