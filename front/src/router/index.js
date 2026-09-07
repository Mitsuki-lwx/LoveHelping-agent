import { createRouter, createWebHistory } from 'vue-router'
import { isAuthenticated, getUser } from '../utils/auth.js'
import Home from '../views/Home.vue'
import Login from '../views/Login.vue'
import LoveChat from '../views/LoveChat.vue'
import Sandbox from '../views/Sandbox.vue'
import MemoryArchive from '../views/MemoryArchive.vue'
import History from '../views/History.vue'
import Profile from '../views/Profile.vue'
import Admin from '../views/Admin.vue'

const routes = [
  { path: '/login', name: 'Login', component: Login },
  {
    path: '/',
    name: 'Home',
    component: Home,
    meta: { requiresAuth: true }
  },
  {
    path: '/love-chat',
    name: 'LoveChat',
    component: LoveChat,
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
    component: Sandbox,
    meta: { requiresAuth: true }
  },
  {
    path: '/memory',
    name: 'MemoryArchive',
    component: MemoryArchive,
    meta: { requiresAuth: true }
  },
  {
    path: '/profile',
    name: 'Profile',
    component: Profile,
    meta: { requiresAuth: true }
  },
  {
    path: '/history',
    name: 'History',
    component: History,
    meta: { requiresAuth: true }
  },
  {
    path: '/admin',
    name: 'Admin',
    component: Admin,
    meta: { requiresAuth: true, requiresAdmin: true }
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
