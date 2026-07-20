<template>
  <div class="chat-page">
    <div class="chat-header">
      <button class="back-btn" @click="goHome">← 返回</button>
      <span class="header-title">AI 超级智能体</span>
    </div>

    <div class="chat-messages" ref="messagesRef">
      <div v-if="messages.length === 0" class="empty-state">
        <div class="empty-icon">🤖</div>
        <p>你好！我是 LoveManus，全能 AI 助手，<br>有任何任务都可以交给我！</p>
      </div>
      <div
        v-for="(msg, idx) in messages"
        :key="idx"
        :class="['message', msg.role === 'user' ? 'message-user' : 'message-ai']"
      >
        <div class="message-content" v-html="renderMarkdown(msg.content)"></div>
      </div>
      <div v-if="loading" class="message message-ai">
        <div class="message-content">
          <span class="typing-dots">
            <span class="dot">.</span><span class="dot">.</span><span class="dot">.</span>
          </span>
        </div>
      </div>
    </div>

    <div class="chat-input-area">
      <div class="input-wrapper">
        <input
          v-model="inputText"
          class="chat-input"
          placeholder="请输入你的问题..."
          @keydown.enter="sendMessage"
          :disabled="loading"
        />
        <button class="send-btn" :class="{ 'stop-btn': loading }" @click="loading ? stopMessage() : sendMessage()" :disabled="!loading && !inputText.trim()">
          {{ loading ? '停止' : '发送' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { createManusChatSSE, stopManusChat } from '../api/index.js'

const router = useRouter()
const messages = ref([])

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
  // Style emoji prefixes: 仅第一个 💭 带标签，后续仅保留徽标
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
const inputText = ref('')
const loading = ref(false)
const messagesRef = ref(null)
let cancelSSE = null
let currentSessionId = null
let userScrolledAway = false
let autoScrolling = false

const SCROLL_THRESHOLD = 120

function goHome() {
  router.push('/')
}

function onScroll() {
  if (autoScrolling) return
  const el = messagesRef.value
  if (!el) return
  const isNearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < SCROLL_THRESHOLD
  userScrolledAway = !isNearBottom
}

async function scrollToBottom() {
  await nextTick()
  const el = messagesRef.value
  if (!el) return
  if (userScrolledAway) return
  autoScrolling = true
  el.scrollTop = el.scrollHeight
  autoScrolling = false
}

function forceScrollToBottom() {
  userScrolledAway = false
  nextTick().then(() => {
    const el = messagesRef.value
    if (el) {
      autoScrolling = true
      el.scrollTop = el.scrollHeight
      autoScrolling = false
    }
  })
}

onMounted(() => {
  const el = messagesRef.value
  if (el) {
    el.addEventListener('scroll', onScroll, { passive: true })
  }
})

onUnmounted(() => {
  const el = messagesRef.value
  if (el) {
    el.removeEventListener('scroll', onScroll)
  }
})

function sendMessage() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  if (!currentSessionId) currentSessionId = crypto.randomUUID()

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true
  forceScrollToBottom()

  messages.value.push({ role: 'ai', content: '' })
  const aiMsgIdx = messages.value.length - 1

  cancelSSE = createManusChatSSE(text, currentSessionId, {
    onMessage(data) {
      const cleaned = data.replace(/^Step \d+ result:\s*/, '')
      if (cleaned) {
        const prefix = messages.value[aiMsgIdx].content ? '\n' : ''
        messages.value[aiMsgIdx].content += prefix + cleaned
      }
      scrollToBottom()
    },
    onError() {
      if (!messages.value[aiMsgIdx].content) {
        messages.value[aiMsgIdx].content = '连接失败，请稍后重试。'
      }
      loading.value = false
      scrollToBottom()
    },
    onComplete() {
      if (!messages.value[aiMsgIdx].content) {
        messages.value[aiMsgIdx].content = '...'
      }
      loading.value = false
      scrollToBottom()
    }
  })
}

function stopMessage() {
  if (currentSessionId) {
    stopManusChat(currentSessionId)
  }
  cancelSSE?.()
  loading.value = false
}

onUnmounted(() => {
  if (cancelSSE) cancelSSE()
})
</script>

<style scoped>
.chat-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-chat);
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 24px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.back-btn {
  background: none;
  border: none;
  color: var(--text-secondary);
  font-size: 14px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: all 0.2s;
}

.back-btn:hover {
  color: var(--text-white);
  background: var(--input-bg);
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-white);
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.chat-messages::-webkit-scrollbar {
  width: 6px;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: var(--border-color);
  border-radius: 3px;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  text-align: center;
  line-height: 1.8;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
}

.message {
  max-width: 75%;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.message-user {
  align-self: flex-end;
}

.message-ai {
  align-self: flex-start;
}

.message-content {
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-user .message-content {
  background: var(--bg-user-msg);
  color: var(--text-white);
  border-bottom-right-radius: 4px;
}

.message-ai .message-content {
  background: var(--bg-ai-msg);
  color: var(--text-primary);
  border-bottom-left-radius: 4px;
}

.typing-dots {
  display: inline-flex;
  gap: 2px;
}

.dot {
  animation: blink 1.4s infinite;
  font-size: 20px;
  line-height: 1;
}

.dot:nth-child(2) { animation-delay: 0.2s; }
.dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes blink {
  0%, 80%, 100% { opacity: 0; }
  40% { opacity: 1; }
}

.chat-input-area {
  padding: 16px 24px;
  background: var(--bg-secondary);
  border-top: 1px solid var(--border-color);
  flex-shrink: 0;
}

.input-wrapper {
  display: flex;
  gap: 12px;
  max-width: 800px;
  margin: 0 auto;
}

.chat-input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid var(--border-color);
  border-radius: 10px;
  background: var(--input-bg);
  color: var(--text-primary);
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.chat-input:focus {
  border-color: var(--accent);
}

.chat-input:disabled {
  opacity: 0.6;
}

.chat-input::placeholder {
  color: var(--text-secondary);
}

.send-btn {
  padding: 12px 24px;
  border: none;
  border-radius: 10px;
  background: var(--accent);
  color: var(--text-white);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
  white-space: nowrap;
}

.send-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.stop-btn {
  background: #e74c3c;
}

.stop-btn:hover:not(:disabled) {
  background: #c0392b;
}

.pdf-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  padding: 12px 16px;
  margin: 8px 0;
}

.pdf-card-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.pdf-card-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--text-primary);
  word-break: break-all;
}

.pdf-card-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.pdf-btn {
  display: inline-block;
  padding: 6px 16px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  cursor: pointer;
  transition: background 0.2s;
  border: none;
}

.pdf-btn-preview {
  background: var(--accent);
  color: var(--text-white);
}

.pdf-btn-preview:hover {
  background: var(--accent-hover);
}

.pdf-btn-download {
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
}

.pdf-btn-download:hover {
  background: var(--input-bg);
  color: var(--text-primary);
}

.msg-link {
  color: var(--accent);
  text-decoration: underline;
}

.step-badge {
  display: inline-block;
  padding: 1px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  margin-right: 6px;
  letter-spacing: 0.5px;
}

.step-think {
  background: #f0e0e6;
  color: #8a5a6a;
}

.step-result {
  background: var(--accent);
  color: var(--text-white);
}

.step-bullet {
  color: var(--text-secondary);
  font-size: 10px;
  margin-right: 6px;
}
</style>

<!-- Non-scoped: these must apply to v-html rendered content -->
<style>
.think-text {
  font-size: 12px;
  color: var(--text-secondary);
}
.pdf-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  padding: 12px 16px;
  margin: 8px 0;
}
.pdf-card-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.pdf-card-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--text-primary);
  word-break: break-all;
}
.pdf-card-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.pdf-btn {
  display: inline-block;
  padding: 6px 16px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  cursor: pointer;
  transition: background 0.2s;
  border: none;
}
.pdf-btn-preview {
  background: var(--accent);
  color: var(--text-white);
}
.pdf-btn-preview:hover {
  background: var(--accent-hover);
}
.pdf-btn-download {
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
}
.pdf-btn-download:hover {
  background: var(--input-bg);
  color: var(--text-primary);
}
.msg-link {
  color: var(--accent);
  text-decoration: underline;
}
.step-badge {
  display: inline-block;
  padding: 1px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  margin-right: 6px;
  letter-spacing: 0.5px;
}
.step-think {
  background: #f0e0e6;
  color: #8a5a6a;
}
.step-result {
  background: var(--accent);
  color: var(--text-white);
}
.step-bullet {
  color: var(--text-secondary);
  font-size: 10px;
  margin-right: 6px;
}
</style>
