import { createRouter, createWebHistory } from 'vue-router'
import { isAuthenticated, getUser } from '../utils/auth.js'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue') },
  {
    path: '/',
    name: 'Home',
    component: () => import('../views/Home.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/love-chat',
    name: 'LoveChat',
    component: () => import('../views/LoveChat.vue'),
    meta: { requiresAuth: true }
  },
  // 恋爱全能帮（旧 LoveManus task 通道）合并进统一聊天（后端 classify 自动路由 agent），
  // 2026-09-07：旧路径重定向到 /love-chat（前端不再区分"简单/困难"会话）
  {
    path: '/manus-chat',
    redirect: '/love-chat'
  },
  {
    path: '/sandbox',
    name: 'Sandbox',
    component: () => import('../views/Sandbox.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/memory',
    name: 'MemoryArchive',
    component: () => import('../views/MemoryArchive.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('../views/Profile.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/history',
    name: 'History',
    component: () => import('../views/History.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('../views/Admin.vue'),
    meta: { requiresAuth: true, requiresAdmin: true }
  },
  // ⛔ 404 兜底（2026-10-02）：此前未知路径**什么都不匹配** → 渲染空白页（无任何提示）。
  //    通配必须放最后；它自身不需要鉴权（未登录也能看到"页面不存在"而不是被弹去登录）。
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('../views/NotFound.vue')
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

router.beforeEach((to, from, next) => {
  if (to.meta.requiresAuth && !isAuthenticated()) {
    next('/login')
    return
  }
  if (to.meta.requiresAdmin) {
    const user = getUser()
    if (!user || user.role !== 'ADMIN') {
      next('/')
      return
    }
  }
  next()
})

export default router
