import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'

createApp(App).use(createPinia()).mount('#app')

function scheduleServiceWorkerRegistration() {
  const register = () => {
    import('./registerServiceWorker')
      .then(({ registerServiceWorker }) => registerServiceWorker())
      .catch((error) => {
        console.warn('TravelLedger service worker module failed to load.', error)
      })
  }

  if ('requestIdleCallback' in window) {
    window.requestIdleCallback(register, { timeout: 2000 })
    return
  }
  window.setTimeout(register, 0)
}

if (document.readyState === 'complete') {
  scheduleServiceWorkerRegistration()
} else {
  window.addEventListener('load', scheduleServiceWorkerRegistration, { once: true })
}
