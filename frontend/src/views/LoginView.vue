<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getErrorMessage } from '../api'
import { useAuthStore } from '../store'

const logoUrl = `${import.meta.env.BASE_URL}baserag-logo.svg`
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (loading.value) return
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    const target = route.query.redirect
    await router.replace(
      typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')
        ? target
        : '/chat',
    )
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card" aria-labelledby="login-title">
      <div class="login-brand">
        <img class="brand-mark" :src="logoUrl" alt="" width="40" height="40" />
        <strong>BaseRAG</strong>
      </div>
      <h1 id="login-title">登录</h1>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input
            v-model="form.username"
            autocomplete="username"
            autofocus
            placeholder="请输入用户名"
            size="large"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            autocomplete="current-password"
            placeholder="请输入密码"
            show-password
            size="large"
            type="password"
          />
        </el-form-item>
        <el-button
          class="submit"
          type="primary"
          native-type="submit"
          size="large"
          :loading="loading"
        >
          登录
        </el-button>
      </el-form>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  place-items: center;
  padding: 24px;
  background: var(--color-canvas);
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 32px;
  background: rgb(255 255 255 / 94%);
  border: 1px solid #e0e6ef;
  border-radius: 12px;
  box-shadow: 0 8px 36px rgb(23 35 60 / 3%);
}

.brand-mark {
  width: 40px;
  height: 40px;
  display: block;
  object-fit: contain;
  flex-shrink: 0;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
  color: var(--color-ink);
  font-size: 18px;
  font-weight: 600;
}

h1 {
  margin: 0;
  color: #1d2940;
  font-size: 22px;
  margin-bottom: 24px;
}

.submit {
  width: 100%;
  margin-top: 6px;
}

@media (max-width: 520px) {
  .login-card {
    padding: 30px 24px;
  }
}
</style>
