<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getErrorMessage } from '../api'
import { useAuthStore } from '../store'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
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
      <div class="brand-mark" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
      <p class="eyebrow">BASERAG · KNOWLEDGE STUDIO</p>
      <h1 id="login-title">欢迎回来</h1>
      <p class="subtitle">登录后继续访问你的私有知识库与会话。</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input
            v-model="form.username"
            autocomplete="username"
            autofocus
            placeholder="name@example"
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
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button class="submit" type="primary" size="large" :loading="loading" @click="submit">
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
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(circle at 20% 10%, rgb(66 99 235 / 14%), transparent 32rem),
    linear-gradient(145deg, #f7f9fd, #eef2f8);
}

.login-card {
  width: min(100%, 420px);
  padding: 42px;
  background: rgb(255 255 255 / 94%);
  border: 1px solid #e0e6ef;
  border-radius: 18px;
  box-shadow: 0 24px 70px rgb(34 49 80 / 14%);
}

.brand-mark {
  width: 44px;
  height: 44px;
  display: grid;
  grid-template-columns: repeat(2, 6px);
  place-content: center;
  gap: 5px;
  background: #4263eb;
  border-radius: 14px 14px 5px 14px;
}

.brand-mark i {
  width: 6px;
  height: 6px;
  background: #fff;
  border-radius: 50%;
}

.eyebrow {
  margin: 24px 0 8px;
  color: #7e8aa0;
  font-family: var(--font-data);
  font-size: 10px;
  letter-spacing: 1.3px;
}

h1 {
  margin: 0;
  color: #1d2940;
  font-size: 30px;
}

.subtitle {
  margin: 9px 0 28px;
  color: #798499;
  font-size: 14px;
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
