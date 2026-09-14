import { createRouter, createWebHistory } from 'vue-router'

import AdminLayout from '../layout/AdminLayout.vue'

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/chat',
    },
    {
      path: '/chat/:conversationId?',
      name: 'chat',
      component: () => import('../views/ConversationView.vue'),
      meta: { title: '知识问答' },
    },
    {
      path: '/admin',
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
          path: 'models',
          name: 'models',
          component: () => import('../views/ModelView.vue'),
          meta: { title: '模型配置', section: '系统设置' },
        },
      ],
    },
    // 兼容早期后台地址，统一引导到新的页面结构。
    { path: '/conversations', redirect: '/chat' },
    { path: '/knowledge-bases', redirect: '/admin/knowledge-bases' },
    { path: '/models', redirect: '/admin/models' },
    { path: '/admin/conversations', redirect: '/chat' },
    { path: '/admin/documents', redirect: '/admin/knowledge-bases' },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/chat',
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? '工作台')} · JAgent`
})
