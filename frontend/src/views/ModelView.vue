<script setup lang="ts">
import { Cpu } from '@element-plus/icons-vue'
</script>

<template>
  <div class="model-page">
    <header class="page-header">
      <h1>模型</h1>
    </header>

    <section class="model-panel">
      <div class="panel-icon">
        <el-icon><Cpu /></el-icon>
      </div>
      <div>
        <h2>配置由后端统一管理</h2>
        <p>模型状态查询尚未接入。</p>
      </div>
      <el-tag type="info" effect="plain">只读 · 未接入</el-tag>
    </section>
  </div>
</template>

<style scoped>
.model-page {
  max-width: 1380px;
  margin: 0 auto;
  padding: 36px 38px 48px;
}

.page-header span {
  color: #4263eb;
  font-family: var(--font-data);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1.4px;
}

.page-header h1 {
  margin: 7px 0 0;
  color: var(--color-ink);
  font-size: 28px;
  letter-spacing: -0.7px;
}

.page-header p {
  margin: 8px 0 24px;
  color: var(--color-muted);
  font-size: 13px;
}

.model-panel {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 17px;
  padding: 24px;
  background: #ffffff;
  border: 1px solid var(--color-line);
  border-radius: 12px;
}

.panel-icon {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  color: #4263eb;
  font-size: 21px;
  background: var(--color-primary-soft);
  border-radius: 10px;
}

.model-panel h2 {
  margin: 0;
  color: var(--color-ink);
  font-size: 15px;
}

.model-panel p {
  margin: 6px 0 0;
  color: var(--color-muted);
  font-size: 12px;
}

@media (max-width: 760px) {
  .model-page {
    padding: 26px 16px 36px;
  }

  .model-panel {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .model-panel :deep(.el-tag) {
    grid-column: 2;
    justify-self: start;
  }
}
</style>
