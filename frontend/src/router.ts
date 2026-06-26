import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/kbs' },
    { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/register', component: () => import('@/views/RegisterView.vue'), meta: { public: true } },
    { path: '/kbs', component: () => import('@/views/KbListView.vue') },
    { path: '/kbs/:kbId', component: () => import('@/views/WorkspaceView.vue') },
    { path: '/admin/users', component: () => import('@/views/AdminUsersPlaceholder.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/kbs' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.public && auth.isAuthenticated) {
    return '/kbs'
  }
  if (to.path === '/admin/users' && auth.globalRole !== 'ADMIN') {
    return '/kbs'
  }
  return true
})
