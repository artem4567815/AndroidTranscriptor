import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

import './theme/variables.css'
import './theme/app.css'

const app = createApp(App).use(router)

router.isReady().then(() => app.mount('#app'))
