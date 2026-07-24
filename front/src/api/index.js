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

function createSSE(url, { onMessage, onError, onComplete }) {
  const fullUrl = sseUrl(url)
  const eventSource = new EventSource(fullUrl)

  eventSource.onmessage = (event) => {
    try {
      if (event.data === '[DONE]') {
        onComplete?.()
        eventSource.close()
        return
      }
      if (event.data) {
        onMessage?.(event.data)
      }
    } catch (e) {
      console.error('SSE onmessage error:', e)
    }
  }

  eventSource.onerror = () => {
    try {
      if (eventSource.readyState === EventSource.CLOSED) {
        onComplete?.()
      } else {
        onError?.(new Error('SSE connection failed'))
      }
    } catch (e) {
      console.error('SSE onerror handler error:', e)
    }
    eventSource.close()
  }

  return () => {
    eventSource.close()
  }
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
