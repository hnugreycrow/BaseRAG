import { createRouter, createWebHistory } from 'vue-router'

import AdminLayout from '../layout/AdminLayout.vue'
import { pinia } from '../store'
import { useAuthStore } from '../store/auth'

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { title: '登录', public: true },
    },
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
      path: '/admin/knowledge-bases/:knowledgeBaseId/documents/:documentId/preview',
      name: 'document-preview',
      component: () => import('../views/DocumentPreviewView.vue'),
      meta: { title: '文档预览', adminOnly: true },
    },
    {
      path: '/admin',
      component: AdminLayout,
      meta: { adminOnly: true },
      children: [
        {
          path: 'users',
          name: 'users',
          component: () => import('../views/UserView.vue'),
          meta: { title: '用户管理', adminOnly: true },
        },
        {
          path: '',
          name: 'dashboard',
          component: () => import('../views/DashboardView.vue'),
          meta: { title: 'Dashboard', section: '概览' },
        },
        {
          path: 'knowledge-bases',
          name: 'knowledge-bases',
          component: () => import('../views/KnowledgeBaseView.vue'),
          meta: { title: '知识库管理', section: '' },
        },
        {
          path: 'intent-tree',
          name: 'intent-tree',
          component: () => import('../views/IntentTreeView.vue'),
          meta: { title: '全局意图树', section: '知识管理' },
        },
        {
          path: 'models',
          name: 'models',
          component: () => import('../views/ModelView.vue'),
          meta: { title: '模型配置', section: '系统设置' },
        },
        {
          path: 'observability',
          name: 'observability',
          component: () => import('../views/ObservabilityView.vue'),
          meta: { title: '链路追踪', section: '观测' },
        },
        {
          path: 'observability/:runId',
          name: 'observability-detail',
          component: () => import('../views/ObservabilityDetailView.vue'),
          meta: {
            title: '运行详情',
            section: '链路追踪',
            activeMenu: '/admin/observability',
          },
        },
      ],
    },
    // 兼容早期后台地址，统一引导到新的页面结构。
    { path: '/conversations', redirect: '/chat' },
    { path: '/knowledge-bases', redirect: '/admin/knowledge-bases' },
    { path: '/models', redirect: '/admin/models' },
    { path: '/observability', redirect: '/admin/observability' },
    { path: '/admin/conversations', redirect: '/chat' },
    { path: '/admin/documents', redirect: '/admin/knowledge-bases' },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/chat',
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore(pinia)
  if (!auth.initialized) {
    try {
      await auth.restore()
    } catch {
      // Redis 或网络故障也保持未认证状态，受保护页面不会降级放行。
    }
  }
  if (to.meta.public) return auth.authenticated ? redirectTarget(to.query.redirect) : true
  if (auth.authenticated) {
    if (to.matched.some((record) => record.meta.adminOnly) && auth.user?.role !== 'ADMIN')
      return '/chat'
    return true
  }
  return { name: 'login', query: { redirect: to.fullPath } }
})

function redirectTarget(value: unknown) {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    ? value
    : '/chat'
}

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? 'Dashboard')} · BaseRAG`
})
