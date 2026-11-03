import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') }
  },
  server: {
    port: 3000,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: p => p.replace(/^\/api/, '')
      }
    }
  },
  build: {
    // ---------------------------------------------------------------------
    // 分包策略（2026-10-17 性能优化）
    //
    // 问题：改造前所有依赖都打进 index-*.js，单文件 1.22MB。生产部署在美国，
    //       国内访问实测该文件（gzip 后 404KB）要 6~9 秒，是首屏慢的主因。
    //
    // 拆分的意义不在减少总字节，而在三点：
    //   ① 首屏关键路径只依赖 vendor-vue（很小）+ 应用代码，element-plus 可并行加载
    //   ② 业务代码改动（每次发版都变）不会让第三方库缓存失效 ——
    //      element-plus / vue 内容不变，浏览器长期复用，用户第二次访问只下几 KB
    //   ③ 配合已开启的 HTTP/2 多路复用，多文件并行不比单文件慢，反而更快
    //
    // 注意：不要拆得过细（每多一个文件就多一次请求头开销），按依赖归属分三组即可。
    // ---------------------------------------------------------------------
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          // Vue 运行时 + 路由 + 状态管理：首屏必需
          if (/[\\/]node_modules[\\/](vue|@vue|vue-router|pinia)[\\/]/.test(id)) return 'vendor-vue'
          // UI 组件库：体积最大，但内容最稳定，缓存价值最高
          if (/[\\/]node_modules[\\/](element-plus|@element-plus)[\\/]/.test(id)) return 'vendor-element'
          // 其余第三方依赖（axios 等）
          return 'vendor'
        },
      },
    },
    // 拆包后单块应远小于此值；保留阈值是为了将来体积失控时能及时报警
    chunkSizeWarningLimit: 700,
  },
})
