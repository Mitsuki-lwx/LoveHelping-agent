<template>
  <div class="chat-page">
    <div class="chat-header">
      <button class="back-btn" @click="goHome"><PhArrowLeft :size="18" weight="bold" aria-hidden="true" /></button>
      <div class="letterhead">
        <span class="letterhead-stamp anim-stamp">恋</span>
        <div class="letterhead-titles">
          <h1 class="letterhead-title hand">恋爱解忧信</h1>
          <span class="letterhead-sub">收信 · 回信，替你慢慢想</span>
        </div>
      </div>
      <button class="guide-open-btn" @click="showGuide = true" title="这里能做什么">能做什么</button>
      <button class="new-chat-btn btn-hand" @click="newChat" title="新建对话"><PhPencilSimple :size="15" weight="bold" class="ico" aria-hidden="true" /> 新一封信</button>
    </div>

    <!-- 未成年人模式提示（2026-10-13 合规）：**不打扰但可见** —— 一条窄提示条，不做弹窗。
         弹窗会被条件反射地关掉（等于没提示），而"当天还剩几条"是用户真的会关心的信息。
         文案里的额度/时段取自与后端限制同一个常量（utils/legal.js），不写死第二份。 -->
    <div v-if="minorMode" class="minor-banner" role="status">
      <PhInfo :size="15" weight="bold" class="ico" aria-hidden="true" />
      <span>未成年人模式已开启：每日 {{ MINOR.dailyLimit }} 条、{{ MINOR.quietStart }}–{{ MINOR.quietEnd }} 休息</span>
    </div>

    <!-- 首次体验引导（ADR-91）：产品能力密度高（话术三牌 / 角色模拟屋 / 旧信存档+情绪轨迹），
         而空态只引导了"聊天"一件事 ⇒ 其余能力全部沉没。
         ⛔ 刻意做成**可关、可重开**的卡片，不是弹窗：弹窗会被条件反射地关掉（等于没引导），
            而"原来还能这样用"这件事应该随时查得到（右上角「能做什么」）。 -->
    <div v-if="showGuide" class="guide-card letter-card" role="region" aria-label="这里能做什么">
      <div class="guide-head">
        <span class="guide-title hand">第一次来？这里还能这样用</span>
        <button class="guide-close" @click="dismissGuide" title="知道了，收起">知道了</button>
      </div>
      <div class="guide-rows">
        <button class="guide-row" @click="guideAction('chat')">
          <span class="guide-row-name">直接说心事</span>
          <span class="guide-row-desc">说不出口的、想不通的，写下来就好，我会慢慢回</span>
        </button>
        <button class="guide-row" @click="guideAction('advice')">
          <span class="guide-row-name">想要具体话术</span>
          <span class="guide-row-desc">说「我该怎么回复 TA」，会给三张牌：安全 / 进击 / 后撤</span>
        </button>
        <button class="guide-row" @click="guideAction('sandbox')">
          <span class="guide-row-name">想先练一练</span>
          <span class="guide-row-desc">角色模拟屋：AI 扮演 TA，陪你把这句难开口的话过一遍</span>
        </button>
        <button class="guide-row" @click="guideAction('history')">
          <span class="guide-row-name">想回头看</span>
          <span class="guide-row-desc">旧信存档 + 情绪轨迹，看看这两周有没有在变好</span>
        </button>
      </div>
      <p class="guide-foot">写完一句后，下面的「TA 视角」还能替你推演对方的想法。</p>
    </div>

    <div class="chat-messages" ref="messagesRef">
      <div v-if="messages.length === 0" class="empty-state">
        <div class="empty-letter letter-card fold anim-letter-in">
          <!-- 真实照片（taste-skill §4.8）：空态最容易被做成"纯文字+图标"，这里放一张相片 -->
          <div class="photo empty-letter-photo">
            <img :src="PHOTO.empty" alt="" loading="lazy" decoding="async" @error="$event.target.style.display = 'none'">
            <span class="photo-fallback">信</span>
          </div>
          <div class="empty-letter-head">
            <span class="empty-letter-stamp stamp">心</span>
            <p class="hand empty-letter-title">写信给我吧</p>
          </div>
          <p class="empty-letter-body">
            说不出口的、想不通的、放不下的……<br>都可以写在这里。我会认真地读，慢慢地回。
          </p>
          <div class="empty-quicks">
            <button class="quick-ask btn-hand" @click="quickAsk('最近和对象总是因为小事冷战，我该怎么办？')"><PhChatCircle :size="15" weight="bold" class="ico" aria-hidden="true" /> 总是冷战怎么办？</button>
            <button class="quick-ask btn-hand" @click="quickAsk('怎么判断对方是不是真的喜欢我？')"><PhChatCircle :size="15" weight="bold" class="ico" aria-hidden="true" /> TA 真的喜欢我吗？</button>
            <button class="quick-ask btn-hand" @click="quickAsk('分手后还是放不下，怎么走出来？')"><PhChatCircle :size="15" weight="bold" class="ico" aria-hidden="true" /> 分手后走不出来</button>
          </div>
        </div>
      </div>
      <div
        v-for="(msg, idx) in messages"
        :key="idx"
        :class="['message anim-letter-in', msg.role === 'user' ? 'message-user' : 'message-ai']"
      >
        <div v-if="msg.role === 'ai'" class="message-sender">
          <span class="msg-stamp-mini">回</span><span class="msg-sender-name hand">恋恋</span>
          <!-- ⛔ AI 生成标识（《人工智能生成合成内容标识办法》第四条(一)：显式标识）。
               必须**常驻**在每条 AI 回复旁 —— 只在页脚/关于页写一句"本服务由 AI 提供"不算标识。 -->
          <span class="ai-tag" title="本条回复由人工智能生成，非真人撰写">{{ AI_LABEL }}</span>
        </div>
        <!-- 用户气泡里的图片（本地 blob 预览；历史回显走 GET /media/{id}） -->
        <div v-if="msg.images && msg.images.length" class="message-images">
          <img
            v-for="im in msg.images"
            :key="im.id"
            :src="im.previewUrl || mediaUrl(im.id)"
            alt="我发的图片"
            loading="lazy"
            @click="openImage(im)"
          />
        </div>
        <div class="message-content" v-html="renderMarkdown(msg.content)"></div>
        <!-- 话术三牌（FR-CORE-01）：结构化渲染成卡片。⛔ 不能当正文 —— 那会是一坨 JSON。 -->
        <div v-if="msg.advice && msg.advice.length" class="advice-cards">
          <div v-for="(tier, ti) in msg.advice" :key="ti" class="advice-card">
            <div class="advice-tier-name">{{ tier.name }}</div>
            <!-- 复用与消息正文**同一个**加固过的渲染器（转义 + scheme 白名单，ADR-79）：
                 三牌正文里模型会用 **加粗** 和 > 引用，纯文本插值会原样显示星号。 -->
            <div class="advice-tier-content" v-html="renderMarkdown(tierContent(tier))"></div>
            <div v-if="tierReaction(tier)" class="advice-tier-reaction">{{ tierReaction(tier) }}</div>
          </div>
        </div>
        <!-- 情绪刹车片（ADR-87）：给用户一条"我仍想说下去"的路，而不是死胡同 -->
        <div v-if="msg.brake" class="brake-row">
          <button type="button" class="brake-continue" @click="continueAfterBrake(idx)">继续发送</button>
        </div>
        <div v-if="msg.role === 'ai' && !loading" class="vote-row">
          <button
            :class="['vote-btn', voteStates[idx] === 'LIKE' ? 'vote-active-like' : '']"
            @click="vote(idx, 'LIKE')"
            title="有用"
          ><PhThumbsUp :size="15" weight="bold" aria-hidden="true" /></button>
          <button
            :class="['vote-btn', voteStates[idx] === 'DISLIKE' ? 'vote-active-dislike' : '']"
            @click="vote(idx, 'DISLIKE')"
            title="没用"
          ><PhThumbsDown :size="15" weight="bold" aria-hidden="true" /></button>
          <input
            v-if="showFeedback === idx"
            v-model="feedbackText"
            class="feedback-input"
            placeholder="为什么没用？（可选）"
            @keydown.enter="submitFeedback(idx)"
            @blur="submitFeedback(idx)"
          />
        </div>
      </div>
      <div v-if="loading" class="message message-ai">
        <div class="message-content">
          <span v-if="statusText" class="thinking-text">{{ statusText }}</span>
          <span class="typing-dots">
            <span class="dot">.</span><span class="dot">.</span><span class="dot">.</span>
          </span>
        </div>
      </div>
    </div>

    <!-- ② 行动卡（2026-09-08）：把回信建议变成可跟踪的事 -->
    <div v-if="actionItems.length" class="action-dock letter-card">
      <div class="action-dock-head">
        <span class="action-dock-title"><PhPushPin :size="15" weight="bold" class="ico" aria-hidden="true" /> 说好要做的事</span>
        <button class="ta-close" @click="actionItems = []" title="收起"><PhX :size="18" weight="bold" aria-hidden="true" /></button>
      </div>
      <div v-for="it in actionItems" :key="it.id" class="action-row">
        <label class="action-check">
          <input type="checkbox" @change="onActionDone(it)" />
          <span class="action-content">{{ it.content }}</span>
        </label>
        <button class="action-skip" @click="onActionSkip(it)">算了</button>
      </div>
    </div>

    <div class="chat-input-area">
      <div class="input-wrapper letter-card">
        <!-- 发图（2026-10-07 用户要求）：上传 → mediaId → 随消息一起发给后端视觉链路 -->
        <ChatImagePicker v-model="pendingImages" :disabled="loading" />
        <input
          v-model="inputText"
          class="chat-input"
          placeholder="写下你的心事……"
          @keydown.enter="sendMessage"
          :disabled="loading"
        />
        <button class="send-btn" @click="sendMessage" :disabled="loading || !inputText.trim()">
          <span class="send-ink">寄出</span>
        </button>
      </div>
      <p class="input-hint">按 Enter 寄出 · AI 回信需要一点时间，请耐心等一等</p>
      <!-- ⛔ 产品级免责声明（docs/07 §5.1）：必须**常驻可见**，不能只写在协议页里
           —— 用户不会为了看一句免责声明去翻协议。刻意做小、不抢戏，但一直在。 -->
      <p class="ai-disclaimer">AI 建议仅供参考，不构成专业心理咨询或医疗诊断。</p>

      <!-- ① TA 视角推演（2026-09-08）：用沙盘人格预演「TA 会怎么回这句」 -->
      <div class="ta-view-bar">
        <button class="ta-view-btn" @click="openTaView" :disabled="loading || taLoading">
          <template v-if="taLoading">推演中…</template>
          <template v-else><PhBinoculars :size="15" weight="bold" class="ico" aria-hidden="true" /> TA 视角</template>
        </button>
        <span class="ta-view-hint">用 TA 的身份，回你这句</span>
      </div>

      <!-- 人设选择弹层 -->
      <div v-if="taPickerOpen" class="ta-picker letter-card">
        <div class="ta-picker-head">
          <span class="ta-picker-title">选一个 TA</span>
          <button class="ta-close" @click="taPickerOpen = false">✕</button>
        </div>
        <div class="ta-persona-grid">
          <button
            v-for="p in taPersonas"
            :key="p.id"
            class="ta-persona"
            :class="{ active: taPersonaId === p.id }"
            @click="taPersonaId = p.id; taCustom = ''"
          >{{ p.name }}</button>
        </div>
        <input
          v-model="taCustom"
          class="ta-custom-input"
          placeholder="或自己写：性格 / 说话方式（选它则不选上面）"
        />
        <button class="btn-hand primary ta-go" :disabled="!canTaView" @click="runTaView">开始推演</button>
      </div>

      <!-- 推演结果卡 -->
      <div v-if="taResult" class="ta-result letter-card">
        <div class="ta-result-head">
          <span class="ta-avatar">T</span>
          <span class="ta-name">{{ taResult.personaName }}</span>
          <button class="ta-close" @click="taResult = null">✕</button>
        </div>
        <p class="ta-reply">「{{ taResult.reply }}」</p>
        <p v-if="taResult.insight" class="ta-insight"><PhEyes :size="15" weight="bold" class="ico" aria-hidden="true" /> {{ taResult.insight }}</p>
        <p class="ta-disclaimer">这是按人设推演的可能反应，不是 TA 的真实想法——可以用来练手，别当成答案。</p>
      </div>
    </div>
  </div>
</template>

<script setup>

import { PhArrowLeft, PhBinoculars, PhChatCircle, PhEyes, PhInfo, PhPencilSimple, PhPushPin, PhThumbsDown, PhThumbsUp, PhX } from '@phosphor-icons/vue'
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { createLoveChatSSE, generateChatId, voteMessage, registerConversation, getConversationMessages, sandboxTaView, listSandboxPersonas, listActionItems, createActionItemFromReply, doneActionItem, removeActionItem, reportSentiment, mediaUrl, markOnboarded } from '../api/index.js'
import { saveLocalConversation } from '../utils/history.js'
import ChatImagePicker from '../components/ChatImagePicker.vue'
import { getUser } from '../utils/auth.js'
import { cachedProfile } from '../utils/profile.js'
import { MINOR_MODE as MINOR, AI_LABEL_TEXT as AI_LABEL } from '../utils/legal.js'
import { createTypewriter } from '../utils/typewriter.js'

// 占位摄影（taste-skill §4.8）：seed 用描述性字符串
const PHOTO = { empty: 'https://picsum.photos/seed/lwx-chat-empty-letter/900/420?grayscale' }

const router = useRouter()
const route = useRoute()

// If URL has sessionId, continue that conversation, otherwise create new
const chatId = ref(route.query.sessionId || generateChatId())

function newChat() {
  router.push('/love-chat')
}

/**
 * ⛔ 属性转义（2026-10-02 修 XSS）。
 *
 * 原实现先把 `<>&` 转义了（裸标签被挡住），**但把 URL/alt 直接拼进 HTML 属性、没转义引号** ——
 * 于是 `![x](y" onerror="...")` 里的 `"` **逃出属性边界**，注入了可执行 handler。
 *
 * 真浏览器实测（ADR-79）：载荷
 *   `![x](y" onerror="window.__pwned=localStorage['lwx_ai_token'])`
 * 渲染成 `<img src="y" onerror="window.__pwned=localStorage['lwx_ai_token']" ...>`
 * → **handler 真的执行、真的读走了 localStorage 里的 JWT**。
 * 而 msg.content 对**用户自己发的消息**也走这个渲染（历史回填时 user/ai 都渲染）⇒ 可被利用。
 */
function escAttr(v) {
  return String(v ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 只放行 http/https 与站内相对路径 —— 拦掉 `javascript:` / `data:` 这类可执行 scheme。
 * ⛔ 只转义引号是不够的：`[点我](javascript:...)` 不需要逃出属性就能执行。
 * ⛔ 2026-11-01（change security-hardening）：`//host` 是**协议相对 URL**，
 *    `startsWith('/')` 会把它当站内路径放行 → 渲染成外链（钓鱼/开放跳转）。
 *    站内路径必须是单斜杠开头，`//` 一律拒绝。
 */
function safeUrl(raw) {
  const url = String(raw ?? '').trim()
  if (/^https?:\/\//i.test(url)) return url
  if (url.startsWith('/') && !url.startsWith('//')) return url
  if (url.startsWith('#')) return url
  return ''
}

function renderMarkdown(text) {
  if (!text) return ''
  const escaped = text
    .replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const lines = escaped.split('\n').map(line => {
    if (line.startsWith('### ')) return `<h3>${line.slice(4)}</h3>`
    if (line.startsWith('## ')) return `<h2>${line.slice(3)}</h2>`
    if (line.startsWith('# ')) return `<h1>${line.slice(2)}</h1>`
    if (/^-{3,}\s*$/.test(line)) return '<hr>'
    return line
  })
  let html = lines.join('\n')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
  // Style emoji prefixes: only the first 💭 gets a label, subsequent ones keep only the badge
  html = html.replace(/^💭 /gm, '%%THINK%%')
  html = html.replace('%%THINK%%', '<span class="step-badge step-think">思考</span> ')
  html = html.replace(/%%THINK%%/g, '<span class="step-bullet">&#8226;</span> ')
  html = html.replace(/^✨ /gm, '<span class="step-badge step-result">结果</span> ')
  // Wrap thinking content in smaller/lighter span
  html = html.replace(/(<span class="step-badge step-think">思考<\/span>\s*)(.+?)(?:\n|$)/g, '$1<span class="think-text">$2</span>')
  html = html.replace(/(<span class="step-bullet">&#8226;<\/span>\s*)(.+?)(?:\n|$)/g, '$1<span class="think-text">$2</span>')
  // Handle inline images
  // ⛔ 转义后再拼（原实现直接把 $1/$2 插进属性 → 引号可逃逸 → XSS）
  html = html.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (_m, alt, rawUrl) => {
    const url = safeUrl(rawUrl)
    if (!url) return escAttr(alt)                       // 不安全 scheme：退化成纯文本
    return `<img src="${escAttr(url)}" alt="${escAttr(alt)}" style="max-width:100%;max-height:400px;border-radius:8px;margin:8px 0;display:block">`
  })
  // Handle markdown links - render PDF links as preview/download cards
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (match, linkText, rawUrl) => {
    const url = safeUrl(rawUrl)
    if (!url) return escAttr(linkText)                  // 不安全 scheme：退化成纯文本，不给可点的链接
    if (url.includes('/pdf/') || url.toLowerCase().endsWith('.pdf')) {
      return `<div class="pdf-card">
        <div class="pdf-card-info">
          <div class="pdf-card-name">${escAttr(linkText)}</div>
          <div class="pdf-card-actions">
            <a href="${escAttr(url)}" target="_blank" class="pdf-btn pdf-btn-preview" rel="noopener">预览</a>
            <a href="${escAttr(url)}" download class="pdf-btn pdf-btn-download">下载</a>
          </div>
        </div>
      </div>`
    }
    return `<a href="${escAttr(url)}" target="_blank" rel="noopener" class="msg-link">${escAttr(linkText)}</a>`
  })
  return html.replace(/\n/g, '<br>')
}
const messages = ref([])
const inputText = ref('')
/** 待发送的图片（ChatImagePicker 里已上传好，这里只存 {id, previewUrl}） */
const pendingImages = ref([])
const loading = ref(false)
/**
 * 「思考中」占位文案（2026-09-27 phase17）。
 *
 * 打字点气泡本来就有，但三个点转 11 秒不给任何信息 = 用户无法判断
 * 「在算」还是「卡了」。后端在首个 token 之前发一个 `event:status`，
 * 这里把它显示出来；**首个正文帧到达即清空**。
 * ⛔ 文案必须诚实：此刻并没有「在分析你的问题」，写进度条式的措辞是拿不存在的进展安抚人。
 */
const statusText = ref('')
const messagesRef = ref(null)
const voteStates = ref({})
const showFeedback = ref(null)
const feedbackText = ref('')
let cancelSSE = null

/**
 * 未成年人模式（2026-10-13 合规）。
 *
 * ⛔ 数据来自**本地资料缓存**（`cachedProfile()` → localStorage），不是这里再发一次 `/auth/me`：
 *    本仓的纪律是"资料的唯一取数点在 utils/profile.js"（App.vue 启动时刷一次），
 *    聊天页再自己请求一遍既多余、又会在单测里真连网（见 utils/profile.js 的教训注释）。
 * ⛔ 后端返回的 `minorMode` 在**登录后**才进缓存，而 App.vue 那次刷新是异步的 ⇒
 *    必须监听 `lh:user-updated` 事件补一次同步，否则用户首次进聊天页可能看不到提示条。
 */
const minorMode = ref(!!cachedProfile().minorMode)

/**
 * 首次体验引导（ADR-91）。
 *
 * <p>初始可见性 = 「服务端还没记过已看过」。⛔ 与未成年人提示条同一个纪律：
 * 数据只从 `cachedProfile()` 读缓存，不为了一个布尔值再打一次 `/auth/me`。</p>
 */
const showGuide = ref(!cachedProfile().onboarded)

/** 收起引导：先关界面（本地即时生效），再异步落库（失败不打扰用户 —— 引导状态不值得报错）。 */
function dismissGuide() {
  showGuide.value = false
  markOnboarded().catch(() => {})
}

/**
 * 引导卡的四个入口。⛔ 每个都要**真的做点什么**：
 * 只写一句"你可以用 XX 功能"而不给动作，等于把说明书塞给用户。
 */
function guideAction(kind) {
  dismissGuide()
  if (kind === 'chat') {
    nextTick(() => document.querySelector('.chat-input')?.focus())
    return
  }
  if (kind === 'advice') {
    // 预填一句**能触发话术三牌**的样例（后端按「怎么回复」等意图路由，见 CapabilityRouter）
    inputText.value = '他总是已读不回，我该怎么回复他'
    nextTick(() => document.querySelector('.chat-input')?.focus())
    return
  }
  if (kind === 'sandbox') {
    router.push('/sandbox')
    return
  }
  if (kind === 'history') {
    router.push('/history')
  }
}
function syncMinorMode() { minorMode.value = !!cachedProfile().minorMode }

/* ============ 行动卡（2026-09-08 产品闭环 ②） ============ */
const actionItems = ref([])

async function loadActionItems() {
  try {
    const res = await listActionItems()
    actionItems.value = res.data?.data || []
  } catch (e) { actionItems.value = [] }
}

/** 收到完整回信后：从三牌建议抽一条行动项（后端无有效建议时不建） */
async function maybeCreateActionItem(replyText) {
  try {
    const res = await createActionItemFromReply(chatId.value, replyText)
    if (res.data?.data?.created) await loadActionItems()
  } catch (e) { /* 行动卡是增强，失败不影响主流程 */ }
}

async function onActionDone(it) {
  try {
    await doneActionItem(it.id)
    actionItems.value = actionItems.value.filter(x => x.id !== it.id)
  } catch (e) { /* 忽略 */ }
}

async function onActionSkip(it) {
  try {
    await removeActionItem(it.id)
    actionItems.value = actionItems.value.filter(x => x.id !== it.id)
  } catch (e) { /* 忽略 */ }
}

/* ============ TA 视角推演（2026-09-08 产品闭环 ①） ============ */
const taPickerOpen = ref(false)
const taPersonas = ref([])
const taPersonaId = ref(null)
const taCustom = ref('')
const taLoading = ref(false)
const taResult = ref(null)

/** 取最后一条用户消息作为推演素材（没写过则用输入框内容） */
function taSourceText() {
  const mine = [...messages.value].reverse().find(m => m.role === 'user')
  return (inputText.value && inputText.value.trim()) ? inputText.value.trim() : (mine?.content || '')
}

const canTaView = computed(() => taSourceText().length > 0)

async function openTaView() {
  if (taPersonas.value.length === 0) {
    try {
      const res = await listSandboxPersonas()
      taPersonas.value = res.data?.data || []
    } catch (e) { /* 弹层内列表为空时可用自定义特征 */ }
  }
  if (!taPersonaId.value && taPersonas.value.length) taPersonaId.value = taPersonas.value[0].id
  taPickerOpen.value = true
}

async function runTaView() {
  const message = taSourceText()
  if (!message) return
  taLoading.value = true
  try {
    const body = taCustom.value.trim()
      ? { customTraits: taCustom.value.trim(), message }
      : { personaId: taPersonaId.value, message }
    const res = await sandboxTaView(body)
    const d = res.data?.data || {}
    taResult.value = { personaName: d.personaName || 'TA', reply: d.reply || '', insight: d.insight || '' }
    taPickerOpen.value = false
  } catch (e) {
    taResult.value = { personaName: 'TA', reply: e.response?.data?.message || '推演失败，稍后再试', insight: '' }
    taPickerOpen.value = false
  } finally {
    taLoading.value = false
  }
}

function goHome() {
  router.push('/')
}

/** 空态引导：一键填入问题并寄出 */
function quickAsk(text) {
  inputText.value = text
  sendMessage()
}

/** Load existing conversation messages */
async function loadExistingMessages() {
  const sessionId = route.query.sessionId
  if (!sessionId) return
  try {
    const res = await getConversationMessages(sessionId)
    const msgs = res.data?.data
    if (Array.isArray(msgs) && msgs.length > 0) {
      for (const msg of msgs) {
        // Only show user messages and assistant replies, skip tool calls / system prompts
        if (msg.messageType !== 'USER' && msg.messageType !== 'ASSISTANT') continue
        const text = (msg.text || '').trim()
        if (!text) continue
        const role = msg.messageType === 'USER' ? 'user' : 'ai'
        messages.value.push({ role, content: text })
      }
    }
  } catch (e) {
    console.error('Failed to load existing messages:', e)
  }
}

onMounted(() => {
  // 首页「今日」区块跳过来时会带一句开场白（?q=）——**只填进输入框，不自动发送**：
  // 自动发出去等于替用户说话，用户可能只想改两个字再发。
  const q = route.query.q
  if (q) inputText.value = String(q)
  // 资料刷到后补同步未成年人模式（见 minorMode 处的说明）
  window.addEventListener('lh:user-updated', syncMinorMode)
  loadActionItems()
  loadExistingMessages()
})

async function scrollToBottom() {
  await nextTick()
  if (messagesRef.value) {
    const el = messagesRef.value
    const threshold = 120
    const isNearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < threshold
    if (isNearBottom) {
      el.scrollTop = el.scrollHeight
    }
  }
}

// 打字机动画（重构 2026-09-07：抽至 utils/typewriter.js，行为不变——35ms/字）
const typewriter = createTypewriter()
function typewrite(msgIdx, text) {
  const msg = messages.value[msgIdx]
  msg.content = ''
  typewriter.start(text, (partial) => {
    msg.content = partial
    scrollToBottom()
  })
}

/**
 * 三牌卡片的正文（送进 renderMarkdown 前先做最小归一）。
 *
 * <p>模型偶尔在末尾留下**未闭合**的 markdown 标记（实测："…主动权在你手里。- **"），
 * 渲染出来就是字面星号。只剥尾部悬空标记，不动正文中间的任何内容。</p>
 */
function tierContent(tier) {
  let s = (tier && tier.content) || ''
  // 反复剥尾部：悬空列表符号（- * +）与未闭合的加粗/斜体标记（** * __ _）
  for (let i = 0; i < 4; i++) {
    const next = s.replace(/\s*(?:\*\*|\*|__|_)\s*$/, '').replace(/\s*[-*+]\s*$/, '')
    if (next === s) break
    s = next
  }
  return s
}

/**
 * 三牌卡片的「对方反应」文本。
 *
 * <p>模型经常在 reaction 里**自己**已经写了"对方可能反应："前缀，直接再加一层标签会读成
 * 「对方可能会：对方可能反应：…」。所以先剥掉模型自带的前缀，再由卡片统一加标签。</p>
 */
function tierReaction(tier) {
  const raw = (tier && tier.reaction) || ''
  const stripped = raw.replace(/^\s*对方(可能|会)?(的)?(可能)?(反应|表现|回应)[:：]?\s*/, '')
  return stripped ? `对方可能会：${stripped}` : ''
}

function sendMessage() {
  const text = inputText.value.trim()
  const images = pendingImages.value.slice()
  // ⛔ 允许"只发图不写字"：带图时文案可以空（后端视觉节点只需要图片 + 可选文本）
  if (!text && !images.length) return

  inputText.value = ''
  pendingImages.value = []          // 发出即清空托盘（图片已上传，引用留在消息里）
  saveLocalConversation(chatId.value, text)
  // Register conversation ownership on first send
  registerConversation(chatId.value, text.substring(0, 50), 'love').catch(() => {})

  // ⛔ 先落用户气泡，再落 AI 空气泡（顺序即界面顺序；AI 气泡下标 = 用户气泡 + 1）
  messages.value.push({ role: 'user', content: text, images })
  messages.value.push({ role: 'ai', content: '' })
  const aiMsgIdx = messages.value.length - 1
  scrollToBottom()
  runChat({ text, images, aiMsgIdx, continueBrake: false })
}

/**
 * 情绪刹车片后的「继续发送」（ADR-87）。
 *
 * <p>刹车不是"错误"，是**带用户动作的阻断**：用户点这里就把同一条消息
 * 带 {@code continueBrake=true} 重发，并**复用原来那个 AI 气泡**（不新增一条）。</p>
 */
function continueAfterBrake(aiMsgIdx) {
  const msg = messages.value[aiMsgIdx]
  const pending = msg && msg.brake
  if (!pending) return
  delete msg.brake
  msg.content = ''
  runChat({ text: pending.text, images: pending.images, aiMsgIdx, continueBrake: true })
}

/**
 * 真正发起一轮 SSE —— 正常发送与刹车后继续**共用这一条路径**。
 * （两份 handler 一定会漂移：加个新事件时只改一处，另一处就静默漏掉。）
 */
function runChat({ text, images, aiMsgIdx, continueBrake }) {
  if (loading.value) cancelSSE?.()
  loading.value = true
  statusText.value = ''

  // 统一入口 /Love_app/chat/sse（2026-09-07）：后端 classify 自动路由——简单/知识库/Agent 全自动
  cancelSSE = createLoveChatSSE(text, chatId.value, {
    // 占位状态（2026-09-27 phase17）：后端在首个 token 前发 event:status，0.06s 就到。
    // ⛔ 它绝不能进 onMessage —— 会被 content += data 追加进气泡变成一坨 JSON。
    onStatus(payload) {
      statusText.value = (payload && payload.text) || ''
    },
    onMessage(data) {
      // 防御（2026-09-07 合并统一流后）：剥离话术三牌事件标记，避免 JSON 残文进正文
      if (data && data.startsWith('@@ADVICE@@')) return
      // 首个正文帧到达 → 占位完成使命。⛔ 放在最前面：被剥离的标记帧不代表正文已开始。
      statusText.value = ''
      messages.value[aiMsgIdx].content += data
      scrollToBottom()
    },
    /**
     * 话术三牌（FR-CORE-01）：**结构化载荷走结构化通道**，渲染成卡片。
     * ⛔ 2026-10-13 修正前：这里没有分支，advice 的 JSON 被当正文追加进气泡
     * —— 核心差异化功能，用户看到的是一坨 JSON。
     */
    onAdvice(payload) {
      const tiers = (payload && payload.tiers) || []
      if (!tiers.length) return
      messages.value[aiMsgIdx].advice = tiers
      statusText.value = ''
      scrollToBottom()
    },
    /**
     * 情绪刹车片（ADR-87）：展示温和的说明 + 「继续发送」。
     * ⛔ 修正前：刹车文案作为正文流出来，里面还带着开发参数 `continueBrake=true`，
     * 而且前端没有继续通道 —— 用户在最需要被照顾的时刻被顶回去且走不下去。
     */
    onBrake(payload) {
      const msg = messages.value[aiMsgIdx]
      const text = (payload && payload.message) || '我注意到你现在情绪比较激动。可以先缓一缓。'
      typewrite(aiMsgIdx, text)
      // 记住原始请求，供「继续发送」原样重发
      msg.brake = { text, images }
      msg.brakeBlocked = true
      statusText.value = ''
      loading.value = false
      scrollToBottom()
    },
    // 打字机渲染排队告知/错误（2026-09-07：'都要打字机效果'——非正常回复也逐字呈现）
    onBusy(body) {
      const text = (body && body.message) || '当前咨询较多，建议稍后再试。'
      const retry = body && body.data && body.data.retryAfterSec
      typewrite(aiMsgIdx, text + (retry ? `（约 ${retry} 秒后可重试）` : ''))
      statusText.value = ''
      loading.value = false
    },
    onError(err) {
      const text = (err && err.message && err.message !== 'Failed to fetch')
        ? err.message : '连接失败，请稍后重试。'
      typewrite(aiMsgIdx, text)
      statusText.value = ''
      loading.value = false
    },
    onComplete() {
      const msg = messages.value[aiMsgIdx]
      // 刹车已处理过这一轮（正文=刹车说明 + 继续按钮）：不要当正常回信做后续动作
      if (msg.brakeBlocked) { loading.value = false; return }
      // 只有三牌卡片、没有正文时不要塞 '...'（卡片本身就是内容）
      if (!msg.content && !(msg.advice && msg.advice.length)) msg.content = '...'
      statusText.value = ''
      loading.value = false
      scrollToBottom()
      // ② 行动卡：回信落定后，从三牌建议抽一条可跟踪的行动
      maybeCreateActionItem(msg.content)
      // ④ 情绪时间线：上报**会话首条**用户消息打分。
      // ⛔ 后端按 chatId **去重**（SentimentService.scoreText：`if (dup > 0) return;`）⇒
      //    一个会话只打一次分，**后续上报全是 no-op**。所以这里刻意取**会话首条**用户消息，
      //    与 timeline 懒补算的口径（类注释"打分材料=会话前几条消息"）保持一致。
      // ⚠️ 本行原注释写的是"上报本轮用户消息打分"——**与实现不符**。正是这类撒谎的注释
      //    让审计误判成"每轮都在打分、图表是重复数据"（2026-10-13 实测证伪）。
      //    行为没变，注释改对：**注释撒谎会把后面所有人（包括自动审计）带偏。**
      const opening = messages.value.find(m => m.role === 'user')
      if (opening && opening.content) reportSentiment(chatId.value, opening.content)
    }
  }, images.map((i) => i.id), continueBrake)   // ⛔ 第 4 个参数：逗号分隔的 mediaIds（后端分流到视觉节点）
}

/** 点开图片看大图（原生 dialog 最省事，且不引入依赖） */
function openImage(im) {
  const url = im.previewUrl || mediaUrl(im.id)
  window.open(url, '_blank', 'noopener')
}

function vote(msgIdx, type) {
  if (showFeedback.value === msgIdx) {
    showFeedback.value = null
    return
  }
  if (voteStates.value[msgIdx] === type) {
    voteStates.value[msgIdx] = 'NEUTRAL'
    voteMessage(chatId.value, msgIdx, 'NEUTRAL', '')
    return
  }
  if (type === 'LIKE') {
    voteStates.value[msgIdx] = 'LIKE'
    showFeedback.value = null
    voteMessage(chatId.value, msgIdx, 'LIKE', '')
    return
  }
  showFeedback.value = msgIdx
  feedbackText.value = ''
}

function submitFeedback(msgIdx) {
  voteStates.value[msgIdx] = 'DISLIKE'
  voteMessage(chatId.value, msgIdx, 'DISLIKE', feedbackText.value)
  showFeedback.value = null
  feedbackText.value = ''
}

onUnmounted(() => {
  window.removeEventListener('lh:user-updated', syncMinorMode)
  typewriter.stop()
  if (cancelSSE) cancelSSE()
})
</script>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: transparent;
}

/* ---------- 信头 ---------- */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 22px 12px;
  flex-shrink: 0;
  border-bottom: 1.5px solid var(--ink-line);
  background: var(--desk-raised);
  box-shadow: 0 10px 26px -18px oklch(12% 0.04 62 / 0.8);
  position: relative;
  z-index: 2;
}
.letterhead {
  display: flex;
  align-items: center;
  gap: 12px;
}
.letterhead-stamp {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: var(--r-seal);
  border: 2px solid var(--accent);
  border-style: dashed;
  color: var(--accent-deep);
  font-family: var(--font-hand);
  font-size: 20px;
  font-weight: 700;
  transform: rotate(-10deg);
  background: var(--paper-card);
  flex-shrink: 0;
}
.letterhead-titles { display: flex; flex-direction: column; line-height: 1.15; }
.letterhead-title {
  font-size: 22px;
  margin: 0;
  letter-spacing: 0.14em;
}
.letterhead-sub {
  font-family: var(--font-hand);
  font-size: 11px;
  color: var(--ink-faint);
  letter-spacing: 0.2em;
  margin-top: 2px;
}
.back-btn {
  font-family: var(--font-hand);
  font-size: 20px;
  color: var(--ink-soft);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 6px 10px;
  border-radius: var(--r-seal);
  transition: background 0.15s, transform 0.15s;
}
.back-btn:hover { background: var(--paper-deep); transform: translateX(-2px); }

/* ---------- 消息区（信纸横格） ---------- */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 26px clamp(14px, 5vw, 40px) 18px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  /* 极淡信纸横线（回信纸的横格） */
  background-image: repeating-linear-gradient(
    transparent 0, transparent 35px,
    oklch(44% 0.02 66 / 0.16) 35px, oklch(44% 0.02 66 / 0.16) 36px
  );
  scroll-behavior: smooth;
}
.chat-messages::-webkit-scrollbar { width: 9px; }
.chat-messages::-webkit-scrollbar-thumb {
  background: var(--ink-line);
  border-radius: var(--r-ctl);
  border: 2px solid var(--desk);
}

/* ---------- 消息（AI = 收到的回信；user = 你寄出的信） ---------- */
.message {
  display: flex;
  flex-direction: column;
  max-width: min(88%, 680px);
}
.message-user { align-self: flex-end; align-items: flex-end; }
.message-ai { align-self: flex-start; align-items: flex-start; }

.message-sender {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 5px 6px;
}
.msg-stamp-mini {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: var(--r-seal);
  border: 1.4px solid var(--accent);
  color: var(--accent-deep);
  font-family: var(--font-hand);
  font-size: 11px;
  font-weight: 700;
  transform: rotate(-8deg);
  background: var(--paper-card);
}
.msg-sender-name { font-size: 13px; color: var(--ink-soft); letter-spacing: 0.12em; }

/* ---------- AI 生成标识（2026-10-13 合规：显式标识）----------
   ⛔ 徽章必须**成对取** --chip-bg/--chip-fg：用 --accent-glow 当字 + --accent-wash 当底
      在浅色主题下就是"浅字浮浅底"（实测对比度 2.42，见 style.css 的 token 注释）。
   尺寸刻意做小、位置固定在发送者名旁 —— 显著 ≠ 抢戏，它要的是"每条都在"。 */
.ai-tag {
  font-size: 10.5px;
  line-height: 1.6;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--chip-fg);
  background: var(--chip-bg);
  border: 1px solid var(--accent);
  border-radius: var(--r-pill);
  padding: 1px 8px;
  flex-shrink: 0;
}

/* ---------- 未成年人模式提示条 ---------- */
.minor-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  padding: 7px 22px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--chip-fg);
  background: var(--chip-bg);
  border-bottom: 1px solid var(--ink-line);
}
.minor-banner .ico { flex-shrink: 0; }

.message-content {
  padding: 13px 18px 13px;
  font-size: 15px;
  line-height: 1.85;
  word-break: break-word;
  position: relative;
}
.message-ai .message-content {
  /* 材质：与全局"信纸"一致 —— 纸纹 + 显式底色（渐变失败也不透明） */
  background-color: var(--paper-card);
  background-image: var(--paper-noise), linear-gradient(172deg, oklch(96.5% 0.012 80), var(--paper-card-warm));
  background-blend-mode: overlay, normal;
  border: 1px solid oklch(80% 0.02 70 / 0.28);
  border-radius: var(--r-bubble-ai);
  box-shadow: 0 1px 0 oklch(60% 0.03 66 / 0.14), 0 14px 28px -16px oklch(12% 0.03 62 / 0.8);
  color: var(--ink-on-paper);   /* 暖纸底上必须用深墨：深色主题下曾整页漏了这条（浏览器实测抓到） */
}
.message-ai .message-content::before {
  content: '';
  position: absolute;
  left: -1px; top: -1px;
  width: 34px; height: 34px;
  border-top: 2px solid var(--accent);
  border-left: 2px solid var(--accent);
  border-radius: 4px 0 0 0;
  opacity: 0.6;
}
.message-user .message-content {
  /* 实体感：浅→深的"上光"渐变 + 顶部一道内高光；底色显式给出（渐变失败不透明） */
  background-color: var(--accent);
  background-image: linear-gradient(180deg, oklch(58% 0.135 28), var(--accent) 55%, var(--accent-deep));
  color: oklch(97% 0.012 80);
  border-radius: var(--r-bubble-user);
  box-shadow: inset 0 1px 0 oklch(72% 0.1 30 / 0.35), 0 10px 22px -14px oklch(40% 0.1 25 / 0.6);
}
.message-user .message-content :deep(strong),
.message-user .message-content :deep(h1),
.message-user .message-content :deep(h2),
.message-user .message-content :deep(h3) { color: oklch(98% 0.01 80); }
.message-user .message-content :deep(a) { color: oklch(94% 0.05 80); }

/* ---------- 首次体验引导（ADR-91）---------- */
.guide-open-btn {
  background: transparent;
  border: 1px dashed var(--ink-line);
  color: var(--ink-soft);
  font-size: 12.5px;
  padding: 4px 10px;
  border-radius: var(--r-ctl);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.guide-open-btn:hover { background: var(--desk-raised); color: var(--ink); }
.guide-card {
  margin: 10px 0 4px;
  padding: 12px 14px;
  border-left: 3px solid var(--accent);
}
.guide-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.guide-title { font-size: 15px; color: var(--ink-on-paper); }
.guide-close {
  background: transparent;
  border: 1px solid var(--ink-line);
  color: var(--ink-on-paper-soft);
  font-size: 12px;
  padding: 3px 10px;
  border-radius: var(--r-ctl);
  cursor: pointer;
}
.guide-close:hover { background: var(--accent-wash); color: var(--accent-text); }
.guide-rows { display: flex; flex-direction: column; gap: 6px; }
.guide-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  text-align: left;
  background: var(--paper-card-warm);
  border: none;
  border-radius: var(--r-ctl);
  padding: 8px 11px;
  cursor: pointer;
  transition: background 0.15s, transform 0.15s;
}
.guide-row:hover { background: var(--accent-wash); transform: translateX(2px); }
.guide-row-name { font-size: 13.5px; font-weight: 700; color: var(--accent-text); }
.guide-row-desc { font-size: 12.5px; line-height: 1.5; color: var(--ink-on-paper-faint); }
.guide-foot { margin-top: 8px; font-size: 12px; color: var(--ink-on-paper-faint); }

/* ---------- 话术三牌（FR-CORE-01）：结构化卡片 ---------- */
.advice-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}
.advice-card {
  border-left: 3px solid var(--accent);
  background: var(--paper-card-warm);
  border-radius: var(--r-ctl);
  padding: 9px 12px;
}
.advice-tier-name {
  font-size: 12.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--accent-text);
  margin-bottom: 4px;
}
.advice-tier-content {
  font-size: 14px;
  line-height: 1.6;
  color: var(--ink-on-paper);
  white-space: pre-wrap;
}
.advice-tier-reaction {
  margin-top: 5px;
  font-size: 12.5px;
  color: var(--ink-on-paper-faint);
}

/* ---------- 情绪刹车片（ADR-87）：继续发送 ---------- */
.brake-row {
  display: flex;
  justify-content: flex-start;
  margin-top: 10px;
}
.brake-continue {
  border: 1px solid var(--accent);
  background: transparent;
  color: var(--accent-text);
  font-size: 13px;
  padding: 5px 14px;
  border-radius: var(--r-ctl);
  cursor: pointer;
  transition: background 0.15s, transform 0.15s;
}
.brake-continue:hover { background: var(--accent-wash); transform: translateY(-1px); }

/* ---------- 投票行 ---------- */
.vote-row {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 8px;
  padding-left: 8px;
  opacity: 0.72;
  transition: opacity 0.18s;
}
.message-ai:hover .vote-row { opacity: 1; }
.vote-btn {
  background: transparent;
  border: 1px solid transparent;
  font-size: 15px;
  padding: 2px 6px;
  border-radius: var(--r-ctl);
  cursor: pointer;
  transition: transform 0.15s cubic-bezier(0.34, 1.56, 0.64, 1), background 0.15s;
}
.vote-btn:hover { transform: scale(1.2) rotate(-4deg); background: var(--desk-raised); }
.vote-active-like { background: oklch(44% 0.07 150 / 0.8) !important; }
.vote-active-dislike { background: oklch(44% 0.07 27 / 0.8) !important; }
.feedback-input {
  border: none;
  border-bottom: 1.4px solid var(--ink-line);
  background: transparent;
  font-size: 13px;
  color: var(--ink);
  outline: none;
  width: 180px;
  padding: 2px 4px;
}
.feedback-input::placeholder { color: var(--ink-faint); }
.feedback-input:focus { border-bottom-color: var(--accent); }

/* ---------- 打字中（墨点） ---------- */
.thinking-text { margin-right: 6px; opacity: 0.72; color: var(--ink-on-paper); }
.typing-dots { display: inline-flex; gap: 3px; padding: 2px 4px; }
.typing-dots .dot {
  width: 6px; height: 6px;
  border-radius: var(--r-seal);
  background: var(--accent);
  display: inline-block;
  animation: dot-bounce 1.1s ease-in-out infinite;
}
.typing-dots .dot:nth-child(2) { animation-delay: 0.16s; }
.typing-dots .dot:nth-child(3) { animation-delay: 0.32s; }
@keyframes dot-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.45; }
  30% { transform: translateY(-5px); opacity: 1; }
}

/* ---------- 空状态：一封待写的信 ---------- */
.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px 0;
}
.empty-letter {
  width: min(440px, 92%);
  padding: 30px 32px 26px;
  background: var(--paper-card);
  text-align: center;
}
.empty-letter-head { display: flex; align-items: center; justify-content: center; gap: 12px; margin-bottom: 14px; }
.empty-letter-stamp {
  width: 40px; height: 40px;
  font-size: 18px;
  border: 2px dashed var(--accent);
}
/* 空态相片：与卡片同族的毛边圆角，宽度收一点，别把信纸卡撑满 */
.empty-letter-photo { width: 100%; aspect-ratio: 21 / 8; border-radius: var(--r-paper); margin-bottom: 16px; box-shadow: 0 8px 20px -14px oklch(20% 0.03 40 / 0.8); }

.empty-letter-title {
  font-size: 24px;
  letter-spacing: 0.18em;
  margin: 0;
}
.empty-letter-body {
  color: var(--ink-soft);
  font-size: 14.5px;
  line-height: 2;
  margin-bottom: 20px;
}
.empty-quicks {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: stretch;
}
.quick-ask {
  font-size: 14px;
  text-align: center;
  padding: 0.55em 1em;
  opacity: 0.9;
}

/* ---------- 输入区 ---------- */
.chat-input-area {
  flex-shrink: 0;
  padding: 12px 20px 14px;
  border-top: 1.5px solid var(--ink-line);
  background: var(--desk-raised);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.input-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 780px;
  margin: 0 auto;
  width: 100%;
  padding: 10px 12px 10px 20px;
  border-radius: var(--r-pill);
}
/* 纸面墨色由 .letter-card 的 token 重映射提供（见 style.css「纸面墨色重映射」）。
   此前这里是 color: var(--ink)（深色主题浅墨）⇒ 实测对比度 1.16，**打进去的字看不见**。 */
.chat-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 15.5px;
  color: var(--ink);
  min-width: 0;
}
.chat-input::placeholder { color: var(--ink-faint); font-family: var(--font-hand); letter-spacing: 0.06em; }
.chat-input:disabled { opacity: 0.6; }
.send-btn {
  border: none;
  border-radius: var(--r-pill);
  /* 与 .btn-hand.primary 统一：wine -> wine-deep（原来用 wine-glow 起头，看着发粉） */
  background-color: var(--accent);
  background-image: linear-gradient(180deg, var(--accent), var(--accent-deep));
  color: oklch(97% 0.012 80);
  font-family: var(--font-hand);
  font-size: 15px;
  letter-spacing: 0.14em;
  padding: 0.55em 1.5em;
  cursor: pointer;
  flex-shrink: 0;
  transition: transform 0.16s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.16s, opacity 0.15s;
  box-shadow: 0 8px 18px -10px oklch(46% 0.12 28 / 0.85);
}
.send-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 12px 22px -10px oklch(46% 0.12 28 / 0.9); }
.send-btn:active:not(:disabled) { transform: translateY(1px) scale(0.97); }
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.input-hint {
  max-width: 780px;
  margin: 0 auto;
  width: 100%;
  font-family: var(--font-hand);
  font-size: 11.5px;
  color: var(--ink-faint);
  letter-spacing: 0.1em;
  text-align: center;
}

/* 常驻免责声明（docs/07 §5.1）：比输入提示再轻一档，但必须在页面上一直可见 */
.ai-disclaimer {
  max-width: 780px;
  margin: 2px auto 0;
  width: 100%;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-faint);
  text-align: center;
}

/* ---- TA 视角推演（2026-09-08） ---- */
/* 与输入框同宽居中：此前左对齐而输入框居中，看着像"掉在页面外" */
.ta-view-bar {
  display: flex; align-items: center; gap: 10px; margin: 8px auto 0;
  max-width: 780px; width: 100%;
}
.ta-view-btn {
  background: var(--paper-card);
  border: 1.4px dashed var(--ink-line);
  border-radius: var(--r-paper);
  padding: 6px 14px;
  font-family: var(--font-hand);
  font-size: 13px;
  color: var(--ink-soft);
  cursor: pointer;
  transition: transform 0.15s, border-color 0.15s, color 0.15s;
}
.ta-view-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent-deep); transform: translateY(-1px); }
.ta-view-btn:disabled { opacity: 0.5; cursor: default; }
.ta-view-hint { font-size: 12px; color: var(--ink-faint); }
.ta-picker { margin-top: 10px; padding: 12px 14px; }
.ta-picker-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.ta-picker-title { font-family: var(--font-hand); font-size: 14px; color: var(--ink); }
.ta-close { background: none; border: none; color: var(--ink-faint); cursor: pointer; font-size: 13px; }
.ta-persona-grid { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.ta-persona {
  border: 1.2px solid var(--ink-line);
  background: transparent;
  border-radius: var(--r-paper);
  padding: 5px 12px;
  font-family: var(--font-hand);
  font-size: 13px;
  color: var(--ink-soft);
  cursor: pointer;
}
.ta-persona.active { background: var(--chip-bg); border-color: var(--accent); color: var(--chip-fg); }
.ta-custom-input {
  width: 100%;
  border: 1.2px solid var(--ink-line);
  border-radius: var(--r-ctl);
  padding: 7px 10px;
  font-size: 13px;
  background: var(--paper-card-warm);
  margin-bottom: 10px;
}
.ta-go { width: 100%; padding: 8px; }
.ta-result { margin-top: 12px; padding: 12px 14px; border-left: 3px solid var(--accent); }
.ta-result-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.ta-avatar {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; border-radius: var(--r-seal);
  background-color: var(--accent);
  background-image: linear-gradient(180deg, var(--accent), var(--accent-deep));
  color: var(--desk); font-size: 12px;
}
.ta-name { font-family: var(--font-hand); font-size: 14px; color: var(--ink); }
.ta-reply { font-size: 14px; color: var(--ink); line-height: 1.7; margin: 4px 0 6px; }
.ta-insight { font-size: 12.5px; color: var(--ink-soft); background: var(--paper-deep); padding: 6px 10px; border-radius: var(--r-ctl); }
.ta-disclaimer { font-size: 11.5px; color: var(--ink-faint); margin-top: 8px; }

/* ---- 行动卡（2026-09-08 产品闭环 ②） ---- */
.action-dock { margin: 10px 0 4px; padding: 10px 14px; border-left: 3px solid var(--accent); }
.action-dock-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.action-dock-title { font-family: var(--font-hand); font-size: 13.5px; color: var(--ink); }
.action-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 4px 0; }
.action-check { display: flex; align-items: flex-start; gap: 8px; cursor: pointer; }
.action-check input { margin-top: 3px; accent-color: var(--accent); }
.action-content { font-size: 13px; color: var(--ink-soft); line-height: 1.5; }
.action-skip {
  background: none; border: none; color: var(--ink-faint);
  font-size: 12px; cursor: pointer; flex-shrink: 0;
}
.action-skip:hover { color: var(--accent-deep); }
</style>

<!-- Non-scoped: these must apply to v-html rendered content -->
<style>
.think-text {
  font-size: 12.5px;
  color: var(--ink-faint);
}
.pdf-card {
  background: var(--paper-card-warm);
  border: 1px dashed var(--ink-line);
  border-radius: var(--r-ctl);
  padding: 12px 16px;
  margin: 10px 0;
}
.pdf-card-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.pdf-card-name {
  font-weight: 700;
  font-size: 13px;
  color: var(--ink);
  word-break: break-all;
}
.pdf-card-actions { display: flex; gap: 8px; flex-shrink: 0; }
.pdf-btn {
  display: inline-block;
  padding: 6px 16px;
  border-radius: var(--r-paper);
  font-size: 13px;
  text-decoration: none;
  cursor: pointer;
  border: none;
  transition: transform 0.15s cubic-bezier(0.34, 1.56, 0.64, 1), background 0.15s;
  font-family: var(--font-hand);
}
.pdf-btn:hover { transform: translateY(-1px); }
.pdf-btn-preview { background: var(--accent); color: oklch(98% 0.012 78); }
.pdf-btn-preview:hover { background: var(--accent-deep); }
.pdf-btn-download {
  background: transparent;
  color: var(--ink-soft);
  border: 1.4px solid var(--ink-line);
}
.pdf-btn-download:hover { background: var(--paper-deep); color: var(--ink); }
.msg-link { color: var(--accent-deep); text-decoration: underline dotted; }
.step-badge {
  display: inline-block;
  padding: 1px 10px;
  border-radius: var(--r-ctl);
  font-size: 11px;
  font-weight: 700;
  margin-right: 6px;
  letter-spacing: 0.5px;
}
.step-think {
  background: var(--accent-wash);
  color: var(--accent-deep);
}
.step-result {
  background: var(--accent);
  color: oklch(98% 0.012 78);
}
.step-bullet {
  color: var(--accent);
  font-size: 11px;
  margin-right: 6px;
}
/* ---------- 聊天发图 ---------- */
.message-images { display: flex; gap: 8px; flex-wrap: wrap; margin: 0 0 8px; }
.message-images img {
  width: 132px; height: 132px; object-fit: cover; cursor: zoom-in;
  border-radius: var(--r-ctl); box-shadow: var(--sh-1); display: block;
}
/* 输入区要能容下"发图按钮 + 输入框 + 寄出"，窄屏自动换行 */
.input-wrapper { flex-wrap: wrap; }
</style>