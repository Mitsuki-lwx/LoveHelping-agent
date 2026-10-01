<template>
  <div class="login-page">
    <div class="login-card letter-card fold">
      <div class="login-header">
        <span class="logo-stamp anim-stamp">恋</span>
        <h1 class="login-title hand">恋爱解忧所</h1>
        <p class="subtitle">{{ isLogin ? '再回来，接着上一次的信写' : '开一个只属于你的信箱' }}</p>
      </div>

      <form @submit.prevent="submit">
        <div class="field">
          <label for="login-username">用户名</label>
          <input id="login-username" v-model="form.username" type="text" placeholder="请输入用户名" required autocomplete="username" />
        </div>
        <div class="field">
          <label for="login-password">密码</label>
          <input id="login-password" v-model="form.password" type="password" placeholder="请输入密码" required autocomplete="current-password" />
        </div>

        <p v-if="error" class="error-msg" role="alert">{{ error }}</p>

        <button type="submit" class="submit-btn" :disabled="loading">
          {{ loading ? '处理中…' : (isLogin ? '登录' : '注册') }}
        </button>
      </form>

      <p class="switch-link">
        {{ isLogin ? '还没有账号？' : '已有账号？' }}
        <a href="#" @click.prevent="isLogin = !isLogin">{{ isLogin ? '去注册' : '去登录' }}</a>
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { loginApi, registerApi } from '../api/index.js'
import { setToken, setUser, removeToken } from '../utils/auth.js'

const router = useRouter()
const isLogin = ref(true)
const loading = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username.trim() || !form.password.trim()) {
    error.value = '用户名和密码不能为空'
    return
  }
  loading.value = true
  error.value = ''

  try {
    const fn = isLogin.value ? loginApi : registerApi
    const res = await fn(form.username.trim(), form.password.trim())
    const data = res.data

    if (data.token) {
      setToken(data.token)
      setUser({ username: data.username || form.username.trim(), role: data.role || 'USER' })
      router.push('/')
    } else if (data.success === false) {
      error.value = data.message || '操作失败'
    }
  } catch (e) {
    const msg = e.response?.data?.message || e.message || '网络错误'
    error.value = msg
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ============================================================
   登录页（2026-10-01 重做）：深夜书桌上的一张暖纸信卡。
   改动：深色氛围 + 信卡是页面上唯一的"纸"（强对比焦点）；
   a11y：label 关联（label-above-input，skill §4.6）、autocomplete、role=alert。
   ============================================================ */
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.login-card {
  width: min(400px, 94%);
  padding: 40px 38px 30px;
  animation: letter-in 0.5s cubic-bezier(0.16, 1, 0.3, 1) both;
}
.login-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  margin-bottom: 26px;
}
.logo-stamp {
  width: 56px;
  height: 56px;
  font-size: 26px;
  border-width: 2.5px;
  margin-bottom: 4px;
}
.login-title { font-size: 26px; margin: 0; letter-spacing: 0.16em; color: var(--ink-on-paper); }
.subtitle {
  font-family: var(--font-hand);
  font-size: 13px;
  color: oklch(48% 0.025 62);
  letter-spacing: 0.06em;
  margin: 0;
}
.field { margin-bottom: 18px; text-align: left; }
.field label {
  display: block;
  font-family: var(--font-hand);
  font-size: 13px;
  color: oklch(40% 0.03 62);
  letter-spacing: 0.12em;
  margin-bottom: 6px;
}
.field input {
  width: 100%;
  font-family: var(--font-body);
  font-size: 15px;
  color: var(--ink-on-paper);
  background: transparent;
  border: none;
  border-bottom: 1.6px solid oklch(74% 0.024 62);
  padding: 8px 2px;
  outline: none;
  transition: border-color 0.18s;
}
.field input:focus { border-bottom-color: var(--wine); }
.field input::placeholder { color: oklch(58% 0.02 62); }
.error-msg {
  color: var(--danger);
  font-size: 13px;
  margin: 0 0 12px;
  text-align: center;
}
.submit-btn {
  width: 100%;
  font-family: var(--font-hand);
  font-size: 16px;
  letter-spacing: 0.2em;
  color: oklch(97% 0.012 80);
  background: linear-gradient(180deg, var(--wine), var(--wine-deep));
  border: none;
  border-radius: 999px;
  padding: 12px 0;
  cursor: pointer;
  margin-top: 8px;
  transition: transform 0.16s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.16s, opacity 0.15s;
  box-shadow: 0 10px 22px -10px oklch(30% 0.1 28 / 0.8);
}
.submit-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 14px 26px -12px oklch(36% 0.12 28 / 0.9); }
.submit-btn:active:not(:disabled) { transform: translateY(1px) scale(0.98); }
.submit-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.switch-link {
  margin-top: 20px;
  text-align: center;
  font-size: 13.5px;
  color: oklch(46% 0.02 62);
}
.switch-link a {
  color: var(--wine-deep);
  font-family: var(--font-hand);
  font-size: 14px;
  text-decoration: none;
  border-bottom: 1px dashed var(--wine);
  margin-left: 4px;
}
.switch-link a:hover { letter-spacing: 0.06em; }
</style>
