import { createApp } from 'vue'
import {
  ElButton,
  ElConfigProvider,
  ElDialog,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTooltip,
  ElUpload,
} from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import { router } from './route'
import { pinia } from './store'
import { useAuthStore } from './store/auth'
import './style.css'

const app = createApp(App)

window.addEventListener('baserag:unauthorized', () => {
  const auth = useAuthStore(pinia)
  auth.clear()
  if (router.currentRoute.value.name !== 'login') {
    void router.replace({
      name: 'login',
      query: { redirect: router.currentRoute.value.fullPath },
    })
  }
})

const elementComponents = [
  ElButton,
  ElConfigProvider,
  ElDialog,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTooltip,
  ElUpload,
]

elementComponents.forEach((component) => app.use(component))

app.use(pinia).use(router).mount('#app')
