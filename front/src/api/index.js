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

/**
 * 生成会话 ID。
 *
 * ⛔ `crypto.randomUUID()` **只在安全上下文可用**（HTTPS 或 localhost）。
 * 2026-10-02 实测（Chromium + host-resolver）：`http://127.0.0.1` → isSecureContext=true、
 * randomUUID 是 function；`http://probe.test`（普通主机名 + HTTP）→ isSecureContext=**false**、
 * randomUUID 是 **undefined**。
 * 而本函数在组件 setup 期被求值 ⇒ 生产若走 http + 内网 IP/域名，**聊天页直接挂载失败**（不是降级）。
 * ⇒ 必须有兜底，且优先用 randomUUID（有加密强度）。
 */
function generateChatId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // 退化路径：getRandomValues 在非安全上下文也可用（它不属于仅安全上下文 API）
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const b = crypto.getRandomValues(new Uint8Array(16))
    return Array.from(b, x => x.toString(16).padStart(2, '0')).join('')
  }
  // 最后兜底：时间戳 + 随机（仅用于会话标识，不承担安全职责）
  return 'c' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}

/** SSE connection (for EventSource, token passed via query param) */
/**
 * ⛔ token **不再放 URL**（2026-10-02）：URL 里的 token 会进服务器访问日志、浏览器历史、
 * Referer 头 —— 而前端自 2026-09-07 起已改 fetch-stream（不再是 EventSource），
 * **完全可以在 Authorization 头里带**。后端拦截器本就「先看 header，再退回 query」，两种都收。
 * 这里只拼路径，token 由 createSSE 放进请求头。
 */
function sseUrl(path) {
  return '/api' + path
}

/**
 * 后端 SSE 的「占位/状态」事件名（与后端 SseBridge.STATUS_EVENT 对齐）。
 *
 * ⛔ 为什么必须在前端**按事件名分流**、而不是靠"浏览器会丢弃未监听的事件"：
 * 那条规矩只对原生 `EventSource` 成立。本项目 2026-09-07 起改用 fetch-stream
 * 手写解析（原 EventSource 读不到 HTTP 错误响应体），凡是 `data:` 行一律当正文。
 * 结果 status 的载荷 `{"stage":"thinking","text":"让我想想…"}`
 * 会被 `content += data` 追加进气泡 → 用户看到一坨 JSON。
 */
const STATUS_EVENT = 'status'

export function createSSE(url, { onMessage, onError, onComplete, onBusy, onStatus }) {
  // 2026-09-07：EventSource → fetch-stream。原 EventSource 无法读取 HTTP 错误响应体——
  // 4003 排队告知（message/data）到不了前端，只能显示固定'连接失败'。
  // fetch 版可读非 200 的 Result body：code==4003 且提供 onBusy → 结构化透传（打字机渲染）。
  const fullUrl = sseUrl(url)
  const controller = new AbortController()

  const run = async () => {
    try {
      const token = getToken()
      const resp = await fetch(fullUrl, {
        headers: {
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
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
      // 当前帧的事件名。SSE 帧以空行分隔，`event:` 行只对**紧随其后**的 data 行生效。
      // ⚠️ 只对 STATUS_EVENT 分流：error / advice 事件的载荷是**要显示的正文**
      // （错误文案、三牌建议），必须继续走 onMessage，否则等于把错误提示吞了。
      let curEvent = null
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        let idx
        while ((idx = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, idx).trim()
          buf = buf.slice(idx + 1)
          if (line === '') { curEvent = null; continue }        // 帧结束
          if (line.startsWith('event:')) {
            curEvent = line.slice(6).trim()
            continue
          }
          if (!line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (data === '[DONE]') { onComplete?.(); return }
          if (!data) continue
          if (curEvent === STATUS_EVENT) {
            // 解析失败也不能影响正文流：占位只是提示，坏JSON 就退化成无提示
            try { onStatus?.(JSON.parse(data)) } catch (_) { onStatus?.({ text: data }) }
            continue
          }
          onMessage?.(data)
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

// ===== 个人中心（V18 后端新增，2026-09-07） =====

/** 更新资料：{nickname?, avatarEmoji?, bio?}（非空字段才更新） */
export function updateProfileApi(payload) {
  return apiClient.put('/auth/profile', payload)
}

/** 修改密码：{oldPassword, newPassword} */
export function changePasswordApi(payload) {
  return apiClient.put('/auth/password', payload)
}

/** 注销当前账号（级联删除全部数据，不可恢复） */
export function deleteAccountApi() {
  return apiClient.delete('/auth/account')
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

// ===== 角色模拟屋（Sandbox · 自定义模拟对话，2026-09-07 按后端能力新增） =====

export function sandboxCreate(body) {
  return apiClient.post('/sandbox/create', body)   // {channel, personaId?, customTraits?, relationshipStage?}
}
export function listSandboxPersonas() {
  return apiClient.get('/sandbox/personas')
}
export function listSandboxSessions(channel = 'REALISTIC') {
  return apiClient.get('/sandbox/list', { params: { channel } })
}
export function addMemoryFact(content, category) {
  return apiClient.post('/memory/facts', { content, category })
}
export function listActionItems() {
  return apiClient.get('/action-items')
}
export function createActionItemFromReply(chatId, replyText) {
  return apiClient.post('/action-items/from-reply', { chatId, replyText })
}
export function doneActionItem(id) {
  return apiClient.put(`/action-items/${id}/done`)
}
export function removeActionItem(id) {
  return apiClient.delete(`/action-items/${id}`)
}
export function sandboxTaView(body) {
  return apiClient.post('/sandbox/ta-view', body)   // {personaId?, customTraits?, message}
}
export function sentimentTimeline() {
  return apiClient.get('/sentiment/timeline')
}
export function reportSentiment(chatId, text) {
  return apiClient.post('/sentiment/score', { chatId, text })
}
export function sandboxReview(id) {
  return apiClient.post(`/sandbox/${id}/review`)
}
export function sandboxReset(id) {
  return apiClient.post(`/sandbox/${id}/reset`)
}
export function sandboxDelete(id) {
  return apiClient.delete(`/sandbox/${id}`)
}
export function createSandboxChatSSE(sandboxId, message, handlers) {
  const url = `/sandbox/chat?sandboxId=${encodeURIComponent(sandboxId)}&message=${encodeURIComponent(message)}`
  return createSSE(url, handlers)
}
export function listSandboxMemories(sandboxId) {
  return apiClient.get(`/sandbox/${sandboxId}/memory`)
}
export function addSandboxMemory(sandboxId, factText, type = 'FACT') {
  return apiClient.post(`/sandbox/${sandboxId}/memory`, { type, factText, sourceType: 'MANUAL' })
}
export function deleteSandboxMemory(sandboxId, memoryId) {
  return apiClient.delete(`/sandbox/${sandboxId}/memory/${memoryId}`)
}

// ===== 记忆档案（/memory/facts · 我的记忆，2026-09-07 新增） =====

export function getMyMemoryFacts() {
  return apiClient.get('/memory/facts')
}
export function updateMemoryFact(id, content) {
  return apiClient.put(`/memory/facts/${id}`, { content })
}
export function deleteMemoryFact(id) {
  return apiClient.delete(`/memory/facts/${id}`)
}

export { generateChatId }
export default apiClient
