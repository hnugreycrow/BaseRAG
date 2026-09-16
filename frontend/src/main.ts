import { createApp } from 'vue'
import {
  ElAlert,
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
  ElInputNumber,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElPagination,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTooltip,
  ElTree,
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
  ElAlert,
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
  ElInputNumber,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElPagination,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTooltip,
  ElTree,
  ElUpload,
]

elementComponents.forEach((component) => app.use(component))

app.use(pinia).use(router).mount('#app')
