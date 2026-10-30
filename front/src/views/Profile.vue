<template>
  <div class="profile-page">
    <!-- 信头 -->
    <div class="chat-header">
      <button class="back-btn" @click="$router.push('/')"><PhArrowLeft :size="18" weight="bold" aria-hidden="true" /></button>
      <div class="letterhead">
        <span class="letterhead-stamp anim-stamp">我</span>
        <div class="letterhead-titles">
          <h1 class="letterhead-title hand">我的小窝</h1>
          <span class="letterhead-sub">把自己收拾得妥帖</span>
        </div>
      </div>
      <span class="header-space"></span>
    </div>

    <div class="profile-body">
      <!-- ══ 左：资料 / 概览 / 关系档案 ══ -->
      <section class="profile-main">
        <!-- ① 基本资料（保持既有契约：.save-btn 与 .form-msg 必须是 main 里第一个） -->
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

        <!-- ② 我的概览：把散在各处的"我的数据"聚到一处（数字来自真实接口，不是写死的） -->
        <div class="letter-card card fold anim-letter-in">
          <h3 class="section-title hand">我的概览</h3>
          <div class="stat-row">
            <div class="stat">
              <b>{{ stats.letters }}</b><span>封信</span>
              <i>写过的对话</i>
            </div>
            <div class="stat">
              <b>{{ stats.facts }}</b><span>条</span>
              <i>恋恋记住的事</i>
            </div>
            <div class="stat">
              <b>{{ stats.todoOpen }}</b><span>件</span>
              <i>说好要做的事</i>
            </div>
            <div class="stat">
              <b>{{ stats.rehearsals }}</b><span>场</span>
              <i>角色排练</i>
            </div>
          </div>
          <p v-if="stats.moodText" class="stat-mood">
            <PhPlant :size="15" weight="bold" class="ico" aria-hidden="true" /> {{ stats.moodText }}
          </p>
          <div class="entry-row">
            <button class="entry-chip" @click="$router.push('/memory')">
              <PhNotebook :size="15" weight="bold" aria-hidden="true" /> 记忆档案
            </button>
            <button class="entry-chip" @click="$router.push('/history')">
              <PhEnvelopeOpen :size="15" weight="bold" aria-hidden="true" /> 旧信存档
            </button>
            <button class="entry-chip" @click="$router.push('/sandbox')">
              <PhMaskHappy :size="15" weight="bold" aria-hidden="true" /> 角色模拟屋
            </button>
          </div>
        </div>

        <!-- ③ 关系档案（平台特色）：这里改的东西会**喂进模型**，直接改变恋恋的回信口径 -->
        <div class="letter-card card fold anim-letter-in rel-card">
          <h3 class="section-title hand">关系档案</h3>
          <p class="rel-hint">
            这些会告诉恋恋 —— 回信时它会更贴合你的处境，称呼也更一致。
          </p>

          <div class="field">
            <label>我现在的关系阶段</label>
            <div class="stage-row">
              <button
                v-for="s in STAGES"
                :key="s"
                :class="['stage-chip', { on: rel.stage === s }]"
                @click="rel.stage = rel.stage === s ? s : s"
              >{{ s }}</button>
            </div>
          </div>

          <div class="field">
            <label>我怎么称呼 TA</label>
            <input v-model="rel.partnerAlias" maxlength="32" placeholder="例如：小朋友 / 他 / 她的名字" />
            <p class="field-tip">留空就不特意称呼。恋恋回信时会用这个词。</p>
          </div>

          <div class="field">
            <label>在一起的纪念日</label>
            <input v-model="annivInput" type="date" class="date-input" />
            <p class="field-tip">填了之后，首页会显示「在一起第几天」。</p>
          </div>

          <button class="btn-hand save-btn rel-save" :disabled="relSaving" @click="saveRelationship">
            {{ relSaving ? '保存中…' : '保存关系档案' }}
          </button>
          <p v-if="relMsg" class="form-msg" :class="{ err: relMsgErr }">{{ relMsg }}</p>
        </div>
      </section>

      <!-- ══ 右：账号与安全 / 数据主权 / 危险区（危险区永远放最后） ══ -->
      <div class="profile-side">
        <section class="letter-card card fold anim-letter-in pwd-card">
          <h3 class="section-title hand">账号与安全</h3>
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

          <div class="acct-info">
            <p><span>用户名</span><b>@{{ profile.username || '—' }}</b></p>
            <p><span>账号状态</span><b>{{ profile.enabled === false ? '已停用' : '正常' }}</b></p>
            <p><span>注册时间</span><b>{{ joinedDate }}</b></p>
          </div>
          <button class="btn-ghost" @click="logout">退出登录</button>
        </section>

        <section class="letter-card card fold anim-letter-in email-card">
          <h3 class="section-title hand">邮箱</h3>
          <!-- 已绑定：显示 + 可换绑 -->
          <template v-if="email.bound && !email.editing">
            <p class="email-line">
              <b>{{ email.value }}</b>
              <span v-if="email.verified" class="email-badge">已验证</span>
            </p>
            <p class="field-tip">忘记密码时，验证码会发到这个邮箱。</p>
            <button class="btn-ghost" @click="startBind">换绑邮箱</button>
          </template>
          <!-- 未绑定 / 换绑：发码 + 校验 -->
          <template v-else>
            <p class="field-tip">绑定邮箱后，忘记密码可以用验证码找回。</p>
            <div class="field">
              <input v-model="email.input" type="email" placeholder="你的邮箱" autocomplete="email" />
            </div>
            <div class="field code-field">
              <div class="code-row">
                <input v-model="email.code" type="text" inputmode="numeric" maxlength="6" placeholder="6 位验证码" />
                <button type="button" class="code-btn" :disabled="email.countdown > 0 || email.sending" @click="sendBindCode">
                  {{ email.countdown > 0 ? email.countdown + 's' : (email.sending ? '发送中…' : '发送验证码') }}
                </button>
              </div>
            </div>
            <button class="btn-hand save-btn" :disabled="email.binding" @click="doBindEmail">
              {{ email.binding ? '绑定中…' : '绑定邮箱' }}
            </button>
            <button v-if="email.editing" class="btn-ghost" @click="email.editing = false">取消</button>
            <p v-if="email.msg" class="form-msg" :class="{ err: email.err }">{{ email.msg }}</p>
          </template>
        </section>

        <section class="letter-card card fold anim-letter-in data-card">
          <h3 class="section-title hand">我的数据</h3>
          <p class="data-desc">
            你的信、记忆与角色都只属于这个账号；注销后按保留期清除，不会出现在别人的对话里。
          </p>
          <button class="btn-hand save-btn" :disabled="exporting" @click="exportData">
            {{ exporting ? '打包中…' : '导出我的数据' }}
          </button>
          <p class="field-tip">导出一个 JSON 文件：资料、关系档案、记忆、行动清单与全部信件正文。</p>
          <p v-if="exportMsg" class="form-msg" :class="{ err: exportErr }">{{ exportMsg }}</p>
        </section>

        <!-- ⛔ 进化模块「单独同意」（2026-10-13，docs/07 §5.4）：
             默认**关**。文案必须如实说清"开了会发生什么"（会沉淀进知识库、别人能检索到）
             以及"不会发生什么"（改写、不留原文与身份信息）—— 含糊的同意不算单独同意。 -->
        <section class="letter-card card fold anim-letter-in consent-card">
          <h3 class="section-title hand">允许用我的对话提炼通用技巧</h3>
          <p class="data-desc">
            开启后，系统会从你的对话里提炼<strong>通用沟通技巧</strong>并沉淀进知识库，
            <strong>其他用户可能检索到</strong>这些技巧；技巧文本会经模型改写，
            <strong>不保留你的原文与身份信息</strong>。
          </p>
          <label class="consent-row" for="evolution-consent">
            <input
              id="evolution-consent"
              v-model="consent"
              class="consent-toggle"
              type="checkbox"
              :disabled="consentBusy"
              @change="saveConsent"
            />
            <span class="consent-text">{{ consent ? '已开启（可随时关闭）' : '已关闭（默认）' }}</span>
          </label>
          <p class="field-tip">关闭时，你的对话不会被用于提炼技巧。此项可随时更改。</p>
          <p v-if="consentMsg" class="form-msg" :class="{ err: consentErr }">{{ consentMsg }}</p>
        </section>

        <!-- ⛔ 回访邮件 = **主动触达**（ADR-92）。与"用对话提炼技巧"是两类同意，必须分开给。
             默认关；文案要如实说清"多久一封""谁会收到""随时可关"。 -->
        <section class="letter-card card fold anim-letter-in consent-card">
          <h3 class="section-title hand">邮件回访</h3>
          <p class="data-desc">
            偶尔给你写一封，提醒你这里还在。只在<strong>邮箱验证后</strong>发送，
            <strong>14 天内最多一封</strong>；<strong>未成年人不会收到</strong>。随时可关。
          </p>
          <label class="consent-row" for="reengage-consent">
            <input
              id="reengage-consent"
              v-model="reengage"
              class="consent-toggle"
              type="checkbox"
              :disabled="reengageBusy"
              @change="saveReengage"
            />
            <span class="consent-text">{{ reengage ? '已开启（可随时关闭）' : '已关闭（默认）' }}</span>
          </label>
          <p v-if="reengageMsg" class="form-msg" :class="{ err: reengageErr }">{{ reengageMsg }}</p>
        </section>

        <!-- ⛔ 投诉举报入口（2026-10-13 合规）：监管要求"设置便捷的投诉举报入口，公布处理流程和反馈时限"。
             所以这张卡片本身就写明时限 —— 用户不必点进去才知道多久有回音；
             按钮用主按钮样式（.save-btn 同款视觉权重），不埋在设置二级页里。 -->
        <section class="letter-card card fold anim-letter-in report-entry">
          <h3 class="section-title hand">投诉举报</h3>
          <p class="data-desc">
            遇到违规内容、隐私泄露或骚扰，都可以在这里提交举报；
            我们自受理之日起 {{ FEEDBACK_DAYS }} 个工作日内完成核查并反馈结果。
          </p>
          <button class="btn-hand save-btn" @click="router.push('/report')">我要投诉举报</button>
          <p class="field-tip">提交后可回看自己举报的分类、时间与处理状态。</p>
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
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  PhArrowLeft, PhPlant, PhNotebook, PhEnvelopeOpen, PhMaskHappy,
} from '@phosphor-icons/vue'
import {
  getMe, updateProfileApi, changePasswordApi, deleteAccountApi,
  sendEmailCode, bindEmail,
  getRelationship, updateRelationship,
  listConversations, getMyMemoryFacts, listActionItems, listSandboxSessions,
  sentimentTimeline, getConversationMessages, updateEvolutionConsent, updateReengageConsent,
} from '../api/index.js'
import { getUser, setUser, removeToken } from '../utils/auth.js'
import { COMPLAINT_FEEDBACK_WORKING_DAYS as FEEDBACK_DAYS, AI_EXPORT_NOTE } from '../utils/legal.js'

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

// ---- 我的概览（数字来自真实接口；取不到就显示 0，不编造）----
const stats = reactive({ letters: 0, facts: 0, todoOpen: 0, rehearsals: 0, moodText: '' })

// ---- 关系档案（会喂进模型提示词，见后端 MemoryStore）----
const STAGES = ['单身', '暧昧中', '恋爱中', '异地恋', '已婚', '其他']
const rel = reactive({ stage: '', partnerAlias: '' })
const annivInput = ref('')
const relSaving = ref(false)
const relMsg = ref('')
const relMsgErr = ref(false)

// ---- 邮箱绑定（2026-10-07）----
const email = reactive({ value: '', verified: false, bound: false, editing: false, input: '', code: '', sending: false, binding: false, countdown: 0, msg: '', err: false })
let emailTimer = null

function startBind() { email.editing = true; email.input = ''; email.code = ''; email.msg = ''; email.err = false }

async function sendBindCode() {
  const to = (email.input || '').trim()
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(to)) { email.msg = '请填一个正确的邮箱'; email.err = true; return }
  email.sending = true; email.msg = ''; email.err = false
  try {
    const d = (await sendEmailCode(to)).data
    if (d && d.code !== 200) { email.msg = d.message || '发送失败'; email.err = true; return }
    email.msg = d?.message || '验证码已发出（5 分钟内有效）'
    email.countdown = 60
    if (emailTimer) clearInterval(emailTimer)
    emailTimer = setInterval(() => { email.countdown -= 1; if (email.countdown <= 0) clearInterval(emailTimer) }, 1000)
  } catch (e) {
    email.msg = e.response?.data?.message || '发送失败，请稍后再试'; email.err = true
  } finally { email.sending = false }
}

async function doBindEmail() {
  const to = (email.input || '').trim()
  const code = (email.code || '').trim()
  if (!to || !code) { email.msg = '邮箱和验证码都要填'; email.err = true; return }
  email.binding = true; email.msg = ''; email.err = false
  try {
    const d = (await bindEmail(to, code)).data
    if (d && d.code === 200) {
      email.value = to; email.verified = true; email.bound = true; email.editing = false
      email.msg = d.message || '邮箱已绑定'
      email.err = false
      // 同步本地缓存（导航栏/其它读资料的组件靠它）
      const prev = getUser() || {}
      setUser({ ...prev, email: to, emailVerified: true })
      window.dispatchEvent(new CustomEvent('lh:user-updated'))
    } else {
      email.msg = (d && d.message) || '绑定失败'; email.err = true
    }
  } catch (e) {
    email.msg = e.response?.data?.message || '绑定失败，请稍后再试'; email.err = true
  } finally { email.binding = false }
}

// ---- 数据导出 ----
const exporting = ref(false)
const exportMsg = ref('')
const exportErr = ref(false)

// ---- 进化模块单独同意（2026-10-13）----
// ⛔ 默认 false：**没拿到后端明确值之前一律按"未同意"处理**（宁可少收集，不可默认拿走）。
const consent = ref(false)
const consentBusy = ref(false)
const consentMsg = ref('')
const consentErr = ref(false)

/** 切换即保存。失败必须**回滚开关**，否则界面显示"已开启"而服务端其实没记上。 */
async function saveConsent() {
  const want = consent.value
  consentBusy.value = true
  consentMsg.value = ''
  consentErr.value = false
  try {
    const d = (await updateEvolutionConsent(want)).data
    if (d && d.success === false) {
      consent.value = !want
      consentMsg.value = d.message || '保存失败，请稍后再试'
      consentErr.value = true
      return
    }
    consentMsg.value = want
      ? '已开启：你的对话可用于提炼通用沟通技巧'
      : '已关闭：你的对话不会被用于提炼技巧'
  } catch (e) {
    consent.value = !want
    consentMsg.value = e?.response?.data?.message || '保存失败，请稍后再试'
    consentErr.value = true
  } finally {
    consentBusy.value = false
  }
}

// ---- 回访邮件同意（2026-10-14，ADR-92）----
// ⛔ 与 consent 同一纪律：拿不到后端明确值之前按"未同意"处理。
const reengage = ref(false)
const reengageBusy = ref(false)
const reengageMsg = ref('')
const reengageErr = ref(false)

/** 切换即保存；失败**回滚开关**（否则界面显示"已开启"而服务端没记上）。 */
async function saveReengage() {
  const want = reengage.value
  reengageBusy.value = true
  reengageMsg.value = ''
  reengageErr.value = false
  try {
    const d = (await updateReengageConsent(want)).data
    if (d && d.success === false) {
      reengage.value = !want
      reengageMsg.value = d.message || '保存失败，请稍后再试'
      reengageErr.value = true
      return
    }
    reengageMsg.value = want ? '已开启：会偶尔给你写一封' : '已关闭：不会再收到这类邮件'
  } catch (e) {
    reengage.value = !want
    reengageMsg.value = e?.response?.data?.message || '保存失败，请稍后再试'
    reengageErr.value = true
  } finally {
    reengageBusy.value = false
  }
}

const displayAvatar = computed(() => form.avatarEmoji || (profile.value.username || '?').slice(0, 1).toUpperCase())
const joinedDate = computed(() => {
  const c = profile.value.createdAt
  if (!c) return '—'
  return String(c).slice(0, 10)
})

/** 后端统一用 Result 包装：{code, message, data} */
const unwrap = (res) => res?.data?.data ?? null
const codeOf = (res) => res?.data?.code

onMounted(async () => {
  try {
    const d = (await getMe()).data
    if (d && d.success) {
      profile.value = d
      form.nickname = d.nickname || ''
      form.avatarEmoji = d.avatarEmoji || ''
      form.bio = d.bio || ''
      annivInput.value = (d.anniversaryDate || '').slice(0, 10)
      // 单独同意的真实值来自后端；缺字段时保持默认"关"（不默认同意）
      consent.value = d.evolutionConsent === true
      // 回访邮件同意同样是「缺字段 = 关」（不默认同意）
      reengage.value = d.reengageConsent === true
      if (d.email) { email.value = d.email; email.verified = !!d.emailVerified; email.bound = true }
    }
  } catch (e) { /* 忽略——401 拦截器会处理 */ }
  loadRelationship()
  loadStats()
})

async function loadRelationship() {
  try {
    const d = unwrap(await getRelationship()) || {}
    rel.stage = d.stage || ''
    rel.partnerAlias = d.partnerAlias || ''
  } catch (e) { /* 未登录/接口异常：卡片退化成空白，不影响其它功能 */ }
}

/** 统计：四个数字各来自真实接口；单个失败不影响其它（并发 + 各自兜底） */
async function loadStats() {
  const safe = async (fn, pick) => { try { return pick(await fn()) } catch (e) { return 0 } }
  const [letters, facts, actions, sessions] = await Promise.all([
    safe(() => listConversations('love'), (r) => (r?.data?.data || []).length),
    safe(() => getMyMemoryFacts(), (r) => (r?.data?.data || []).length),
    safe(() => listActionItems(), (r) => (r?.data?.data || [])),
    safe(() => listSandboxSessions(), (r) => (r?.data?.data || []).length),
  ])
  stats.letters = letters
  stats.facts = facts
  stats.rehearsals = sessions
  stats.todoOpen = Array.isArray(actions) ? actions.filter((a) => !a.done).length : 0
  try {
    const tl = (await sentimentTimeline())?.data?.data || []
    if (tl.length >= 2) {
      const first = Number(tl[0]?.score ?? tl[0]?.value ?? 0)
      const last = Number(tl[tl.length - 1]?.score ?? tl[tl.length - 1]?.value ?? 0)
      stats.moodText = last > first ? '最近的你在往上走' : last < first ? '最近有点沉，慢一点也没关系' : '情绪还算平稳'
    }
  } catch (e) { /* 情绪接口失败就不显示这一行 */ }
}

async function saveProfile() {
  saving.value = true
  profileMsg.value = ''
  try {
    const d = (await updateProfileApi({
      nickname: form.nickname.trim(),
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
        createdAt: profile.value.createdAt,
        anniversaryDate: annivInput.value || prev.anniversaryDate || null,
      })
      profileMsg.value = d.message || '已保存'
      profileMsgErr.value = false
      window.dispatchEvent(new CustomEvent('lh:user-updated'))
    } else {
      profileMsg.value = (d && d.message) || '保存失败'
      profileMsgErr.value = true
    }
  } catch (e) {
    profileMsg.value = e?.response?.data?.message || '保存失败，请稍后再试'
    profileMsgErr.value = true
  } finally {
    saving.value = false
  }
}

/** 保存关系档案：stage/称呼走 /profile/relationship，纪念日走既有的 /auth/profile */
async function saveRelationship() {
  relSaving.value = true
  relMsg.value = ''
  try {
    const res = await updateRelationship({ stage: rel.stage || '', partnerAlias: rel.partnerAlias.trim() })
    if (codeOf(res) !== 200) {
      relMsg.value = res?.data?.message || '保存失败'
      relMsgErr.value = true
      return
    }
    const d = unwrap(res) || {}
    rel.stage = d.stage || rel.stage
    rel.partnerAlias = d.partnerAlias ?? rel.partnerAlias

    if (annivInput.value) {
      const pr = await updateProfileApi({ anniversaryDate: annivInput.value })
      if (pr?.data?.success === false) {
        relMsg.value = pr.data.message || '纪念日没存上'
        relMsgErr.value = true
        return
      }
    }
    relMsg.value = '已记下 —— 恋恋下次回信会照着这个来'
    relMsgErr.value = false
    window.dispatchEvent(new CustomEvent('lh:user-updated'))
  } catch (e) {
    relMsg.value = e?.response?.data?.message || '保存失败，请稍后再试'
    relMsgErr.value = true
  } finally {
    relSaving.value = false
  }
}

async function changePwd() {
  // ⛔ 2026-10-07 复原：重写页面时我把这段**本地校验 + 成功后的跳转**丢了，
  //    两条既有测试立刻变红（"一律不发请求" 与 "清 token 并跳登录页"）—— 那是真回归，不是测试过时。
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
      // 改完密码必须重新登录（旧 token 已失效）
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
      localStorage.removeItem('lwx_ai_user')
      router.push('/login')
    } else {
      delMsg.value = (d && d.message) || '注销失败'
      delMsgErr.value = true
    }
  } catch (e) {
    delMsg.value = e?.response?.data?.message || '注销失败，请稍后再试'
    delMsgErr.value = true
  } finally {
    delBusy.value = false
  }
}

function logout() {
  removeToken()
  localStorage.removeItem('lwx_ai_user')
  router.push('/login')
}

/**
 * 导出我的数据：**真的去取**（资料 + 关系档案 + 记忆 + 行动 + 信件正文），
 * 不是导一个空壳。⛔ 信件正文按封逐个取，所以限制封数上限，避免一次打爆接口。
 */
async function exportData() {
  exporting.value = true
  exportMsg.value = ''
  const MAX_LETTERS = 100
  try {
    const [me, relRes, facts, actions, sessions, convs] = await Promise.all([
      getMe().then((r) => r?.data || null).catch(() => null),
      getRelationship().then((r) => unwrap(r)).catch(() => null),
      getMyMemoryFacts().then((r) => r?.data?.data || []).catch(() => []),
      listActionItems().then((r) => r?.data?.data || []).catch(() => []),
      listSandboxSessions().then((r) => r?.data?.data || []).catch(() => []),
      listConversations('love').then((r) => r?.data?.data || []).catch(() => []),
    ])
    const picked = convs.slice(0, MAX_LETTERS)
    const letters = []
    for (const c of picked) {
      const id = c.chatId || c.id || c.conversationId
      let messages = []
      try { messages = (await getConversationMessages(id))?.data?.data || [] } catch (e) { /* 单封失败不中断导出 */ }
      letters.push({ id, title: c.title || '未命名对话', createdAt: c.createdAt || null, messages })
    }
    const payload = {
      exportedAt: new Date().toISOString(),
      note: '恋爱解忧所 · 我的数据导出',
      // ⛔ AI 生成标识（《人工智能生成合成内容标识办法》）：导出文件里也要能看出来
      //    "这些回复是 AI 生成的" —— 文件一旦离开本应用，界面上那个标签就看不到了。
      aiGenerated: true,
      aiGeneratedNote: AI_EXPORT_NOTE,
      profile: me,
      relationship: relRes,
      memories: facts,
      actionItems: actions,
      sandboxSessions: sessions,
      letters,
      lettersOmitted: Math.max(0, convs.length - picked.length),
    }
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `恋爱解忧所-我的数据-${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)                       // 不释放会漏内存
    exportMsg.value = `已导出 ${letters.length} 封信${payload.lettersOmitted ? `（另有 ${payload.lettersOmitted} 封未包含）` : ''}`
    exportErr.value = false
  } catch (e) {
    exportMsg.value = '导出失败，请稍后再试'
    exportErr.value = true
  } finally {
    exporting.value = false
  }
}

onUnmounted(() => { if (emailTimer) clearInterval(emailTimer) })
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
  background: var(--desk-raised);
  box-shadow: 0 8px 18px -16px oklch(35% 0.04 62 / 0.5);
  position: relative;
  z-index: 2;
}
.letterhead { display: flex; align-items: center; gap: 12px; }
.letterhead-stamp {
  display: inline-flex; align-items: center; justify-content: center;
  width: 42px; height: 42px; border-radius: 50%;
  border: 2px dashed var(--accent); color: var(--accent-text);
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
  border: 2px dashed var(--accent);
  /* ⛔ 不能用 background 简写：它会把 background-color 重置成 transparent ⇒ 判据失效 */
  background-color: var(--accent-wash);
  background-image: linear-gradient(180deg, var(--accent-wash), var(--paper-card));
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
  border-radius: 10px; font-size: 11px; color: var(--chip-fg);
  background: var(--chip-bg); border: 1px dashed var(--accent);
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
.emoji-opt.on { border-color: var(--accent); background: var(--chip-bg); }

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
  border-color: var(--accent);
  box-shadow: 0 0 0 3px oklch(58% 0.13 28 / 0.3);
}
.field input::placeholder, .field textarea::placeholder { color: var(--ink-faint); font-family: var(--font-hand); }
.counter { position: absolute; right: 10px; bottom: -16px; font-size: 11px; color: var(--ink-faint); }

.btn-hand.save-btn {
  border: none; border-radius: 20px;
  background-color: var(--accent);
  background-image: linear-gradient(180deg, var(--accent), var(--accent-deep));
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

.memo-note { border-left: 3px solid var(--accent); background-color: var(--paper-card); background-image: linear-gradient(135deg, var(--paper-card), var(--paper-card-warm)); color: var(--ink-on-paper); }
.memo-title { margin: 0 0 6px; font-size: 16px; color: var(--accent-text); }
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
.btn-cancel:hover { border-color: var(--accent); color: var(--accent-text); }

/* ========== 2026-10-07 新增区块（概览 / 关系档案 / 账号信息 / 数据导出） ========== */
.profile-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.profile-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 22px 40px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  align-items: start;
}
.profile-main { display: flex; flex-direction: column; gap: 16px; }
.profile-side { display: flex; flex-direction: column; gap: 16px; }

.card { padding: 24px 26px; }
.avatar-row { display: flex; align-items: center; gap: 16px; margin-bottom: 18px; }

/* ---- 概览 ---- */
.stat-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }
.stat {
  display: flex; flex-direction: column; align-items: center; gap: 1px;
  padding: 12px 6px; border-radius: var(--r-card);
  background: var(--paper-card-warm); border: 1px solid oklch(82% 0.02 62);
}
.stat b { font-family: var(--font-hand); font-size: 26px; line-height: 1.1; color: var(--accent-text); }
.stat span { font-size: 11.5px; color: var(--ink-on-paper-soft); }
.stat i { font-style: normal; font-size: 11px; color: var(--ink-on-paper-faint); text-align: center; margin-top: 3px; }
.stat-mood {
  display: flex; align-items: center; gap: 6px;
  margin: 0 0 12px; font-size: 13px; color: var(--ink-on-paper-soft);
}
.entry-row { display: flex; flex-wrap: wrap; gap: 9px; }
.entry-chip {
  display: inline-flex; align-items: center; gap: 6px; cursor: pointer;
  padding: 7px 14px; font-size: 13px; font-family: var(--font-hand); letter-spacing: 0.06em;
  background: var(--paper-card-warm); border: 1.4px solid oklch(74% 0.024 62);
  border-radius: var(--r-pill); color: var(--ink-on-paper); transition: all 0.16s;
}
.entry-chip:hover { border-color: var(--accent); color: var(--accent-text); transform: translateY(-1px); }

/* ---- 关系档案 ---- */
.rel-hint { margin: 0 0 16px; font-size: 13px; line-height: 1.8; color: var(--ink-on-paper-soft); }
.stage-row { display: flex; flex-wrap: wrap; gap: 8px; }
.stage-chip {
  padding: 7px 15px; cursor: pointer; font-size: 13.5px; font-family: var(--font-hand); letter-spacing: 0.06em;
  background: var(--paper-card-warm); border: 1.4px solid oklch(74% 0.024 62);
  border-radius: var(--r-pill); color: var(--ink-on-paper); transition: all 0.16s;
}
.stage-chip:hover { border-color: var(--accent); }
.stage-chip.on { background: var(--chip-bg); border-color: var(--accent); color: var(--chip-fg); font-weight: 700; }
.field-tip { margin: 6px 0 0; font-size: 12px; color: var(--ink-on-paper-faint); }
.date-input {
  width: 100%; font-size: 14px; padding: 9px 12px; color: var(--ink-on-paper);
  background: var(--paper-card-warm); border: 1px solid var(--ink-line);
  border-radius: var(--r-ctl); outline: none;
}
.rel-save { margin-top: 4px; }

/* ---- 账号与安全 ---- */
.acct-info {
  margin-top: 16px; padding-top: 14px; border-top: 1px dashed oklch(78% 0.02 60);
  display: flex; flex-direction: column; gap: 7px;
}
.acct-info p { display: flex; justify-content: space-between; margin: 0; font-size: 12.5px; }
.acct-info span { color: var(--ink-on-paper-faint); }
.acct-info b { color: var(--ink-on-paper); font-weight: 600; }
.btn-ghost {
  margin-top: 14px; width: 100%; cursor: pointer;
  padding: 8px 14px; font-size: 13px; font-family: var(--font-hand); letter-spacing: 0.08em;
  background: transparent; border: 1px solid var(--ink-line); border-radius: var(--r-ctl);
  color: var(--ink-on-paper-soft); transition: all 0.16s;
}
.btn-ghost:hover { border-color: var(--accent); color: var(--accent-text); }

.data-desc { margin: 0 0 14px; font-size: 12.5px; line-height: 1.8; color: var(--ink-on-paper-soft); }
.data-desc strong { color: var(--ink-on-paper); }

/* ---------- 进化模块单独同意开关（2026-10-13）---------- */
.consent-row {
  display: flex; align-items: center; gap: 9px;
  cursor: pointer; font-size: 13px; color: var(--ink-on-paper);
  padding: 8px 0 2px;
}
.consent-toggle { width: 17px; height: 17px; flex-shrink: 0; accent-color: var(--accent); cursor: pointer; }
.consent-toggle:disabled { cursor: not-allowed; opacity: 0.6; }
.consent-text { letter-spacing: 0.02em; }

@media (max-width: 900px) {
  .profile-body { grid-template-columns: 1fr; padding: 14px 14px 32px; }
  .stat-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
/* ---------- 邮箱绑定 ---------- */
.email-line { display: flex; align-items: center; gap: 8px; margin: 0 0 6px; font-size: 14px; color: var(--ink-on-paper); }
.email-badge {
  font-size: 11px; padding: 1px 8px; border-radius: var(--r-pill);
  background: var(--chip-bg); color: var(--chip-fg); border: 1px solid var(--accent);
}
.email-card .code-row { display: flex; gap: 8px; align-items: center; }
.email-card .code-row input { flex: 1; }
.code-btn {
  flex-shrink: 0; cursor: pointer; padding: 9px 12px; font-size: 12.5px;
  background: var(--paper-card-warm); border: 1px solid var(--ink-line);
  border-radius: var(--r-ctl); color: var(--ink-on-paper); font-family: var(--font-hand);
}
.code-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent-text); }
.code-btn:disabled { opacity: 0.55; cursor: not-allowed; }
</style>