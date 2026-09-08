<template>
  <div class="chat-page">
    <div class="chat-header">
      <button class="back-btn" @click="goHome">←</button>
      <div class="letterhead">
        <span class="letterhead-stamp anim-stamp">恋</span>
        <div class="letterhead-titles">
          <h1 class="letterhead-title hand">恋爱解忧信</h1>
          <span class="letterhead-sub">— 收信 · 回信 · 替你慢慢想 —</span>
        </div>
      </div>
      <button class="new-chat-btn btn-hand" @click="newChat" title="新建对话">✎ 新一封信</button>
    </div>

    <div class="chat-messages" ref="messagesRef">
      <div v-if="messages.length === 0" class="empty-state">
        <div class="empty-letter letter-card fold anim-letter-in">
          <div class="empty-letter-head">
            <span class="empty-letter-stamp stamp">心</span>
            <p class="hand empty-letter-title">写信给我吧</p>
          </div>
          <p class="empty-letter-body">
            说不出口的、想不通的、放不下的……<br>都可以写在这里。我会认真地读，慢慢地回。
          </p>
          <div class="empty-quicks">
            <button class="quick-ask btn-hand" @click="quickAsk('最近和对象总是因为小事冷战，我该怎么办？')">💬 总是冷战怎么办？</button>
            <button class="quick-ask btn-hand" @click="quickAsk('怎么判断对方是不是真的喜欢我？')">💬 TA 真的喜欢我吗？</button>
            <button class="quick-ask btn-hand" @click="quickAsk('分手后还是放不下，怎么走出来？')">💬 分手后走不出来</button>
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
        </div>
        <div class="message-content" v-html="renderMarkdown(msg.content)"></div>
        <div v-if="msg.role === 'ai' && !loading" class="vote-row">
          <button
            :class="['vote-btn', voteStates[idx] === 'LIKE' ? 'vote-active-like' : '']"
            @click="vote(idx, 'LIKE')"
            title="有用"
          >👍</button>
          <button
            :class="['vote-btn', voteStates[idx] === 'DISLIKE' ? 'vote-active-dislike' : '']"
            @click="vote(idx, 'DISLIKE')"
            title="没用"
          >👎</button>
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
          <span class="typing-dots">
            <span class="dot">.</span><span class="dot">.</span><span class="dot">.</span>
          </span>
        </div>
      </div>
    </div>

    <!-- ② 行动卡（2026-09-08）：把回信建议变成可跟踪的事 -->
    <div v-if="actionItems.length" class="action-dock letter-card">
      <div class="action-dock-head">
        <span class="action-dock-title">📌 说好要做的事</span>
        <button class="ta-close" @click="actionItems = []">✕</button>
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

      <!-- ① TA 视角推演（2026-09-08）：用沙盘人格预演「TA 会怎么回这句」 -->
      <div class="ta-view-bar">
        <button class="ta-view-btn" @click="openTaView" :disabled="loading || taLoading">
          {{ taLoading ? '推演中…' : '🔮 TA 视角' }}
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
        <p v-if="taResult.insight" class="ta-insight">👀 {{ taResult.insight }}</p>
        <p class="ta-disclaimer">这是按人设推演的可能反应，不是 TA 的真实想法——可以用来练手，别当成答案。</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { createLoveChatSSE, generateChatId, voteMessage, registerConversation, getConversationMessages, sandboxTaView, listSandboxPersonas, listActionItems, createActionItemFromReply, doneActionItem, removeActionItem } from '../api/index.js'
import { saveLocalConversation } from '../utils/history.js'
import { getUser } from '../utils/auth.js'
import { createTypewriter } from '../utils/typewriter.js'

const router = useRouter()
const route = useRoute()

// If URL has sessionId, continue that conversation, otherwise create new
const chatId = ref(route.query.sessionId || generateChatId())

function newChat() {
  router.push('/love-chat')
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
  html = html.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1" style="max-width:100%;max-height:400px;border-radius:8px;margin:8px 0;display:block">')
  // Handle markdown links - render PDF links as preview/download cards
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (match, linkText, url) => {
    if (url.includes('/pdf/') || url.toLowerCase().endsWith('.pdf')) {
      return `<div class="pdf-card">
        <div class="pdf-card-info">
          <div class="pdf-card-name">${linkText}</div>
          <div class="pdf-card-actions">
            <a href="${url}" target="_blank" class="pdf-btn pdf-btn-preview" rel="noopener">预览</a>
            <a href="${url}" download class="pdf-btn pdf-btn-download">下载</a>
          </div>
        </div>
      </div>`
    }
    return `<a href="${url}" target="_blank" rel="noopener" class="msg-link">${linkText}</a>`
  })
  return html.replace(/\n/g, '<br>')
}
const messages = ref([])
const inputText = ref('')
const loading = ref(false)
const messagesRef = ref(null)
const voteStates = ref({})
const showFeedback = ref(null)
const feedbackText = ref('')
let cancelSSE = null

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

function sendMessage() {
  const text = inputText.value.trim()
  if (!text) return

  if (loading.value) {
    cancelSSE?.()
    loading.value = false
  }

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true
  saveLocalConversation(chatId.value, text)
  // Register conversation ownership on first send
  registerConversation(chatId.value, text.substring(0, 50), 'love').catch(() => {})
  scrollToBottom()

  messages.value.push({ role: 'ai', content: '' })
  const aiMsgIdx = messages.value.length - 1

  // 统一入口 /Love_app/chat/sse（2026-09-07）：后端 classify 自动路由——简单/知识库/Agent 全自动
  cancelSSE = createLoveChatSSE(text, chatId.value, {
    onMessage(data) {
      // 防御（2026-09-07 合并统一流后）：剥离话术三牌事件标记，避免 JSON 残文进正文
      if (data && data.startsWith('@@ADVICE@@')) return
      messages.value[aiMsgIdx].content += data
      scrollToBottom()
    },
    // 打字机渲染排队告知/错误（2026-09-07：'都要打字机效果'——非正常回复也逐字呈现）
    onBusy(body) {
      const text = (body && body.message) || '当前咨询较多，建议稍后再试。'
      const retry = body && body.data && body.data.retryAfterSec
      typewrite(aiMsgIdx, text + (retry ? `（约 ${retry} 秒后可重试）` : ''))
      loading.value = false
    },
    onError(err) {
      const text = (err && err.message && err.message !== 'Failed to fetch')
        ? err.message : '连接失败，请稍后重试。'
      typewrite(aiMsgIdx, text)
      loading.value = false
    },
    onComplete() {
      if (!messages.value[aiMsgIdx].content) {
        messages.value[aiMsgIdx].content = '...'
      }
      loading.value = false
      scrollToBottom()
      // ② 行动卡：回信落定后，从三牌建议抽一条可跟踪的行动
      maybeCreateActionItem(messages.value[aiMsgIdx].content)
    }
  })
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
  background: linear-gradient(180deg, oklch(99% 0.008 78), var(--paper));
  box-shadow: 0 8px 18px -16px oklch(35% 0.04 62 / 0.5);
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
  border-radius: 50%;
  border: 2px solid var(--wine);
  border-style: dashed;
  color: var(--wine-deep);
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
  border-radius: 50%;
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
    oklch(70% 0.02 78 / 0.13) 35px, oklch(70% 0.02 78 / 0.13) 36px
  );
  scroll-behavior: smooth;
}
.chat-messages::-webkit-scrollbar { width: 9px; }
.chat-messages::-webkit-scrollbar-thumb {
  background: var(--ink-line);
  border-radius: 8px;
  border: 2px solid var(--paper);
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
  border-radius: 50%;
  border: 1.4px solid var(--wine);
  color: var(--wine-deep);
  font-family: var(--font-hand);
  font-size: 11px;
  font-weight: 700;
  transform: rotate(-8deg);
  background: var(--paper-card);
}
.msg-sender-name { font-size: 13px; color: var(--ink-soft); letter-spacing: 0.12em; }

.message-content {
  padding: 13px 18px 13px;
  font-size: 15px;
  line-height: 1.85;
  word-break: break-word;
  position: relative;
}
.message-ai .message-content {
  background: var(--paper-card);
  border: 1px solid var(--ink-line);
  border-radius: 4px 14px 14px 14px;
  box-shadow: 0 1px 0 oklch(50% 0.02 62 / 0.07), 0 8px 18px -14px oklch(35% 0.04 62 / 0.45);
}
.message-ai .message-content::before {
  content: '';
  position: absolute;
  left: -1px; top: -1px;
  width: 34px; height: 34px;
  border-top: 2px solid var(--wine);
  border-left: 2px solid var(--wine);
  border-radius: 4px 0 0 0;
  opacity: 0.55;
}
.message-user .message-content {
  background: linear-gradient(180deg, var(--wine), var(--wine-deep));
  color: oklch(98% 0.012 78);
  border-radius: 14px 4px 14px 14px;
  box-shadow: 0 10px 22px -14px oklch(40% 0.1 25 / 0.6);
}
.message-user .message-content :deep(strong),
.message-user .message-content :deep(h1),
.message-user .message-content :deep(h2),
.message-user .message-content :deep(h3) { color: oklch(99% 0.01 78); }
.message-user .message-content :deep(a) { color: oklch(94% 0.06 78); }

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
  border-radius: 10px;
  cursor: pointer;
  transition: transform 0.15s cubic-bezier(0.34, 1.56, 0.64, 1), background 0.15s;
}
.vote-btn:hover { transform: scale(1.2) rotate(-4deg); background: var(--paper-deep); }
.vote-active-like { background: oklch(90% 0.05 150 / 0.5) !important; }
.vote-active-dislike { background: oklch(90% 0.05 27 / 0.45) !important; }
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
.feedback-input:focus { border-bottom-color: var(--wine); }

/* ---------- 打字中（墨点） ---------- */
.typing-dots { display: inline-flex; gap: 3px; padding: 2px 4px; }
.typing-dots .dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: var(--wine);
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
  border: 2px dashed var(--wine);
}
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
  background: linear-gradient(0deg, var(--paper), oklch(99% 0.006 78));
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
  border-radius: 26px;
}
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
  border-radius: 20px;
  background: linear-gradient(180deg, var(--wine), var(--wine-deep));
  color: oklch(98% 0.012 78);
  font-family: var(--font-hand);
  font-size: 15px;
  letter-spacing: 0.14em;
  padding: 0.55em 1.5em;
  cursor: pointer;
  flex-shrink: 0;
  transition: transform 0.16s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.16s, opacity 0.15s;
  box-shadow: 0 8px 16px -10px oklch(40% 0.1 25 / 0.65);
}
.send-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 12px 20px -10px oklch(40% 0.1 25 / 0.7); }
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

/* ---- TA 视角推演（2026-09-08） ---- */
.ta-view-bar { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.ta-view-btn {
  background: var(--paper-card);
  border: 1.4px dashed var(--ink-line);
  border-radius: 14px 10px 13px 9px;
  padding: 6px 14px;
  font-family: var(--font-hand);
  font-size: 13px;
  color: var(--ink-soft);
  cursor: pointer;
  transition: transform 0.15s, border-color 0.15s, color 0.15s;
}
.ta-view-btn:hover:not(:disabled) { border-color: var(--wine); color: var(--wine-deep); transform: translateY(-1px); }
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
  border-radius: 12px 8px 11px 7px;
  padding: 5px 12px;
  font-family: var(--font-hand);
  font-size: 13px;
  color: var(--ink-soft);
  cursor: pointer;
}
.ta-persona.active { background: var(--wine-soft); border-color: var(--wine); color: var(--wine-deep); }
.ta-custom-input {
  width: 100%;
  border: 1.2px solid var(--ink-line);
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 13px;
  background: var(--paper-card-warm);
  margin-bottom: 10px;
}
.ta-go { width: 100%; padding: 8px; }
.ta-result { margin-top: 12px; padding: 12px 14px; border-left: 3px solid var(--wine); }
.ta-result-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.ta-avatar {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; border-radius: 50%;
  background: linear-gradient(180deg, var(--accent), var(--accent-hover));
  color: var(--bg-primary); font-size: 12px;
}
.ta-name { font-family: var(--font-hand); font-size: 14px; color: var(--ink); }
.ta-reply { font-size: 14px; color: var(--ink); line-height: 1.7; margin: 4px 0 6px; }
.ta-insight { font-size: 12.5px; color: var(--ink-soft); background: var(--paper-deep); padding: 6px 10px; border-radius: 8px; }
.ta-disclaimer { font-size: 11.5px; color: var(--ink-faint); margin-top: 8px; }

/* ---- 行动卡（2026-09-08 产品闭环 ②） ---- */
.action-dock { margin: 10px 0 4px; padding: 10px 14px; border-left: 3px solid var(--wine); }
.action-dock-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.action-dock-title { font-family: var(--font-hand); font-size: 13.5px; color: var(--ink); }
.action-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 4px 0; }
.action-check { display: flex; align-items: flex-start; gap: 8px; cursor: pointer; }
.action-check input { margin-top: 3px; accent-color: var(--wine); }
.action-content { font-size: 13px; color: var(--ink-soft); line-height: 1.5; }
.action-skip {
  background: none; border: none; color: var(--ink-faint);
  font-size: 12px; cursor: pointer; flex-shrink: 0;
}
.action-skip:hover { color: var(--wine-deep); }
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
  border-radius: 8px;
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
  border-radius: 14px 10px 13px 9px;
  font-size: 13px;
  text-decoration: none;
  cursor: pointer;
  border: none;
  transition: transform 0.15s cubic-bezier(0.34, 1.56, 0.64, 1), background 0.15s;
  font-family: var(--font-hand);
}
.pdf-btn:hover { transform: translateY(-1px); }
.pdf-btn-preview { background: var(--wine); color: oklch(98% 0.012 78); }
.pdf-btn-preview:hover { background: var(--wine-deep); }
.pdf-btn-download {
  background: transparent;
  color: var(--ink-soft);
  border: 1.4px solid var(--ink-line);
}
.pdf-btn-download:hover { background: var(--paper-deep); color: var(--ink); }
.msg-link { color: var(--wine-deep); text-decoration: underline dotted; }
.step-badge {
  display: inline-block;
  padding: 1px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 700;
  margin-right: 6px;
  letter-spacing: 0.5px;
}
.step-think {
  background: var(--wine-soft);
  color: var(--wine-deep);
}
.step-result {
  background: var(--wine);
  color: oklch(98% 0.012 78);
}
.step-bullet {
  color: var(--wine);
  font-size: 11px;
  margin-right: 6px;
}
</style>
