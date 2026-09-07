import axios from 'axios'
import { getToken, removeToken } from '../utils/auth.js'

const apiClient = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// Request interceptor: automatically attach token
apiClient.interceptors.request.use(config => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Response interceptor: 401 auto-clear token
apiClient.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      removeToken()
      window.location.href = '/login'
    }
    return Promise.reject(err)
  }
)

function generateChatId() {
  return crypto.randomUUID()
}

/** SSE connection (for EventSource, token passed via query param) */
function sseUrl(path) {
  const token = getToken()
  // Prepend /api prefix; in production Spring Boot context-path=/api, in dev vite proxy passes through
  const fullPath = '/api' + path
  const sep = fullPath.includes('?') ? '&' : '?'
  return `${fullPath}${token ? sep + 'token=' + encodeURIComponent(token) : ''}`
}

function createSSE(url, { onMessage, onError, onComplete, onBusy }) {
  // 2026-09-07：EventSource → fetch-stream。原 EventSource 无法读取 HTTP 错误响应体——
  // 4003 排队告知（message/data）到不了前端，只能显示固定'连接失败'。
  // fetch 版可读非 200 的 Result body：code==4003 且提供 onBusy → 结构化透传（打字机渲染）。
  const fullUrl = sseUrl(url)
  const controller = new AbortController()

  const run = async () => {
    try {
      const resp = await fetch(fullUrl, {
        headers: { Accept: 'text/event-stream' },
        signal: controller.signal,
      })
      if (!resp.ok) {
        let body = null
        try { body = await resp.json() } catch (_) {}
        if (body && body.code === 4003 && onBusy) {
          onBusy(body)
        } else {
          onError?.(new Error((body && body.message) || ('HTTP ' + resp.status)))
        }
        return
      }
      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        let idx
        while ((idx = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, idx).trim()
          buf = buf.slice(idx + 1)
          if (!line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (data === '[DONE]') { onComplete?.(); return }
          if (data) onMessage?.(data)
        }
      }
      onComplete?.()
    } catch (e) {
      if (e.name !== 'AbortError') {
        console.error('SSE fetch error:', e)
        onError?.(e)
      }
    }
  }
  run()
  return () => controller.abort()
}

// ===== Auth =====

export function loginApi(username, password) {
  return apiClient.post('/auth/login', { username, password })
}

export function registerApi(username, password) {
  return apiClient.post('/auth/register', { username, password })
}

export function getMe() {
  return apiClient.get('/auth/me')
}

// ===== Chat (SSE) =====

export function createLoveChatSSE(prompt, chatId, handlers) {
  const url = `/Love_app/chat/sse?prompt=${encodeURIComponent(prompt)}&chatId=${encodeURIComponent(chatId)}`
  return createSSE(url, handlers)
}

export function createLoveChatRagSSE(prompt, chatId, handlers) {
  const url = `/Love_app/chat/sse/rag?prompt=${encodeURIComponent(prompt)}&chatId=${encodeURIComponent(chatId)}`
  return createSSE(url, handlers)
}

export function createLoveChatToolsSSE(prompt, chatId, handlers) {
  const url = `/Love_app/chat/sse/tools?prompt=${encodeURIComponent(prompt)}&chatId=${encodeURIComponent(chatId)}`
  return createSSE(url, handlers)
}

export function createManusChatSSE(message, sessionId, handlers) {
  const url = `/Love_app/chat/LoveManus?message=${encodeURIComponent(message)}&sessionId=${encodeURIComponent(sessionId)}`
  return createSSE(url, handlers)
}

export function stopManusChat(sessionId) {
  const token = getToken()
  const qs = token ? `?token=${encodeURIComponent(token)}` : ''
  return fetch(`/api/Love_app/chat/LoveManus/stop/${encodeURIComponent(sessionId)}${qs}`)
}

// ===== Vote =====

export function voteMessage(sessionId, messageIndex, voteType, feedbackText) {
  return apiClient.post('/evolution/vote', { sessionId, messageIndex, voteType, feedbackText })
}

// ===== Memory / History =====

export function listConversations(chatType = 'love') {
  return apiClient.get('/memory/conversations', { params: { chatType } })
}

export function listAllConversations() {
  return apiClient.get('/memory/admin/conversations')
}

export function registerConversation(conversationId, title, chatType) {
  return apiClient.post('/memory/register', { conversationId, title, chatType })
}

export function getConversationMessages(conversationId) {
  return apiClient.get(`/memory/${encodeURIComponent(conversationId)}`)
}

export function clearConversation(conversationId) {
  return apiClient.delete(`/memory/${encodeURIComponent(conversationId)}`)
}

export { generateChatId }
export default apiClient
