<template>
  <div id="app-layout">
    <nav v-if="showNav" class="top-nav">
      <div class="nav-left">
        <router-link to="/" class="nav-brand">LoveHelping</router-link>
        <router-link to="/love-chat" class="nav-link">解忧信箱</router-link>
        <router-link to="/sandbox" class="nav-link">角色模拟屋</router-link>
        <router-link to="/memory" class="nav-link">记忆档案</router-link>
        <router-link to="/history" class="nav-link">旧信存档</router-link>
        <router-link v-if="isAdminUser" to="/admin" class="nav-link admin-link">管理</router-link>
      </div>
      <div class="nav-right">
        <router-link v-if="user" to="/profile" class="nav-me" :title="'个人中心 · ' + (user.username || '')">
          <span class="nav-avatar">{{ user.avatarEmoji || (user.username || '?').slice(0, 1).toUpperCase() }}</span>
          <span class="nav-nick">{{ user.nickname || user.username }}</span>
        </router-link>
        <button v-if="user" class="nav-logout" @click="logout">退出</button>
      </div>
    </nav>
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getUser, removeToken, isAuthenticated } from './utils/auth.js'

const router = useRouter()
const route = useRoute()

// user 改为 ref + 路由变化时刷新（2026-09-07：个人中心保存资料后导航昵称即时更新）
const user = ref(getUser())
watch(() => route.path, () => { user.value = getUser() }, { immediate: true })
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

.nav-me {
  display: flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
  padding: 3px 10px 3px 4px;
  border-radius: 20px;
  border: 1px solid var(--border-color);
  background: var(--input-bg);
  transition: border-color 0.2s, transform 0.15s;
}
.nav-me:hover { border-color: var(--accent); transform: translateY(-1px); }
.nav-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px; height: 26px;
  border-radius: 50%;
  background: linear-gradient(180deg, var(--accent), var(--accent-hover));
  color: var(--bg-primary);
  font-size: 13px;
  flex-shrink: 0;
}
.nav-nick {
  font-size: 13px;
  color: var(--text-secondary);
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
