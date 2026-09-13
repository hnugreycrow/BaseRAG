<script setup lang="ts">
import { ChatLineRound } from '@element-plus/icons-vue'
</script>

<template>
  <div class="conversation-page">
    <section class="conversation-shell">
      <span class="conversation-icon"
        ><el-icon><ChatLineRound /></el-icon
      ></span>
      <span class="eyebrow">智能问答</span>
      <h1>问答会话</h1>
      <p>后续将在这里接入流式回答、来源查看与会话历史。</p>
      <el-button type="primary" disabled>创建会话</el-button>
    </section>
  </div>
</template>

<style scoped>
.conversation-page {
  min-height: calc(100vh - 68px);
  display: grid;
  place-items: center;
  padding: 36px;
}

.conversation-shell {
  width: min(620px, 100%);
  padding: 54px 34px;
  text-align: center;
  background: #ffffff;
  border: 1px solid var(--color-line);
  border-radius: 14px;
  box-shadow: 0 14px 38px rgb(25 40 70 / 5%);
}

.conversation-icon {
  width: 54px;
  height: 54px;
  display: grid;
  place-items: center;
  margin: 0 auto 20px;
  color: #4263eb;
  font-size: 25px;
  background: var(--color-primary-soft);
  border-radius: 14px 14px 5px 14px;
}

.eyebrow {
  color: #4263eb;
  font-family: var(--font-data);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1.4px;
}

h1 {
  margin: 8px 0 0;
  color: var(--color-ink);
  font-size: 27px;
}

p {
  margin: 10px 0 24px;
  color: var(--color-muted);
  font-size: 13px;
}

@media (max-width: 760px) {
  .conversation-page {
    min-height: calc(100vh - 60px);
    padding: 16px;
  }

  .conversation-shell {
    padding: 42px 22px;
  }
}
</style>
