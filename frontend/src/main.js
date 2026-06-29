import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'
import { registerServiceWorker } from './registerServiceWorker'

createApp(App).use(createPinia()).mount('#app')
registerServiceWorker()
