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

/**
 * 话术三牌（FR-CORE-01）事件名（与后端 SseBridge 的 `event:advice` 对齐）。
 *
 * ⛔ 2026-10-13 修正：原先 advice 被**故意**放行到 onMessage，注释还写着
 * "advice 的载荷是要显示的正文" —— 实测后果是用户气泡里出现
 * `{"type":"advice","tiers":[…]}` 一坨 JSON。**结构化载荷不是正文**：
 * 它必须走结构化通道由组件渲染成卡片。这条认知错在注释里，所以测试也一起错了
 * （见 sseEventRouting.test.js 的回归断言）。
 */
const ADVICE_EVENT = 'advice'

/**
 * 情绪刹车片事件名（与后端 SseBridge.BRAKE_EVENT 对齐）。
 *
 * 载荷 `{code:4002, message, canContinue:true}` —— 它不是一个"错误"，是
 * **带用户动作的阻断**：UI 要渲染「继续发送」（重发时带 continueBrake=true）。
 */
const BRAKE_EVENT = 'brake'

/** 后端"情绪刹车片"业务码（与 ChatEntry.BRAKE_CODE 对齐）。 */
const BRAKE_CODE = 4002

export function createSSE(url, { onMessage, onError, onComplete, onBusy, onStatus, onAdvice, onBrake }) {
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
        } else if (body && body.code === BRAKE_CODE && onBrake) {
          // 刹车片也可能走非 200 返回（视端点而定）：同样交给 onBrake，别落进"连接失败"
          onBrake(body)
        } else {
          onError?.(new Error((body && body.message) || ('HTTP ' + resp.status)))
        }
        return
      }
      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''
      // 当前帧的事件名。SSE 帧以空行分隔，`event:` 行只对**紧随其后**的 data 行生效。
      // 分流原则：**结构化载荷走结构化通道，只有"人话"才进正文**。
      //   · status —— 占位提示（组件用它的 text）
      //   · advice —— 三牌建议（结构化，组件渲染成卡片）
      //   · brake  —— 刹车片（结构化，组件渲染"继续发送"）
      //   · error / 默认 data —— **是人话**（错误文案、转介话术、AI 正文）→ 继续走 onMessage
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
          if (curEvent === ADVICE_EVENT) {
            // 三牌：**坏 JSON 宁可丢弃也绝不进正文**（否则用户又看到一坨 JSON）
            try { onAdvice?.(JSON.parse(data)) } catch (_) { /* 丢弃 */ }
            continue
          }
          if (curEvent === BRAKE_EVENT) {
            try { onBrake?.(JSON.parse(data)) } catch (_) { onBrake?.({ message: data, canContinue: true }) }
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

/**
 * 注册。⛔ `birthYear` **必填**（2026-10-13 合规改造）：后端据此判定未成年人并开启未成年人模式
 * （每日条数 + 时段限制）。不传/传非法值后端直接拒 —— 前端先本地校验，别让用户白等一轮。
 */
export function registerApi(username, password, birthYear) {
  return apiClient.post('/auth/register', { username, password, birthYear })
}

/**
 * 上传聊天图片（multipart）→ 返回 {id, objectKey, mediaType, width, height}。
 * ⛔ 后端只收 jpg/png/webp、≤10MB、长边 ≤2048px（ADR-11：视觉 token 贵）；
 *    前端在 ChatImagePicker 里**先校验**再上传，别让用户白等一轮。
 */
export function uploadMedia(file) {
  const fd = new FormData()
  fd.append('file', file)
  return apiClient.post('/media/upload', fd)
}

/** 已上传图片的展示地址（后端 GET /media/{id}，带归属校验）。 */
export function mediaUrl(id) {
  return `/api/media/${id}`
}

// ===== 邮箱绑定 / 找回密码（2026-10-07）=====

/** 发送「绑定邮箱」验证码（需登录） */
export function sendEmailCode(email) {
  return apiClient.post('/auth/email/code', { email })
}

/** 校验并绑定邮箱（需登录） */
export function bindEmail(email, code) {
  return apiClient.post('/auth/email/bind', { email, code })
}

/** 发送「重置密码」验证码（匿名）。⛔ 后端无论账号是否存在都返回同样文案（不泄露枚举）。 */
export function sendResetCode(username) {
  return apiClient.post('/auth/password/reset/code', { username })
}

/** 用验证码重置密码（匿名）。成功后旧 token 会失效（后端 token_version 递增）。 */
export function resetPassword(username, code, newPassword) {
  return apiClient.post('/auth/password/reset', { username, code, newPassword })
}

/**
 * 标记「首次体验引导已看过」（幂等）。
 * ⛔ 只上报"已看过"这一个方向 —— 服务端没有 un-onboard，想看回来点页面上的「能做什么」即可。
 */
/**
 * 回访邮件同意（V37，ADR-92）。⛔ 与「用对话提炼技巧」是**两类同意**，不捆绑。
 */
export function updateReengageConsent(consent) {
  return apiClient.put('/auth/reengage-consent', { consent })
}

/**
 * 一键退订（公开端点，链接里带签名）。
 * ⛔ 这个请求**不能带/依赖登录态** —— 邮件客户端的内置浏览器里通常没有。
 */
export function unsubscribeReengage(u, t) {
  return apiClient.get('/auth/reengage/unsubscribe', { params: { u, t } })
}

export function markOnboarded() {
  return apiClient.post('/auth/onboarded')
}

export function getMe() {
  return apiClient.get('/auth/me')
}

// ===== 关系档案（2026-10-07）：会**喂进模型提示词**，所以改它等于改 AI 的回信口径 =====

/** 我的关系档案：{stage, partnerAlias, updatedAt}。没填过时返回空值，不是 404。 */
export function getRelationship() {
  return apiClient.get('/profile/relationship')
}

/** 更新关系档案：{stage?, partnerAlias?}（stage 是受控枚举，非法值后端会拒） */
export function updateRelationship(payload) {
  return apiClient.put('/profile/relationship', payload)
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

export function createLoveChatSSE(prompt, chatId, handlers, mediaIds = [], continueBrake = false) {
  // ⛔ mediaIds 是**逗号分隔**（后端 parseMediaIds 的口径）；带图时后端会分流到视觉节点。
  const media = mediaIds.length ? `&mediaIds=${encodeURIComponent(mediaIds.join(','))}` : ''
  // continueBrake：情绪刹车片触发后，用户点「继续发送」时带 true 重发同一条（ADR-87）
  const brake = continueBrake ? '&continueBrake=true' : ''
  const url = `/Love_app/chat/sse?prompt=${encodeURIComponent(prompt)}&chatId=${encodeURIComponent(chatId)}${media}${brake}`
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
export function createSandboxChatSSE(sandboxId, message, handlers, mediaIds = []) {
  const media = mediaIds.length ? `&mediaIds=${encodeURIComponent(mediaIds.join(','))}` : ''
  const url = `/sandbox/chat?sandboxId=${encodeURIComponent(sandboxId)}&message=${encodeURIComponent(message)}${media}`
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

// ===== 合规面（2026-10-13）：投诉举报 + 进化技能审核 =====

/** 投诉举报分类（与后端枚举一一对应；中文文案只在前端出现，发出去的一律是枚举值）。 */
export const COMPLAINT_CATEGORIES = [
  { value: 'CONTENT', label: '内容违规' },
  { value: 'PRIVACY', label: '隐私泄露' },
  { value: 'ABUSE', label: '辱骂/骚扰' },
  { value: 'OTHER', label: '其他问题' },
]

/** 提交投诉举报：{category, content, contact?}。category ∈ CONTENT|PRIVACY|ABUSE|OTHER。 */
export function submitComplaint({ category, content, contact }) {
  const body = { category, content }
  if (contact) body.contact = contact
  return apiClient.post('/complaint', body)
}

/** 我的举报列表：[{id, category, content, contact, status, createdAt}] */
export function listMyComplaints() {
  return apiClient.get('/complaint/mine')
}

/**
 * 待审核的进化技能（管理端）。
 * ⛔ 只拉**一页**（默认 50）：生产积压上万条，全量拉取会打爆接口与页面。
 */
export function listPendingSkills(status = 'PENDING', limit = 50) {
  return apiClient.get('/evolution/skills', { params: { status, limit } })
}

/**
 * 待审候选的**真实**条数。
 * ⛔ 界面上"积压约 N 条"必须来自这里 —— 写死会与真实积压漂移（实测踩过：写死 12,532，实际 8,347）。
 */
export function countPendingSkills(status = 'PENDING') {
  return apiClient.get('/evolution/skills/count', { params: { status } })
}

/** 单条审核：status ∈ APPROVED|REJECTED。 */
export function auditSkill(id, status) {
  return apiClient.post(`/evolution/skill/${id}/audit`, null, { params: { status } })
}

// ===== 管理端 · 数据概览（2026-10-14，只读分析层）=====

/**
 * 运营数据概览：{days, generatedAt, excludedTestUsers, series, retention, funnel, features}。
 * ⛔ `excludedTestUsers` 必须在界面上显著展示 —— 不说明"已排除测试账号"，日活/新增会被误读。
 */
export function getAnalyticsOverview(days = 30) {
  return apiClient.get('/admin/analytics/overview', { params: { days } })
}

/**
 * 批量驳回低分候选（管理端，**不可逆**）。
 * 逐条审不现实（积压 12,532 条）⇒ 一次清掉质量分 ≤ maxScore 的候选；返回本次驳回条数。
 */
export function bulkRejectSkills(maxScore = 5, limit = 2000) {
  return apiClient.post('/evolution/skills/bulk-reject', null, { params: { maxScore, limit } })
}

/**
 * 进化模块「单独同意」开关（2026-10-13，docs/07 §5.4）。
 * ⛔ 默认**关闭**：只有用户明确开启后，对话才可用于提炼通用技巧。
 *    这是"单独同意"，不能拿注册时的总协议勾选替代。
 */
export function updateEvolutionConsent(consent) {
  return apiClient.put('/auth/evolution-consent', { consent })
}

export { generateChatId }
export default apiClient
