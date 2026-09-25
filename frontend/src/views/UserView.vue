<script setup lang="ts">
import { MoreFilled, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import {
  createAdminUser,
  getErrorMessage,
  listAdminUsers,
  resetAdminUserPassword,
  setAdminUserEnabled,
  type AuthUser,
  type CreateUserInput,
} from '../api'
import { useAuthStore } from '../store'
const auth = useAuthStore()
const users = ref<AuthUser[]>([])
const query = ref('')
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const dialog = ref(false)
const target = ref<AuthUser | null>(null)
const formError = ref('')
const form = reactive<CreateUserInput>({
  username: '',
  displayName: '',
  password: '',
  role: 'USER',
})
let sequence = 0
async function load() {
  const request = ++sequence
  loading.value = true
  error.value = ''
  try {
    const result = await listAdminUsers(page.value, 10, query.value)
    if (request === sequence) {
      users.value = result.items
      total.value = result.total
    }
  } catch (cause) {
    if (request === sequence) error.value = getErrorMessage(cause)
  } finally {
    if (request === sequence) loading.value = false
  }
}
function search() {
  page.value = 1
  void load()
}
function openCreate() {
  target.value = null
  Object.assign(form, { username: '', displayName: '', password: '', role: 'USER' })
  formError.value = ''
  dialog.value = true
}
async function action(command: string, user: AuthUser) {
  if (command === 'password') {
    target.value = user
    form.password = ''
    formError.value = ''
    dialog.value = true
    return
  }
  try {
    await ElMessageBox.confirm(
      user.enabled ? '禁用后将立即注销该用户的所有会话。' : '启用后该用户可以重新登录。',
      user.enabled ? '禁用用户' : '启用用户',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
    await setAdminUserEnabled(user.id, !user.enabled)
    await load()
    ElMessage.success('用户状态已更新')
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(getErrorMessage(cause))
  }
}
async function save() {
  if (saving.value) return
  formError.value = ''
  if (
    [...form.password].length < 12 ||
    new TextEncoder().encode(form.password).length > 72 ||
    form.password.includes('\0')
  ) {
    formError.value = '密码至少 12 个字符，UTF-8 编码不超过 72 字节'
    return
  }
  if (
    !target.value &&
    (!/^[a-z0-9._-]{3,64}$/i.test(form.username.trim()) ||
      form.username.trim().toLowerCase() === '__legacy_owner__')
  ) {
    formError.value = '用户名为 3–64 位字母、数字或 . _ -'
    return
  }
  if (
    !target.value &&
    (!form.displayName.trim() ||
      form.displayName.trim().length > 100 ||
      /[\u0000-\u001f\u007f-\u009f]/.test(form.displayName))
  ) {
    formError.value = '请输入有效显示名（不超过 100 字）'
    return
  }
  saving.value = true
  try {
    if (target.value) await resetAdminUserPassword(target.value.id, form.password)
    else
      await createAdminUser({
        ...form,
        username: form.username.trim(),
        displayName: form.displayName.trim(),
      })
    dialog.value = false
    form.password = ''
    page.value = 1
    await load()
    ElMessage.success(target.value ? '密码已重置，该用户需重新登录' : '用户已创建')
  } catch (cause) {
    formError.value = getErrorMessage(cause)
  } finally {
    saving.value = false
  }
}
onMounted(load)
</script>
<template>
  <div class="users-page">
    <header>
      <h1>用户管理</h1>
      <el-button type="primary" :icon="Plus" @click="openCreate">创建用户</el-button>
    </header>
    <section class="users-panel">
      <form class="user-search" @submit.prevent="search">
        <el-input
          v-model="query"
          :prefix-icon="Search"
          maxlength="100"
          placeholder="搜索姓名或用户名"
          aria-label="搜索用户"
          clearable
          @clear="search"
        /><el-button native-type="submit">搜索</el-button
        ><el-button style="margin-left: 0;" :icon="Refresh" aria-label="刷新用户" :loading="loading" @click="load" />
      </form>
      <div v-if="error" role="alert" class="user-error">
        <p>{{ error }}</p>
        <el-button @click="load">重新加载</el-button>
      </div>
      <template v-else>
        <div class="user-row table-heading">
          <span>用户</span><span>角色</span><span>状态</span><span>最近登录</span><span>操作</span>
        </div>
        <article v-for="user in users" :key="user.id" class="user-row">
          <div class="identity">
            <div>
              <strong>{{ user.displayName }}</strong
              ><small>{{ user.username }}</small>
            </div>
          </div>
          <span class="user-role">{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</span>
          <el-tag :type="user.enabled ? 'success' : 'info'">{{
            user.enabled ? '已启用' : '已禁用'
          }}</el-tag>
          <time>{{
            user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString('zh-CN') : '尚未登录'
          }}</time>
          <span v-if="user.id === auth.user?.id" class="user-actions">当前账号</span>
          <el-dropdown
            v-else
            class="user-actions"
            trigger="click"
            @command="(command: string) => action(command, user)"
            ><el-button
              text
              :icon="MoreFilled"
              :aria-label="'管理用户 ' + user.displayName"
            /><template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item command="password">重置密码</el-dropdown-item
                ><el-dropdown-item command="status">{{
                  user.enabled ? '禁用用户' : '启用用户'
                }}</el-dropdown-item></el-dropdown-menu
              ></template
            ></el-dropdown
          >
        </article>
        <el-empty
          v-if="!users.length"
          :description="loading ? '正在加载用户' : '没有匹配的用户'"
          :image-size="64"
        />
        <footer>
          <span>共 {{ total }} 条</span
          ><el-pagination
            v-model:current-page="page"
            :total="total"
            :page-size="10"
            :pager-count="5"
            layout="prev, pager, next"
            @current-change="load"
          />
        </footer>
      </template>
    </section>
    <el-dialog
      v-model="dialog"
      :title="target ? '重置密码' : '创建用户'"
      width="440px"
      :close-on-click-modal="!saving"
      :before-close="
        (done: () => void) => {
          if (!saving) done()
        }
      "
      @closed="form.password = ''"
    >
      <el-form label-position="top" @submit.prevent="save">
        <template v-if="!target"
          ><el-form-item label="显示名称" required
            ><el-input v-model="form.displayName" maxlength="100" /></el-form-item
          ><el-form-item label="用户名" required
            ><el-input v-model="form.username" maxlength="64" autocomplete="off" /></el-form-item
          ><el-form-item label="角色"
            ><el-select v-model="form.role"
              ><el-option label="普通用户" value="USER" /><el-option
                label="管理员"
                value="ADMIN" /></el-select></el-form-item
        ></template>
        <p v-else>{{ target.displayName }} · {{ target.username }}</p>
        <el-form-item :label="target ? '新密码' : '初始密码'" required
          ><el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="new-password"
          /><small class="password-hint"
            >至少 12 个字符，UTF-8 编码不超过 72 字节</small
          ></el-form-item
        >
        <p v-if="formError" role="alert" class="form-error">{{ formError }}</p>
      </el-form>
      <template #footer
        ><el-button :disabled="saving" @click="dialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save">{{
          target ? '重置密码' : '创建用户'
        }}</el-button></template
      >
    </el-dialog>
  </div>
</template>
<style scoped>
.users-page {
  max-width: 1600px;
  margin: auto;
  padding: 29px 30px 45px;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 26px;
  gap: 16px;
}
h1 {
  margin: 0;
  font-size: 25px;
}
.users-panel {
  background: white;
  border: 1px solid var(--color-line);
  border-radius: 17px;
  overflow: hidden;
}
.user-search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 24px;
}
.user-search .el-input {
  max-width: 320px;
}
.user-row {
  display: grid;
  grid-template-columns: minmax(150px, 1.5fr) 0.8fr 0.7fr 1.2fr 80px;
  gap: 16px;
  padding: 18px 24px;
  align-items: center;
  border-top: 1px dashed var(--color-line);
  font-size: 13px;
}
.table-heading {
  font-size: 12px;
  color: var(--color-muted);
  background: #fafbfd;
  padding-block: 12px;
}
.identity {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.identity div {
  min-width: 0;
}
.identity strong {
  font-weight: 550;
  overflow-wrap: anywhere;
}
.identity small {
  display: block;
  color: var(--color-muted);
  margin-top: 4px;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.el-tag {
  width: fit-content;
}
time,
.user-actions {
  font-size: 12px;
  color: var(--color-muted);
}
footer {
  padding: 16px;
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
  border-top: 1px solid var(--color-line);
  font-size: 12px;
  color: var(--color-muted);
}
.user-error {
  padding: 24px;
}
.form-error {
  color: #b85044;
  font-size: 13px;
}
.password-hint {
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.7;
  margin-top: 8px;
}
@media (max-width: 1000px) {
  .user-row {
    grid-template-columns: minmax(130px, 1fr) 90px 75px 70px;
  }
  time,
  .table-heading span:nth-child(4) {
    display: none;
  }
}
@media (max-width: 600px) {
  .users-page {
    padding: 24px 16px;
  }
  .user-search {
    padding: 16px;
    gap: 6px;
  }
  .user-search .el-button {
    margin: 0;
    padding-inline: 10px;
  }
  .table-heading {
    display: none;
  }
  .user-row {
    grid-template-columns: 1fr auto;
    padding: 18px;
    gap: 12px;
  }
  .identity {
    grid-area: 1 / 1;
  }
  .user-actions {
    grid-area: 1 / 2;
  }
  .user-role {
    grid-area: 2 / 1;
    padding-left: 0;
    font-size: 12px;
    color: var(--color-muted);
  }
  .el-tag {
    grid-area: 2 / 2;
  }
}
</style>
