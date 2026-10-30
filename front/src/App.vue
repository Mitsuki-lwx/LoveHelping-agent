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
        <!-- 心情主题切换：主题名就是**心情名**（恋爱产品不该叫"粉色/蓝色"） -->
        <div class="theme-picker">
          <button class="theme-btn" :title="'换个心情的界面 · 当前：' + theme" @click.stop="themeOpen = !themeOpen">
            <PhPalette :size="15" weight="bold" aria-hidden="true" />
            <span class="theme-btn-name">{{ theme }}</span>
          </button>
          <div v-if="themeOpen" class="theme-menu" @click.stop>
            <button
              v-for="t in THEMES"
              :key="t.id"
              :class="['theme-opt', { on: t.id === theme }]"
              @click="pickTheme(t.id)"
            >
              <!-- ⛔ 把 data-theme 挂在色块**自己**身上：属性选择器对任意元素生效，
                   于是这块小圆点会解析成**那一套**主题的 --accent（不用在 JS 里抄一份颜色）。 -->
              <span class="theme-swatch" :data-theme="t.id" aria-hidden="true"></span>
              <span class="theme-opt-text"><b>{{ t.label }}</b><i>{{ t.hint }}</i></span>
              <PhCheck v-if="t.id === theme" :size="15" weight="bold" aria-hidden="true" />
            </button>
          </div>
        </div>
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
import { PhPalette, PhCheck } from '@phosphor-icons/vue'
import { THEMES, getTheme, setTheme } from './utils/theme.js'
import { refreshProfile } from './utils/profile.js'

const theme = ref(getTheme())
const themeOpen = ref(false)
function pickTheme(id) {
  theme.value = setTheme(id)
  themeOpen.value = false
}
// 别的地方（如今日心情打卡）也能切主题 —— 用事件同步选中态，避免两处各自维护
function onThemeChanged(e) { theme.value = e.detail.theme }
function closeThemeMenu() { themeOpen.value = false }
onMounted(() => {
  window.addEventListener('lh:theme-changed', onThemeChanged)
  document.addEventListener('click', closeThemeMenu)
  // 个人资料的**唯一取数点**：应用外壳启动时刷一次，写进缓存；
  // 其他组件（首页纪念日卡等）一律读缓存，不各自发请求（见 utils/profile.js 的教训注释）。
  if (isAuthenticated()) refreshProfile()
})
onUnmounted(() => {
  window.removeEventListener('lh:theme-changed', onThemeChanged)
  document.removeEventListener('click', closeThemeMenu)
})

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
  border: 1.8px dashed var(--accent-text);
  color: var(--accent-text);
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
  /* ⛔ 成对取色：--accent-glow 当字 + --accent-wash 当底，在深色主题成立，
     换到浅色主题就是"浅字浮浅底"（实测 2.42）。徽章类一律走 --chip-*。 */
  color: var(--chip-fg);
  background: var(--chip-bg);
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
.nav-me:hover { border-color: var(--accent); transform: translateY(-1px); }
.nav-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 27px; height: 27px;
  border-radius: 50%;
  background: linear-gradient(180deg, var(--accent), var(--accent-deep));
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
/* ---------- 心情主题切换器 ---------- */
.theme-picker { position: relative; }
.theme-btn {
  display: inline-flex; align-items: center; gap: 7px;
  background: transparent; border: 1px solid var(--ink-line);
  color: var(--ink-soft); border-radius: var(--r-pill);
  font-family: var(--font-hand); font-size: 13px; letter-spacing: 0.08em;
  padding: 5px 13px; cursor: pointer; transition: all 0.18s;
}
.theme-btn:hover { border-color: var(--accent); color: var(--accent-text); }
.theme-btn-name { line-height: 1; }
.theme-menu {
  position: absolute; right: 0; top: calc(100% + 10px); z-index: 40;
  display: flex; flex-direction: column; gap: 2px; min-width: 210px; padding: 7px;
  background: var(--paper-card); border: 1px solid var(--ink-line);
  border-radius: var(--r-card); box-shadow: var(--sh-2);
}
.theme-opt {
  display: flex; align-items: center; gap: 10px; width: 100%;
  background: transparent; border: none; cursor: pointer; text-align: left;
  padding: 8px 10px; border-radius: var(--r-ctl); color: var(--ink-on-paper);
  transition: background 0.15s;
}
.theme-opt:hover { background: var(--accent-wash); }
.theme-opt.on { background: var(--accent-wash); }
.theme-swatch {
  width: 18px; height: 18px; flex-shrink: 0; border-radius: var(--r-seal);
  background: var(--accent); border: 2px solid var(--paper-card);
  box-shadow: 0 0 0 1px var(--ink-line);
}
.theme-opt-text { display: flex; flex-direction: column; gap: 1px; flex: 1; }
.theme-opt-text b { font-size: 13.5px; font-weight: 700; font-family: var(--font-hand); letter-spacing: 0.06em; }
.theme-opt-text i { font-size: 11.5px; font-style: normal; color: var(--ink-on-paper-soft); }
@media (max-width: 720px) {
  .theme-btn-name { display: none; }
  .theme-btn { padding: 5px 9px; }
}
</style>