import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ command }) => ({
  plugins: [vue()],
  base: command === 'build' ? '/api/' : '/',
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.{test,spec}.{js,ts}']
  },
  server: {
    port: 3003,
    proxy: {
      '/Love_app': {
        target: 'http://localhost:8088',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/Love_app/, '/api/Love_app')
      },
      '/api': {
        target: 'http://localhost:8088',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: '../src/main/resources/static',
    // ⛔ 每次构建前清空产物目录：此前不清 ⇒ static/assets 堆到 40 个文件而 index.html 只引用 2 个，
    //    38 个陈旧构建随 jar 一起交付（体积 + 排查干扰）。
    //    ⚠️ 只清 assets 与 index.html（outDir 就是 static，里面没有别的源文件）。
    emptyOutDir: true
  }
}))
