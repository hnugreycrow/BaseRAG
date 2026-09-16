import vue from '@vitejs/plugin-vue'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const prototype = mode === 'prototype'

  return {
    plugins: [vue()],
    build: {
      outDir: prototype ? 'dist-prototype' : 'dist',
      rollupOptions: {
        input: prototype ? 'prototype.html' : 'index.html',
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      include: ['src/tests/**/*.test.ts'],
      setupFiles: ['./src/tests/setup.ts'],
    },
    server: {
      proxy: {
        '/api': {
          target: env.VITE_API_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
          timeout: 0,
          proxyTimeout: 0,
        },
      },
    },
  }
})
