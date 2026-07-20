import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    proxy: {
      '/Love_app': {
        target: 'http://localhost:8123',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/Love_app/, '/api/Love_app')
      },
      '/api': {
        target: 'http://localhost:8123',
        changeOrigin: true
      }
    }
  }
})
