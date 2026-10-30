<template>
  <div class="login-page">
    <div class="login-card letter-card fold">
      <div class="login-header">
        <span class="logo-stamp anim-stamp">恋</span>
        <h1 class="login-title hand">恋爱解忧所</h1>
        <p class="subtitle">{{ subtitle }}</p>
      </div>

      <!-- ⛔ 重置态必须把这一整块（表单 + 切换 + 忘记入口）藏起来：
           否则两套表单**同时显示**，用户不知道该填哪个（实测截图抓到的）。 -->
      <template v-if="mode !== 'reset'">
      <form @submit.prevent="submit">
        <div class="field">
          <label for="login-username">用户名</label>
          <input id="login-username" v-model="form.username" type="text" placeholder="请输入用户名" required autocomplete="username" />
        </div>
        <div class="field">
          <label for="login-password">密码</label>
          <input id="login-password" v-model="form.password" type="password" placeholder="请输入密码" required autocomplete="current-password" />
        </div>

        <!-- ⛔ 出生年份（2026-10-13 合规改造）：**注册必填**。
             后端据此判定未成年人并自动开启未成年人模式（每日条数 + 22:00–6:00 休息），
             所以前端本地也要拦一次 —— 空值/越界都不许发请求。 -->
        <div v-if="!isLogin" class="field">
          <label for="register-birthyear">出生年份</label>
          <input
            id="register-birthyear"
            v-model="form.birthYear"
            type="number"
            inputmode="numeric"
            :min="MIN_YEAR"
            :max="maxYear"
            placeholder="例如 1998（用于判断是否需要未成年人模式）"
          />
        </div>

        <!-- ⛔ 协议勾选（必填）：未勾选**不许提交**，且必须给出可读提示（不能静默失败）。
             两个链接指向公开的法务页（未登录也能打开）。 -->
        <div v-if="!isLogin" class="agree-row">
          <label class="agree-label" for="register-agree">
            <input id="register-agree" v-model="form.agree" type="checkbox" class="agree-box" />
            <span class="agree-text">
              我已阅读并同意
              <router-link to="/terms" class="agree-link">《用户服务协议》</router-link>与
              <router-link to="/privacy" class="agree-link">《隐私政策》</router-link>
            </span>
          </label>
        </div>

        <p v-if="error" class="error-msg" role="alert">{{ error }}</p>

        <button type="submit" class="submit-btn" :disabled="loading">
          {{ loading ? '处理中…' : (isLogin ? '登录' : '注册') }}
        </button>
      </form>

      <p class="switch-link">
        {{ isLogin ? '还没有账号？' : '已有账号？' }}
        <a href="#" @click.prevent="toggleMode">{{ isLogin ? '去注册' : '去登录' }}</a>
      </p>

      <!-- ⛔ 用 button 而不是 a：既有测试用 find('a') 取**第一个** a（那是切换链接），
           多插一个 a 会让它取错元素。语义上这也不该是链接，是动作。 -->
      <p v-if="isLogin" class="forgot-row">
        <button type="button" class="forgot-btn" @click="startReset">忘记密码？用邮箱找回</button>
      </p>
      </template>

      <!-- 找回密码（第三态）：用户名 → 邮箱验证码 → 新密码 -->
      <form v-if="mode === 'reset'" class="reset-form" @submit.prevent="submitReset">
        <p class="reset-hint">输入你的用户名，我们会把验证码发到你**绑定的邮箱**。</p>
        <div class="field">
          <label for="reset-username">用户名</label>
          <input id="reset-username" v-model="reset.username" type="text" placeholder="你的用户名" autocomplete="username" />
        </div>
        <div class="field code-field">
          <label for="reset-code">邮箱验证码</label>
          <div class="code-row">
            <input id="reset-code" v-model="reset.code" type="text" inputmode="numeric" maxlength="6" placeholder="6 位数字" />
            <button type="button" class="code-btn" :disabled="countdown > 0 || sending" @click="sendCode">
              {{ countdown > 0 ? countdown + 's' : (sending ? '发送中…' : '发送验证码') }}
            </button>
          </div>
        </div>
        <div class="field">
          <label for="reset-password">新密码</label>
          <input id="reset-password" v-model="reset.newPassword" type="password" placeholder="至少 8 位" autocomplete="new-password" />
        </div>

        <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
        <p v-if="notice" class="notice-msg">{{ notice }}</p>

        <button type="submit" class="submit-btn" :disabled="loading">{{ loading ? '提交中…' : '重置密码' }}</button>
        <p class="switch-link">
          <a href="#" @click.prevent="backToLogin">返回登录</a>
        </p>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { loginApi, registerApi, sendResetCode, resetPassword } from '../api/index.js'
import { setToken, setUser, removeToken } from '../utils/auth.js'

const router = useRouter()
/** 'login' | 'register' | 'reset'。isLogin 保留为 computed —— 模板与既有测试的语义不变。 */
const mode = ref('login')
// ⛔ 必须是 === 'login'：写成 !== 'reset' 时**注册态也算 true** ⇒ 切换链接文字永远不变（实测被既有测试抓到）
const isLogin = computed(() => mode.value === 'login')
/** 三态各自的副标题 —— 别再拿 isLogin 当"是不是重置"用（那样重置态会显示注册文案） */
const subtitle = computed(() => (mode.value === 'reset'
  ? '用绑定的邮箱，把信箱的钥匙找回来'
  : (mode.value === 'login' ? '再回来，接着上一次的信写' : '开一个只属于你的信箱')))
const loading = ref(false)
const error = ref('')
const notice = ref('')
const form = reactive({ username: '', password: '', birthYear: '', agree: false })

// 出生年份的合法区间：1900 ~ 今年（今年动态取，跨年不用改代码）。
const MIN_YEAR = 1900
const maxYear = new Date().getFullYear()

// 找回密码
const reset = reactive({ username: '', code: '', newPassword: '' })
const sending = ref(false)
const countdown = ref(0)
let timer = null

function toggleMode() { mode.value = mode.value === 'login' ? 'register' : 'login'; error.value = ''; notice.value = '' }
function startReset() { mode.value = 'reset'; error.value = ''; notice.value = ''; reset.username = form.username || '' }
function backToLogin() { mode.value = 'login'; error.value = ''; notice.value = ''; if (timer) clearInterval(timer); countdown.value = 0 }

async function sendCode() {
  if (!reset.username.trim()) { error.value = '先填用户名'; return }
  sending.value = true
  error.value = ''
  notice.value = ''
  try {
    const d = (await sendResetCode(reset.username.trim())).data
    // ⛔ 后端**故意**不区分"账号不存在/没绑邮箱"（防用户枚举）⇒ 这里也不能说"已发送到你的邮箱"
    notice.value = d?.message || '如果该账号绑定了邮箱，验证码已发出（5 分钟内有效）'
    countdown.value = 60
    timer = setInterval(() => { countdown.value -= 1; if (countdown.value <= 0) clearInterval(timer) }, 1000)
  } catch (e) {
    error.value = e.response?.data?.message || '发送失败，请稍后再试'
  } finally {
    sending.value = false
  }
}

async function submitReset() {
  if (!reset.username.trim() || !reset.code.trim() || !reset.newPassword) { error.value = '用户名、验证码、新密码都要填'; return }
  if (reset.newPassword.length < 8) { error.value = '新密码至少 8 位'; return }
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const d = (await resetPassword(reset.username.trim(), reset.code.trim(), reset.newPassword)).data
    if (d && d.code === 200) {
      notice.value = d.message || '密码已重置，请用新密码登录'
      reset.code = ''
      reset.newPassword = ''
      setTimeout(() => { mode.value = 'login' }, 1200)
    } else {
      error.value = (d && d.message) || '重置失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '重置失败，请稍后再试'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!form.username.trim() || !form.password.trim()) {
    error.value = '用户名和密码不能为空'
    return
  }

  // ⛔ 注册态的本地前置校验（2026-10-13）：出生年份必填且必须在区间内、协议必须勾选。
  //    两条都不许静默失败 —— 后端也会拒，但让用户白等一轮往返是坏体验。
  let birthYear = null
  if (!isLogin.value) {
    const raw = String(form.birthYear ?? '').trim()
    const y = Number(raw)
    if (!raw || !Number.isInteger(y) || y < MIN_YEAR || y > maxYear) {
      error.value = `请填写出生年份（${MIN_YEAR}–${maxYear} 之间的四位数）`
      return
    }
    if (!form.agree) {
      error.value = '请先阅读并同意《用户服务协议》与《隐私政策》'
      return
    }
    birthYear = y
  }

  loading.value = true
  error.value = ''

  try {
    const res = isLogin.value
      ? await loginApi(form.username.trim(), form.password.trim())
      : await registerApi(form.username.trim(), form.password.trim(), birthYear)
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

onUnmounted(() => { if (timer) clearInterval(timer) })
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
.field input:focus { border-bottom-color: var(--accent); }
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
  background-color: var(--accent);
  background-image: linear-gradient(180deg, var(--accent), var(--accent-deep));
  border: none;
  border-radius: var(--r-pill);
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
  color: var(--accent-deep);
  font-family: var(--font-hand);
  font-size: 14px;
  text-decoration: none;
  border-bottom: 1px dashed var(--accent);
  margin-left: 4px;
}
.switch-link a:hover { letter-spacing: 0.06em; }
/* ---------- 忘记密码 / 找回（2026-10-07） ---------- */
.forgot-row { margin: 10px 0 0; text-align: center; }
.forgot-btn {
  background: none; border: none; cursor: pointer; padding: 2px 4px;
  font-size: 12.5px; color: var(--ink-soft); font-family: var(--font-hand); letter-spacing: 0.06em;
}
.forgot-btn:hover { color: var(--accent-text); text-decoration: underline; }
.reset-form { margin-top: 18px; padding-top: 16px; border-top: 1px dashed oklch(78% 0.02 60); }
.reset-hint { margin: 0 0 14px; font-size: 12.5px; line-height: 1.7; color: var(--ink-on-paper-soft); }
.code-row { display: flex; gap: 8px; align-items: center; }
.code-row input { flex: 1; }
.code-btn {
  flex-shrink: 0; cursor: pointer; padding: 9px 14px; font-size: 12.5px;
  background: var(--paper-card-warm); border: 1px solid var(--ink-line);
  border-radius: var(--r-ctl); color: var(--ink-on-paper); font-family: var(--font-hand);
}
.code-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent-text); }
.code-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.notice-msg { margin: 10px 0 0; font-size: 13px; color: var(--ok); }
/* ---------- 注册同意条款（2026-10-13） ---------- */
.agree-row { margin: -4px 0 12px; text-align: left; }
.agree-label {
  display: flex; align-items: flex-start; gap: 8px;
  cursor: pointer; font-size: 12.5px; line-height: 1.7;
  color: var(--ink-on-paper-soft);
}
.agree-box { margin-top: 2px; flex-shrink: 0; accent-color: var(--accent); cursor: pointer; }
.agree-text { flex: 1; }
.agree-link { color: var(--accent-text); text-decoration: none; border-bottom: 1px dashed var(--accent); }
.agree-link:hover { color: var(--accent-deep); }
</style>