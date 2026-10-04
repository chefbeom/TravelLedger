import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    // Playwright writes locked videos and trace HTML while Vite is running.
    // They are test output, not sources to watch or hot-reload into the app.
    watch: { ignored: ['**/artifacts/**', '**/test-results/**', '**/playwright-report/**'] },
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
