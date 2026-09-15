import { createApp } from 'vue'
import {
  ElButton,
  ElDialog,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElSkeleton,
} from 'element-plus'
import 'element-plus/dist/index.css'
import PrototypeApp from './PrototypeApp.vue'
import './prototype.css'

const app = createApp(PrototypeApp)
;[
  ElButton,
  ElDialog,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElSkeleton,
].forEach((component) => app.use(component))
app.mount('#app')
