/**
 * @file    main.js
 * @module  应用入口
 * @desc    应用入口：创建 Vue 实例，注册 Pinia、路由、Element Plus 与全局指令。
 *
 * @author  ZC
 * @date    2026-08-15
 * @version 1.0.0
 * @requires @element-plus/icons-vue, element-plus, pinia, vue
 */

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import router from './router'
import App from './App.vue'
import './style.css'
import { permission } from './directives/permission'
import { useThemeStore } from './stores/theme'

const app = createApp(App)
const pinia = createPinia()

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.directive('permission', permission)
app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

useThemeStore(pinia).init()

app.mount('#app')
