<script setup lang="ts">
import { ArrowRight, ChatDotRound, Collection, Connection } from '@element-plus/icons-vue'

const capabilities = [
  {
    title: '整理知识',
    description: '创建知识库并导入 Markdown 文档，构建可检索的内容空间。',
    path: '/admin/knowledge-bases',
    action: '管理知识库',
    icon: Collection,
  },
  {
    title: '开始问答',
    description: '基于已就绪的文档发起多轮问答，并核对回答来源。',
    path: '/chat',
    action: '进入会话',
    icon: ChatDotRound,
  },
  {
    title: '连接模型',
    description: '查看当前 Chat 与 Embedding 模型的本地配置状态。',
    path: '/admin/models',
    action: '查看模型',
    icon: Connection,
  },
]
</script>

<template>
  <div class="dashboard-page">
    <section class="page-heading">
      <div>
        <span class="eyebrow">LOCAL RAG WORKSPACE</span>
        <h1>把资料变成可追溯的回答</h1>
        <p>管理本地知识、处理文档，并从来源明确的上下文中获得答案。</p>
      </div>
      <div class="environment-badge">
        <span></span>
        本地环境
      </div>
    </section>

    <section class="workflow-panel">
      <div class="workflow-copy">
        <span class="panel-label">推荐流程</span>
        <h2>从第一份 Markdown 开始</h2>
        <p>创建知识库，导入文档并完成分块后，即可进入问答会话。</p>
        <el-button type="primary" @click="$router.push('/admin/knowledge-bases')">
          创建知识库
          <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
      <div class="knowledge-orbit" aria-hidden="true">
        <span class="orbit orbit-one"></span>
        <span class="orbit orbit-two"></span>
        <span class="node node-center">J</span>
        <span class="node node-a"></span>
        <span class="node node-b"></span>
        <span class="node node-c"></span>
      </div>
    </section>

    <section class="capability-grid" aria-label="工作区入口">
      <article v-for="item in capabilities" :key="item.path" class="capability-card">
        <span class="capability-icon"
          ><el-icon><component :is="item.icon" /></el-icon
        ></span>
        <h2>{{ item.title }}</h2>
        <p>{{ item.description }}</p>
        <RouterLink :to="item.path">
          {{ item.action }}
          <el-icon><ArrowRight /></el-icon>
        </RouterLink>
      </article>
    </section>
  </div>
</template>

<style scoped>
.dashboard-page {
  max-width: 1380px;
  margin: 0 auto;
  padding: 38px 38px 48px;
}

.page-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 28px;
}

.eyebrow,
.panel-label {
  color: #4263eb;
  font-family: var(--font-data);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1.6px;
}

.page-heading h1 {
  margin: 8px 0 0;
  color: var(--color-ink);
  font-size: clamp(26px, 3vw, 36px);
  line-height: 1.2;
  letter-spacing: -1.2px;
}

.page-heading p {
  margin: 10px 0 0;
  color: var(--color-muted);
  font-size: 14px;
}

.environment-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 11px;
  color: #617087;
  font-size: 12px;
  background: #ffffff;
  border: 1px solid var(--color-line);
  border-radius: 8px;
}

.environment-badge span {
  width: 7px;
  height: 7px;
  background: var(--color-success);
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgb(26 154 115 / 10%);
}

.workflow-panel {
  position: relative;
  min-height: 270px;
  display: flex;
  align-items: center;
  overflow: hidden;
  padding: 44px 48px;
  color: #ffffff;
  background: linear-gradient(115deg, rgb(23 35 60 / 98%), rgb(34 50 82 / 95%)), #17233c;
  border-radius: 16px;
  box-shadow: 0 18px 45px rgb(24 40 72 / 12%);
}

.workflow-panel::before {
  position: absolute;
  inset: 0;
  pointer-events: none;
  content: '';
  background-image: radial-gradient(rgb(255 255 255 / 9%) 0.8px, transparent 0.8px);
  background-size: 18px 18px;
  mask-image: linear-gradient(90deg, transparent 30%, #000 100%);
}

.workflow-copy {
  position: relative;
  z-index: 2;
  max-width: 570px;
}

.panel-label {
  color: #68dfc5;
}

.workflow-copy h2 {
  margin: 10px 0 0;
  color: #ffffff;
  font-size: clamp(23px, 2.5vw, 31px);
  line-height: 1.2;
  letter-spacing: -0.7px;
}

.workflow-copy p {
  max-width: 500px;
  margin: 12px 0 24px;
  color: #aebbd0;
  font-size: 14px;
  line-height: 1.7;
}

.workflow-copy :deep(.el-button) {
  height: 40px;
  padding-inline: 17px;
  border-radius: 8px;
}

.knowledge-orbit {
  position: absolute;
  right: 7%;
  width: 230px;
  height: 230px;
}

.orbit,
.node {
  position: absolute;
  border-radius: 50%;
}

.orbit {
  inset: 20px;
  border: 1px solid rgb(104 223 197 / 25%);
  transform: rotate(-18deg) scaleY(0.55);
}

.orbit-two {
  inset: 43px 2px;
  border-color: rgb(97 126 255 / 28%);
  transform: rotate(47deg) scaleY(0.52);
}

.node-center {
  inset: 82px;
  display: grid;
  place-items: center;
  color: #ffffff;
  font-family: var(--font-data);
  font-size: 18px;
  font-weight: 700;
  background: #4263eb;
  box-shadow: 0 0 0 12px rgb(66 99 235 / 13%);
}

.node-a,
.node-b,
.node-c {
  width: 10px;
  height: 10px;
  background: #68dfc5;
  box-shadow: 0 0 0 5px rgb(104 223 197 / 10%);
}

.node-a {
  top: 49px;
  right: 30px;
}

.node-b {
  right: 47px;
  bottom: 47px;
}

.node-c {
  top: 105px;
  left: 10px;
  background: #8197ff;
}

.capability-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: 18px;
}

.capability-card {
  min-height: 210px;
  display: flex;
  flex-direction: column;
  padding: 25px;
  background: #ffffff;
  border: 1px solid var(--color-line);
  border-radius: 12px;
  transition:
    border-color 160ms ease,
    transform 160ms ease,
    box-shadow 160ms ease;
}

.capability-card:hover {
  border-color: #d5dcf4;
  box-shadow: 0 12px 30px rgb(29 45 75 / 7%);
  transform: translateY(-2px);
}

.capability-icon {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  color: #4263eb;
  font-size: 19px;
  background: var(--color-primary-soft);
  border-radius: 9px;
}

.capability-card h2 {
  margin: 18px 0 0;
  color: var(--color-ink);
  font-size: 16px;
}

.capability-card p {
  margin: 8px 0 20px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.65;
}

.capability-card a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  margin-top: auto;
  color: #4263eb;
  font-size: 13px;
  font-weight: 600;
}

@media (max-width: 960px) {
  .knowledge-orbit {
    right: 1%;
    opacity: 0.65;
  }

  .workflow-copy {
    max-width: 65%;
  }
}

@media (max-width: 760px) {
  .dashboard-page {
    padding: 26px 16px 36px;
  }

  .page-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .workflow-panel {
    min-height: 310px;
    align-items: flex-start;
    padding: 30px 26px;
  }

  .workflow-copy {
    max-width: 100%;
  }

  .knowledge-orbit {
    right: -62px;
    bottom: -77px;
  }

  .capability-grid {
    grid-template-columns: 1fr;
  }

  .capability-card {
    min-height: 190px;
  }
}
</style>
