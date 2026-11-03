import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router.js'
import { registerIcons } from './icons.js'
import './styles/taobao.css'
import './styles/mobile.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
// 图标按需注册（28 个，而非全量 293 个）。
// 详见 src/icons.js 顶部说明：全量注册会让 293 个图标组件都进首屏 bundle，
// 净增约 250KB 原始体积 —— 在跨国链路上就是几秒钟。
registerIcons(app)
app.mount('#app')
