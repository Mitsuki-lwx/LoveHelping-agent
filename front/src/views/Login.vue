<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <div class="logo">💕</div>
        <h1>LoveHelping</h1>
        <p class="subtitle">{{ isLogin ? '登录后开始体验' : '注册一个新账号' }}</p>
      </div>

      <form @submit.prevent="submit">
        <div class="field">
          <label>用户名</label>
          <input v-model="form.username" type="text" placeholder="请输入用户名" required />
        </div>
        <div class="field">
          <label>密码</label>
          <input v-model="form.password" type="password" placeholder="请输入密码" required />
        </div>

        <p v-if="error" class="error-msg">{{ error }}</p>

        <button type="submit" class="submit-btn" :disabled="loading">
          {{ loading ? '处理中...' : (isLogin ? '登录' : '注册') }}
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
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--bg-primary) 0%, var(--bg-secondary) 100%);
}

.login-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  padding: 40px;
  width: 100%;
  max-width: 400px;
  box-shadow: 0 4px 24px rgba(232, 104, 128, 0.1);
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.logo {
  font-size: 48px;
  margin-bottom: 8px;
}

.login-header h1 {
  font-size: 24px;
  color: var(--text-white);
}

.subtitle {
  color: var(--text-secondary);
  font-size: 14px;
  margin-top: 4px;
}

.field {
  margin-bottom: 16px;
}

.field label {
  display: block;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}

.field input {
  width: 100%;
  padding: 12px 14px;
  border: 1px solid var(--border-color);
  border-radius: 10px;
  background: var(--input-bg);
  color: var(--text-primary);
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.field input:focus {
  border-color: var(--accent);
}

.error-msg {
  color: #e74c3c;
  font-size: 13px;
  margin-bottom: 12px;
  text-align: center;
}

.submit-btn {
  width: 100%;
  padding: 12px;
  border: none;
  border-radius: 10px;
  background: var(--accent);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}

.submit-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.switch-link {
  text-align: center;
  margin-top: 20px;
  font-size: 13px;
  color: var(--text-secondary);
}

.switch-link a {
  color: var(--accent);
  text-decoration: none;
}

.switch-link a:hover {
  text-decoration: underline;
}
</style>
