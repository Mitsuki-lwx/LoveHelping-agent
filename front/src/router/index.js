import { createRouter, createWebHistory } from 'vue-router'
import { isAuthenticated, getUser } from '../utils/auth.js'
import Home from '../views/Home.vue'
import Login from '../views/Login.vue'
import LoveChat from '../views/LoveChat.vue'
import ManusChat from '../views/ManusChat.vue'
import History from '../views/History.vue'
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
  {
    path: '/manus-chat',
    name: 'ManusChat',
    component: ManusChat,
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
