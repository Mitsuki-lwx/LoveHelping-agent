<template>
  <div id="app-layout">
    <nav v-if="showNav" class="top-nav">
      <div class="nav-left">
        <router-link to="/" class="nav-brand">LoveHelping</router-link>
        <router-link to="/love-chat" class="nav-link">恋爱专家</router-link>
        <router-link to="/manus-chat" class="nav-link">恋爱全能帮</router-link>
        <router-link to="/history" class="nav-link">历史记录</router-link>
        <router-link v-if="isAdminUser" to="/admin" class="nav-link admin-link">管理</router-link>
      </div>
      <div class="nav-right">
        <span v-if="user" class="nav-user">{{ user.username }}</span>
        <button v-if="user" class="nav-logout" @click="logout">退出</button>
      </div>
    </nav>
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getUser, removeToken, isAuthenticated } from './utils/auth.js'

const router = useRouter()
const route = useRoute()

const user = computed(() => getUser())
const isAdminUser = computed(() => user.value?.role === 'ADMIN')
const showNav = computed(() => {
  return route.path !== '/login' && isAuthenticated()
})

function logout() {
  removeToken()
  router.push('/login')
}
</script>

<style>
#app-layout {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

.top-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 48px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 20px;
}

.nav-brand {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-white);
  text-decoration: none;
  margin-right: 8px;
}

.nav-link {
  font-size: 13px;
  color: var(--text-secondary);
  text-decoration: none;
  padding: 4px 8px;
  border-radius: 6px;
  transition: all 0.2s;
}

.nav-link:hover {
  color: var(--text-white);
  background: var(--input-bg);
}

.nav-link.router-link-active {
  color: var(--accent);
}

.admin-link {
  color: var(--accent);
  font-weight: 600;
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.nav-user {
  font-size: 13px;
  color: var(--text-secondary);
}

.nav-logout {
  background: none;
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.nav-logout:hover {
  color: #e74c3c;
  border-color: #e74c3c;
}

.main-content {
  flex: 1;
  overflow: hidden;
}
</style>
