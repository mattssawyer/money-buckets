import { installTrustedTypesPolicy } from './trustedTypes'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import theme from './theme'

import App from './App.vue'
import router from './router'
import { startAuth } from './auth'

import '@fontsource-variable/geist/wght.css'
import './assets/main.css'

installTrustedTypesPolicy()

const workosClientId = import.meta.env.VITE_WORKOS_CLIENT_ID

if (!workosClientId) {
  throw new Error('VITE_WORKOS_CLIENT_ID is not set')
}

const app = createApp(App)

app.use(PrimeVue, {
  license: import.meta.env.VITE_PRIMEUI_LICENSE_KEY,
  theme: {
    preset: theme,
    options: { darkModeSelector: false },
  },
})
app.use(createPinia())
app.use(router)
app.mount('#app')

void startAuth(workosClientId)
