<template>
  <div id="app-layout">
    <nav v-if="showNav" class="top-nav">
      <div class="nav-left">
        <router-link to="/" class="nav-brand hand" title="回到首页">
          <span class="nav-brand-stamp">恋</span>恋爱解忧所
        </router-link>
        <span class="nav-sep" aria-hidden="true"></span>
        <router-link to="/love-chat" class="nav-link">解忧信箱</router-link>
        <router-link to="/sandbox" class="nav-link">角色模拟屋</router-link>
        <router-link to="/memory" class="nav-link">记忆档案</router-link>
        <router-link to="/history" class="nav-link">旧信存档</router-link>
        <router-link v-if="isAdminUser" to="/admin" class="nav-link">管理</router-link>
      </div>
      <div class="nav-right">
        <router-link v-if="user" to="/profile" class="nav-me" :title="'个人中心 · ' + (user.username || '')">
          <span class="nav-avatar hand">{{ user.avatarEmoji || (user.username || '?').slice(0, 1).toUpperCase() }}</span>
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
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getUser, removeToken, isAuthenticated } from './utils/auth.js'

const router = useRouter()
const route = useRoute()

// user 改为 ref + 路由变化/资料保存事件时刷新（2026-09-07：个人中心保存后导航即时更新）
const user = ref(getUser())
watch(() => route.path, () => { user.value = getUser() }, { immediate: true })
function refreshUser() { user.value = getUser() }
onMounted(() => window.addEventListener('lh:user-updated', refreshUser))
onUnmounted(() => window.removeEventListener('lh:user-updated', refreshUser))
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
/* ============================================================
   顶层导航（2026-10-01 重做：并入「晚灯书房」体系）
   此前用旧变量名 + 通用样式，与信笺风完全脱节（代码里自己标注"过渡期"）。
   现在它就是桌沿上的一条墨线：深木底 + 楷体印章品牌 + 酒红当前位。
   ============================================================ */
#app-layout {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

.top-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 56px;
  background: var(--desk-raised);
  border-bottom: 1px solid var(--ink-line);
  flex-shrink: 0;
  /* 桌沿光：导航底缘一道极淡的酒红灯晕 */
  box-shadow: 0 10px 30px -18px oklch(30% 0.08 28 / 0.7);
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.nav-brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  letter-spacing: 0.12em;
  color: var(--ink);
  text-decoration: none;
  margin-right: 10px;
}
.nav-brand-stamp {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  border: 1.8px dashed var(--wine-glow);
  color: var(--wine-glow);
  font-size: 15px;
  transform: rotate(-10deg);
}

/* 品牌与功能区的分隔：一小段墨线 */
.nav-sep {
  width: 1.5px;
  height: 18px;
  background: var(--ink-line);
  margin: 0 10px 0 2px;
  flex-shrink: 0;
}

.nav-link {
  font-size: 13.5px;
  color: var(--ink-soft);
  text-decoration: none;
  padding: 6px 12px;
  border-radius: 10px;
  white-space: nowrap;
  transition: color 0.18s, background 0.18s;
}
.nav-link:hover {
  color: var(--ink);
  background: var(--desk);
}
/* 当前位：桌面上的一枚小印 */
.nav-link.router-link-active {
  color: var(--wine-glow);
  background: var(--wine-soft);
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
  padding: 3px 12px 3px 4px;
  border-radius: 999px;
  border: 1px solid var(--ink-line);
  background: var(--desk);
  transition: border-color 0.2s, transform 0.15s;
}
.nav-me:hover { border-color: var(--wine-glow); transform: translateY(-1px); }
.nav-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 27px; height: 27px;
  border-radius: 50%;
  background: linear-gradient(180deg, var(--wine), var(--wine-deep));
  color: oklch(97% 0.012 80);
  font-size: 13px;
  flex-shrink: 0;
}
.nav-nick {
  font-size: 13px;
  color: var(--ink-soft);
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-logout {
  background: none;
  border: 1px solid var(--ink-line);
  color: var(--ink-soft);
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: color 0.18s, border-color 0.18s;
}
.nav-logout:hover {
  color: var(--danger);
  border-color: var(--danger);
}

/* 窄屏（<768px）：链接区横向滚动，禁止换行（skill：导航必须单行） */
@media (max-width: 767px) {
  .top-nav { padding: 0 12px; }
  .nav-left { overflow-x: auto; scrollbar-width: none; }
  .nav-left::-webkit-scrollbar { display: none; }
  .nav-nick { display: none; }
}

.main-content {
  flex: 1;
  overflow: hidden;
}
</style>
