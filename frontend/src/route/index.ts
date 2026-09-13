import { createRouter, createWebHistory } from 'vue-router'

import AdminLayout from '../layout/AdminLayout.vue'

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: AdminLayout,
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('../views/DashboardView.vue'),
          meta: { title: '工作台', section: '概览' },
        },
        {
          path: 'knowledge-bases',
          name: 'knowledge-bases',
          component: () => import('../views/KnowledgeBaseView.vue'),
          meta: { title: '知识库', section: '' },
        },
        {
          path: 'conversations',
          name: 'conversations',
          component: () => import('../views/ConversationView.vue'),
          meta: { title: '问答会话', section: '智能问答' },
        },
        {
          path: 'models',
          name: 'models',
          component: () => import('../views/ModelView.vue'),
          meta: { title: '模型配置', section: '系统设置' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? '工作台')} · JAgent`
})
