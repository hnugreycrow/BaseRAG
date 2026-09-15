<script setup lang="ts">
import { ArrowDown, Key, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { getErrorMessage } from '../../api'
import { useAuthStore } from '../../store'

withDefaults(defineProps<{ compact?: boolean }>(), {
  compact: false,
})

const auth = useAuthStore()
const router = useRouter()
const passwordOpen = ref(false)
const saving = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const initials = computed(() => (auth.user?.displayName || auth.user?.username || '?').slice(0, 2))
const roleLabel = computed(() => (auth.user?.role === 'ADMIN' ? '管理员' : '用户'))

async function logout() {
  try {
    await auth.logout()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    await router.replace('/login')
  }
}

function openPasswordDialog() {
  Object.assign(form, { oldPassword: '', newPassword: '', confirmPassword: '' })
  passwordOpen.value = true
}

async function changePassword() {
  if (!form.oldPassword || !form.newPassword) {
    ElMessage.warning('请填写当前密码和新密码')
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  saving.value = true
  try {
    await auth.changePassword(form.oldPassword, form.newPassword)
    passwordOpen.value = false
    ElMessage.success('密码已修改，其他登录会话已失效')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="account-menu" :class="{ compact }">
    <el-dropdown trigger="click">
      <button class="profile" type="button" aria-label="打开账号菜单">
        <span class="avatar">{{ initials }}</span>
        <span v-if="!compact" class="copy">
          <strong>{{ auth.user?.displayName || auth.user?.username }}</strong>
          <small>{{ roleLabel }}</small>
        </span>
        <el-icon v-if="!compact" class="arrow"><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item :icon="Key" @click="openPasswordDialog">修改密码</el-dropdown-item>
          <el-dropdown-item :icon="SwitchButton" divided @click="logout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <el-dialog v-model="passwordOpen" title="修改密码" width="min(92vw, 430px)" append-to-body>
      <el-form label-position="top" @submit.prevent="changePassword">
        <el-form-item label="当前密码">
          <el-input
            v-model="form.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="form.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
          />
          <small class="password-hint">至少 12 个字符，UTF-8 编码不超过 72 字节</small>
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            @keyup.enter="changePassword"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="changePassword">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.account-menu {
  min-width: 0;
}

.account-menu :deep(.el-dropdown) {
  width: 100%;
}

.profile {
  width: 100%;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 5px;
  color: #35425a;
  background: transparent;
  border: 0;
  border-radius: 9px;
  cursor: pointer;
}

.profile:hover {
  background: #f5f7fb;
}

.profile:focus-visible {
  outline: 2px solid #315ee7;
  outline-offset: 2px;
}

.avatar {
  width: 33px;
  height: 33px;
  display: grid;
  place-items: center;
  color: #4c60a6;
  font-family: var(--font-data);
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  background: #e6ebf6;
  border-radius: 50%;
}

.copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  text-align: left;
}

.copy strong,
.copy small {
  max-width: 160px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.copy strong {
  font-size: 12px;
  font-weight: 650;
}

.copy small {
  margin-top: 2px;
  color: #919bad;
  font-size: 12px;
}

.arrow {
  flex: 0 0 auto;
  margin: 0 5px 0 2px;
  color: #9ca6b6;
  font-size: 11px;
}

.account-menu:not(.compact) .arrow {
  margin-left: auto;
}

.password-hint {
  display: block;
  margin-top: 6px;
  color: #8a94a6;
  line-height: 1.5;
}
</style>
