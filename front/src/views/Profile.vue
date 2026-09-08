<template>
  <div class="profile-page">
    <!-- 信头 -->
    <div class="chat-header">
      <button class="back-btn" @click="$router.push('/')">←</button>
      <div class="letterhead">
        <span class="letterhead-stamp anim-stamp">我</span>
        <div class="letterhead-titles">
          <h1 class="letterhead-title hand">我的小窝</h1>
          <span class="letterhead-sub">— 把自己收拾得妥帖 —</span>
        </div>
      </div>
      <span class="header-space"></span>
    </div>

    <div class="profile-body">
      <!-- 左：基本资料 -->
      <section class="profile-main">
        <div class="letter-card card fold anim-letter-in">
          <div class="avatar-row">
            <button class="avatar-ring" :class="{ editing: showEmojiPicker }"
                    @click="showEmojiPicker = !showEmojiPicker" title="换个头像">
              <span>{{ displayAvatar }}</span>
            </button>
            <div class="who">
              <h2 class="hand nick">{{ form.nickname || profile.username }}</h2>
              <p class="username-line">@{{ profile.username }}
                <span v-if="profile.role === 'ADMIN'" class="role-badge">管理员</span>
              </p>
              <p class="joined-line">加入于 {{ joinedDate }}</p>
            </div>
          </div>

          <!-- emoji 头像选择盘 -->
          <div v-if="showEmojiPicker" class="emoji-picker">
            <button v-for="e in emojiSet" :key="e" class="emoji-opt"
                    :class="{ on: e === form.avatarEmoji }" @click="form.avatarEmoji = e; showEmojiPicker = false">
              {{ e }}
            </button>
          </div>

          <div class="field">
            <label>怎么称呼你（昵称）</label>
            <input v-model="form.nickname" maxlength="64" placeholder="留空则显示用户名" />
          </div>
          <div class="field">
            <label>一句话介绍（签名）</label>
            <textarea v-model="form.bio" maxlength="200" rows="2"
                      placeholder="例如：正在练习好好说爱这件事…"></textarea>
            <p class="counter">{{ form.bio.length }}/200</p>
          </div>
          <button class="btn-hand save-btn" :disabled="saving" @click="saveProfile">
            {{ saving ? '保存中…' : '保存修改' }}
          </button>
          <p v-if="profileMsg" class="form-msg" :class="{ err: profileMsgErr }">{{ profileMsg }}</p>
        </div>

        <div class="letter-card card fold anim-letter-in memo-note">
          <p class="memo-title hand">小窝说明</p>
          <p class="memo-body">
            头像与昵称会出现在信件回信人一栏。改了这里，恋恋下次回信就会这样称呼你。
          </p>
        </div>
      </section>

      <!-- 右：密码 + 危险区 -->
      <div class="profile-side">
        <section class="letter-card card fold anim-letter-in">
          <h3 class="section-title hand">改个密码</h3>
          <div class="field">
            <input v-model="pwd.old" type="password" placeholder="当前密码" autocomplete="current-password" />
          </div>
          <div class="field">
            <input v-model="pwd.next" type="password" placeholder="新密码（至少 8 位）" autocomplete="new-password" />
          </div>
          <div class="field">
            <input v-model="pwd.confirm" type="password" placeholder="再输一遍新密码" autocomplete="new-password" />
          </div>
          <button class="btn-hand save-btn" :disabled="pwdBusy" @click="changePwd">
            {{ pwdBusy ? '提交中…' : '更新密码' }}
          </button>
          <p v-if="pwdMsg" class="form-msg" :class="{ err: pwdMsgErr }">{{ pwdMsg }}</p>
        </section>

        <section class="letter-card card fold danger-zone">
          <h3 class="section-title hand danger-title">注销账号</h3>
          <p class="danger-desc">会删掉这里所有的信、记忆与角色，且无法找回。</p>
          <template v-if="!confirmDel">
            <button class="btn-danger" @click="confirmDel = true">注销我的账号</button>
          </template>
          <template v-else>
            <p class="danger-warn">真的要全部抹去吗？</p>
            <div class="danger-actions">
              <button class="btn-danger" :disabled="delBusy" @click="doDelete">{{ delBusy ? '注销中…' : '是的，删除' }}</button>
              <button class="btn-cancel" @click="confirmDel = false">再想想</button>
            </div>
          </template>
          <p v-if="delMsg" class="form-msg" :class="{ err: delMsgErr }">{{ delMsg }}</p>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getMe, updateProfileApi, changePasswordApi, deleteAccountApi } from '../api/index.js'
import { getUser, setUser, removeToken } from '../utils/auth.js'

const router = useRouter()
const profile = ref(getUser() || { username: '', role: 'USER' })
const form = reactive({ nickname: '', avatarEmoji: '', bio: '' })
const emojiSet = ['❤️', '🌙', '🌸', '🐰', '🌷', '💌', '🦋', '✨', '🍀', '⭐', '🎀', '🧸']
const showEmojiPicker = ref(false)

const saving = ref(false)
const profileMsg = ref('')
const profileMsgErr = ref(false)

const pwd = reactive({ old: '', next: '', confirm: '' })
const pwdBusy = ref(false)
const pwdMsg = ref('')
const pwdMsgErr = ref(false)

const confirmDel = ref(false)
const delBusy = ref(false)
const delMsg = ref('')
const delMsgErr = ref(false)

const displayAvatar = computed(() => form.avatarEmoji || (profile.value.username || '?').slice(0, 1).toUpperCase())
const joinedDate = computed(() => {
  const c = profile.value.createdAt
  if (!c) return '—'
  return String(c).slice(0, 10)
})

onMounted(async () => {
  try {
    const d = (await getMe()).data
    if (d && d.success) {
      profile.value = d
      form.nickname = d.nickname || ''
      form.avatarEmoji = d.avatarEmoji || ''
      form.bio = d.bio || ''
    }
  } catch (e) { /* 忽略——401 拦截器会处理 */ }
})

async function saveProfile() {
  saving.value = true
  profileMsg.value = ''
  try {
    const d = (await updateProfileApi({
      nickname: form.nickname.trim() || form.nickname,
      avatarEmoji: form.avatarEmoji,
      bio: form.bio.trim()
    })).data
    if (d && d.success) {
      const prev = getUser() || {}
      setUser({
        username: profile.value.username || prev.username,
        role: profile.value.role || prev.role || 'USER',
        nickname: form.nickname.trim() || null,
        avatarEmoji: form.avatarEmoji || null,
        bio: form.bio.trim() || null,
        createdAt: profile.value.createdAt
      })
      profileMsg.value = d.message || '已保存'
      profileMsgErr.value = false
      // 通知顶部导航即时刷新（同页保存无路由变化，watch 不触发）
      window.dispatchEvent(new CustomEvent('lh:user-updated'))
    } else {
      profileMsg.value = (d && d.message) || '保存失败'
      profileMsgErr.value = true
    }
  } catch (e) {
    profileMsg.value = e.response?.data?.message || e.message || '网络错误'
    profileMsgErr.value = true
  } finally {
    saving.value = false
  }
}

async function changePwd() {
  pwdMsg.value = ''
  pwdMsgErr.value = false
  if (!pwd.old || !pwd.next) { pwdMsg.value = '请填写当前密码与新密码'; pwdMsgErr.value = true; return }
  if (pwd.next.length < 8) { pwdMsg.value = '新密码至少 8 位'; pwdMsgErr.value = true; return }
  if (pwd.next !== pwd.confirm) { pwdMsg.value = '两次输入的新密码不一致'; pwdMsgErr.value = true; return }
  pwdBusy.value = true
  try {
    const d = (await changePasswordApi({ oldPassword: pwd.old, newPassword: pwd.next })).data
    if (d && d.success) {
      pwdMsg.value = d.message || '密码已修改，请重新登录'
      pwdMsgErr.value = false
      pwd.old = pwd.next = pwd.confirm = ''
      setTimeout(() => { removeToken(); router.push('/login') }, 1400)
    } else {
      pwdMsg.value = (d && d.message) || '修改失败'
      pwdMsgErr.value = true
    }
  } catch (e) {
    pwdMsg.value = e.response?.data?.message || e.message || '网络错误'
    pwdMsgErr.value = true
  } finally {
    pwdBusy.value = false
  }
}

async function doDelete() {
  delBusy.value = true
  delMsg.value = ''
  try {
    const d = (await deleteAccountApi()).data
    if (d && d.success) {
      removeToken()
      router.push('/login')
    } else {
      delMsg.value = (d && d.message) || '注销失败'
      delMsgErr.value = true
    }
  } catch (e) {
    delMsg.value = e.response?.data?.message || e.message || '网络错误'
    delMsgErr.value = true
  } finally {
    delBusy.value = false
  }
}
</script>

<style scoped>
.profile-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(oklch(70% 0.02 78 / 0.16) 0.6px, transparent 0.8px),
    var(--paper);
  background-size: 21px 21px;
}

/* 信头（与 LoveChat 同语言） */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 22px 12px;
  flex-shrink: 0;
  border-bottom: 1.5px solid var(--ink-line);
  background: linear-gradient(180deg, oklch(99% 0.008 78), var(--paper));
  box-shadow: 0 8px 18px -16px oklch(35% 0.04 62 / 0.5);
  position: relative;
  z-index: 2;
}
.letterhead { display: flex; align-items: center; gap: 12px; }
.letterhead-stamp {
  display: inline-flex; align-items: center; justify-content: center;
  width: 42px; height: 42px; border-radius: 50%;
  border: 2px dashed var(--wine); color: var(--wine-deep);
  font-family: var(--font-hand); font-size: 20px; font-weight: 700;
  transform: rotate(-10deg); background: var(--paper-card); flex-shrink: 0;
}
.letterhead-titles { display: flex; flex-direction: column; line-height: 1.15; }
.letterhead-title { font-size: 22px; margin: 0; letter-spacing: 0.14em; }
.letterhead-sub { font-family: var(--font-hand); font-size: 11px; color: var(--ink-faint); letter-spacing: 0.2em; margin-top: 2px; }
.back-btn {
  font-family: var(--font-hand); font-size: 20px; color: var(--ink-soft);
  background: transparent; border: none; cursor: pointer;
  padding: 6px 10px; border-radius: 50%;
  transition: background 0.15s, transform 0.15s;
}
.back-btn:hover { background: var(--paper-deep); transform: translateX(-2px); }
.header-space { width: 38px; }

.profile-body {
  flex: 1;
  overflow-y: auto;
  padding: 26px clamp(14px, 4vw, 36px);
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 20px;
  align-items: start;
  max-width: 980px;
  margin: 0 auto;
  width: 100%;
}
@media (max-width: 860px) {
  .profile-body { grid-template-columns: 1fr; }
  .profile-side { display: contents; }
}
.profile-main { display: flex; flex-direction: column; gap: 16px; }
.profile-side { display: flex; flex-direction: column; gap: 16px; }

.card { padding: 24px 26px; }
.avatar-row { display: flex; align-items: center; gap: 16px; margin-bottom: 18px; }
.avatar-ring {
  width: 78px; height: 78px; border-radius: 50%;
  border: 2px dashed var(--wine);
  background: linear-gradient(180deg, var(--wine-soft), var(--paper-card));
  font-size: 36px;
  display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer; flex-shrink: 0;
  box-shadow: 0 8px 16px -12px oklch(40% 0.1 25 / 0.5);
  transition: transform 0.18s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.avatar-ring:hover { transform: rotate(-6deg) scale(1.04); }
.avatar-ring.editing { transform: rotate(-6deg) scale(1.04); border-style: solid; }
.who .nick { margin: 0 0 4px; font-size: 22px; letter-spacing: 0.08em; color: var(--ink); }
.username-line { margin: 0 0 3px; font-size: 13px; color: var(--ink-faint); }
.role-badge {
  display: inline-block; margin-left: 6px; padding: 1px 8px;
  border-radius: 10px; font-size: 11px; color: var(--wine-deep);
  background: var(--wine-soft); border: 1px dashed var(--wine);
  font-family: var(--font-hand);
}
.joined-line { margin: 0; font-size: 12px; color: var(--ink-faint); font-family: var(--font-hand); letter-spacing: 0.04em; }

.emoji-picker {
  display: flex; flex-wrap: wrap; gap: 8px;
  padding: 12px 14px; margin-bottom: 16px;
  background: var(--paper-deep);
  border: 1px dashed var(--ink-line);
  border-radius: 12px;
  animation: pop-in 0.2s ease-out both;
}
@keyframes pop-in { from { opacity: 0; transform: scale(0.95); } to { opacity: 1; transform: scale(1); } }
.emoji-opt {
  width: 40px; height: 40px; font-size: 20px;
  border: 1.4px solid transparent; border-radius: 10px;
  background: var(--paper-card); cursor: pointer;
  transition: transform 0.15s cubic-bezier(0.34, 1.56, 0.64, 1), border-color 0.15s;
}
.emoji-opt:hover { transform: scale(1.15); }
.emoji-opt.on { border-color: var(--wine); background: var(--wine-soft); }

.field { margin-bottom: 16px; position: relative; }
.field label {
  display: block; font-family: var(--font-hand); font-size: 13px;
  color: var(--ink-soft); letter-spacing: 0.12em; margin-bottom: 6px;
}
.field input, .field textarea {
  width: 100%; font-size: 14.5px; color: var(--ink);
  background: var(--paper-card);
  border: 1px solid var(--ink-line);
  border-radius: 8px;
  padding: 9px 12px;
  outline: none; resize: none;
  transition: border-color 0.18s, box-shadow 0.18s;
  font-family: var(--font-body);
}
.field input:focus, .field textarea:focus {
  border-color: var(--wine);
  box-shadow: 0 0 0 3px oklch(92% 0.03 25 / 0.5);
}
.field input::placeholder, .field textarea::placeholder { color: var(--ink-faint); font-family: var(--font-hand); }
.counter { position: absolute; right: 10px; bottom: -16px; font-size: 11px; color: var(--ink-faint); }

.btn-hand.save-btn {
  border: none; border-radius: 20px;
  background: linear-gradient(180deg, var(--wine), var(--wine-deep));
  color: oklch(98% 0.012 78);
  font-family: var(--font-hand); font-size: 15px; letter-spacing: 0.14em;
  padding: 0.6em 2em; cursor: pointer;
  box-shadow: 0 8px 16px -10px oklch(40% 0.1 25 / 0.6);
  transition: transform 0.16s cubic-bezier(0.34, 1.56, 0.64, 1), opacity 0.15s;
}
.save-btn:hover:not(:disabled) { transform: translateY(-1px); }
.save-btn:disabled { opacity: 0.55; cursor: not-allowed; }

.form-msg { margin: 10px 0 0; font-size: 13px; color: oklch(52% 0.08 150); }
.form-msg.err { color: var(--danger, oklch(55% 0.15 27)); }

.section-title { margin: 0 0 14px; font-size: 18px; letter-spacing: 0.12em; color: var(--ink); }

.memo-note { border-left: 3px solid var(--wine); background: linear-gradient(135deg, var(--paper-card), oklch(97% 0.015 78)); }
.memo-title { margin: 0 0 6px; font-size: 16px; color: var(--wine-deep); }
.memo-body { margin: 0; font-size: 13px; color: var(--ink-soft); line-height: 1.8; }

.danger-zone { border: 1.4px dashed oklch(60% 0.12 27 / 0.5); }
.danger-title { color: oklch(55% 0.14 27); }
.danger-desc { font-size: 13px; color: var(--ink-soft); line-height: 1.7; margin: 0 0 14px; }
.danger-warn { font-size: 14px; color: oklch(55% 0.14 27); font-weight: 700; margin: 0 0 10px; font-family: var(--font-hand); }
.danger-actions { display: flex; gap: 10px; }
.btn-danger {
  border: none; border-radius: 14px 10px 13px 9px;
  background: oklch(55% 0.14 27); color: oklch(98% 0.012 78);
  font-family: var(--font-hand); font-size: 13.5px; letter-spacing: 0.08em;
  padding: 9px 18px; cursor: pointer;
  transition: transform 0.15s, opacity 0.15s;
}
.btn-danger:hover:not(:disabled) { transform: translateY(-1px); }
.btn-danger:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-cancel {
  border: 1.4px solid var(--ink-line); background: var(--paper-card);
  border-radius: 14px 10px 13px 9px; font-family: var(--font-hand);
  font-size: 13.5px; color: var(--ink-soft); padding: 9px 18px; cursor: pointer;
}
.btn-cancel:hover { border-color: var(--wine); color: var(--wine-deep); }
</style>
