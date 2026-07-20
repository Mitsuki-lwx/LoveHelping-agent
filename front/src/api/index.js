import axios from 'axios'

const apiClient = axios.create({
  baseURL: '/',
  timeout: 30000
})

function generateChatId() {
  return crypto.randomUUID()
}

function createSSE(url, { onMessage, onError, onComplete }) {
  const eventSource = new EventSource(url)

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

/** 普通流式对话（无检索） */
export function createLoveChatSSE(prompt, chatId, handlers) {
  const url = `/Love_app/chat/sse?prompt=${encodeURIComponent(prompt)}&chatId=${encodeURIComponent(chatId)}`
  return createSSE(url, handlers)
}

/**
 * 流式 RAG 对话（新增，第一期）。
 * 调 /Love_app/chat/sse/rag 端点，走 PGvector 知识库检索 + 流式输出。
 * 前端点击 📖 按钮后切换到使用此函数。
 */
export function createLoveChatRagSSE(prompt, chatId, handlers) {
  const url = `/Love_app/chat/sse/rag?prompt=${encodeURIComponent(prompt)}&chatId=${encodeURIComponent(chatId)}`
  return createSSE(url, handlers)
}

export function createManusChatSSE(message, sessionId, handlers) {
  const url = `/Love_app/chat/LoveManus?message=${encodeURIComponent(message)}&sessionId=${encodeURIComponent(sessionId)}`
  return createSSE(url, handlers)
}

export function stopManusChat(sessionId) {
  return fetch(`/Love_app/chat/LoveManus/stop/${encodeURIComponent(sessionId)}`)
}

export { generateChatId }
export default apiClient
