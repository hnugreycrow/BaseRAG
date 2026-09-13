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
import './style.css'

const app = createApp(App)

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
