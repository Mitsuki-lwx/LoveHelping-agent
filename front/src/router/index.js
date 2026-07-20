import { createRouter, createWebHistory } from 'vue-router'
import Home from '../views/Home.vue'
import LoveChat from '../views/LoveChat.vue'
import ManusChat from '../views/ManusChat.vue'

const routes = [
  { path: '/', name: 'Home', component: Home },
  { path: '/love-chat', name: 'LoveChat', component: LoveChat },
  { path: '/manus-chat', name: 'ManusChat', component: ManusChat }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
