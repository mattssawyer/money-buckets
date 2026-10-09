import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueJsx from '@vitejs/plugin-vue-jsx'
import vueDevTools from 'vite-plugin-vue-devtools'
import tailwindcss from '@tailwindcss/vite'
import { contentSecurityPolicyPlugin } from './contentSecurityPolicy'

// https://vite.dev/config/
export default defineConfig(({ mode }) => ({
  plugins: [
    contentSecurityPolicyPlugin(loadEnv(mode, process.cwd(), 'VITE_').VITE_API_BASE_URL ?? ''),
    vue(),
    vueJsx(),
    vueDevTools(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
}))
