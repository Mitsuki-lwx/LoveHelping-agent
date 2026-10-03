import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createSSE, createLoveChatSSE, getConversationMessages } from '../api/index.js'
import { setToken } from '../utils/auth.js'

/**
 * API 层的关键契约（此前 38% 覆盖，缺的正是**错误路径**）。
 *
 * ⛔ 这组的重点全是"出问题时"：HTTP 非 200、4003 排队、[DONE]、token 注入。
 * 正常路径用户天天在走，出问题是低概率高代价 —— 而低概率路径恰恰是手测最容易漏的。
 */

function mockFetchOk(chunks) {
  const encoder = new TextEncoder()
  const body = new ReadableStream({
    start(controller) {
      for (const c of chunks) controller.enqueue(encoder.encode(c))
      controller.close()
    },
  })
  return vi.fn(async () => ({ ok: true, body }))
}

const drain = () => new Promise(r => setTimeout(r, 30))

describe('createSSE 错误与边界路径', () => {
  beforeEach(() => { localStorage.clear() })
  afterEach(() => { vi.restoreAllMocks() })

  it('HTTP 非 200：读**错误响应体**里的 message 交给 onError（不是笼统的"连接失败"）', async () => {
    global.fetch = vi.fn(async () => ({
      ok: false, status: 500,
      json: async () => ({ code: 5000, message: 'AI 服务暂时不可用' }),
    }))
    const onError = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onError, onMessage: vi.fn() })
    await drain()

    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError.mock.calls[0][0].message).toBe('AI 服务暂时不可用')
  })

  it('⛔ 4003 排队：有 onBusy 时走 onBusy（结构化透传），**不走 onError**', async () => {
    global.fetch = vi.fn(async () => ({
      ok: false, status: 429,
      json: async () => ({ code: 4003, message: '当前咨询较多', data: { retryAfterSec: 20 } }),
    }))
    const onBusy = vi.fn(), onError = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onBusy, onError, onMessage: vi.fn() })
    await drain()

    expect(onBusy).toHaveBeenCalledTimes(1)
    expect(onBusy.mock.calls[0][0].data.retryAfterSec).toBe(20)
    expect(onError).not.toHaveBeenCalled()
  })

  it('4003 但**没有** onBusy：降级为 onError（不能静默丢掉）', async () => {
    global.fetch = vi.fn(async () => ({
      ok: false, status: 429,
      json: async () => ({ code: 4003, message: '当前咨询较多' }),
    }))
    const onError = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onError, onMessage: vi.fn() })
    await drain()
    expect(onError).toHaveBeenCalledTimes(1)
  })

  it('非 200 且响应体不是 JSON：仍要报错，不静默', async () => {
    global.fetch = vi.fn(async () => ({
      ok: false, status: 502,
      json: async () => { throw new Error('not json') },
    }))
    const onError = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onError, onMessage: vi.fn() })
    await drain()
    expect(onError.mock.calls[0][0].message).toContain('502')
  })

  it('[DONE] 只触发 onComplete 一次，且其后不再有 onMessage', async () => {
    global.fetch = mockFetchOk(['data:正文\n\n', 'data:[DONE]\n\n', 'data:不该出现\n\n'])
    const onMessage = vi.fn(), onComplete = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onMessage, onComplete })
    await drain()

    expect(onMessage.mock.calls.map(c => c[0])).toEqual(['正文'])
    expect(onComplete).toHaveBeenCalledTimes(1)
  })

  it('流自然结束（没有 [DONE]）也会调 onComplete', async () => {
    global.fetch = mockFetchOk(['data:只有正文\n\n', 'data:第二段\n\n'])
    const onMessage = vi.fn(), onComplete = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onMessage, onComplete })
    await drain()
    expect(onMessage).toHaveBeenCalledTimes(2)
    expect(onComplete).toHaveBeenCalledTimes(1)
  })

  it('空 data 行被跳过（不能往气泡里塞空串）', async () => {
    global.fetch = mockFetchOk(['data:\n\n', 'data:有内容\n\n'])
    const onMessage = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onMessage })
    await drain()
    expect(onMessage.mock.calls.map(c => c[0])).toEqual(['有内容'])
  })

  it('⛔ token 走 Authorization 头，**不进 URL**（2026-10-02 改：URL 会进访问日志/历史/Referer）', async () => {
    setToken('tok with space&sym')
    global.fetch = mockFetchOk(['data:x\n\n'])
    createSSE('/Love_app/chat/sse?prompt=a&chatId=b', { onMessage: vi.fn() })
    await drain()

    const [calledUrl, opts] = global.fetch.mock.calls[0]
    expect(calledUrl).toContain('/api/Love_app/chat/sse?prompt=a&chatId=b')
    expect(calledUrl).not.toContain('token')                 // 路径里不许再有 token
    expect(opts.headers.Authorization).toBe('Bearer tok with space&sym')
  })

  it('无 token 时不带 Authorization 头（匿名/未登录路径不能被塞一个空 Bearer）', async () => {
    global.fetch = mockFetchOk(['data:x\n\n'])
    createSSE('/Love_app/chat/sse?prompt=a', { onMessage: vi.fn() })
    await drain()
    const [, opts] = global.fetch.mock.calls[0]
    expect(opts.headers.Authorization).toBeUndefined()
  })

  it('返回的取消函数能中断（AbortError 不冒泡到 onError）', async () => {
    let aborted = false
    const body = new ReadableStream({
      start(controller) { controller.enqueue(new TextEncoder().encode('data:x\n\n')) },
      cancel() { aborted = true },
    })
    global.fetch = vi.fn(async (url, opts) => {
      // 模拟 fetch 在 abort 后抛 AbortError
      return new Promise((resolve, reject) => {
        opts.signal.addEventListener('abort', () => {
          const e = new Error('aborted'); e.name = 'AbortError'; reject(e)
        })
        setTimeout(() => resolve({ ok: true, body }), 5)
      })
    })
    const onError = vi.fn()
    const cancel = createSSE('/Love_app/chat/sse?x=1', { onMessage: vi.fn(), onError })
    cancel()
    await drain()
    await drain()

    expect(onError).not.toHaveBeenCalled()
  })
})

describe('业务封装', () => {
  beforeEach(() => { localStorage.clear() })
  afterEach(() => { vi.restoreAllMocks() })

  it('createLoveChatSSE 打的是统一入口 /Love_app/chat/sse（不是旧的分叉端点）', async () => {
    global.fetch = mockFetchOk(['data:x\n\n'])
    createLoveChatSSE('你好', 'chat-1', { onMessage: vi.fn() })
    await drain()
    const url = global.fetch.mock.calls[0][0]
    expect(url).toContain('/Love_app/chat/sse')
    expect(url).toContain('prompt=')
  })

  it('getConversationMessages 打的是旧端点 /memory/{id}（前端只用来渲染历史，不需要 id）', async () => {
    // ⛔ 我第一版断言成 /messages —— 那是 ADR-74 新加的**带 id** 端点，
    //    前端**并没有**改用它（加载历史只用 text/messageType）。断言必须照实现写，不能照愿望写。
    const mod = await import('../api/index.js')
    vi.spyOn(mod.default, 'get').mockResolvedValue({ data: { code: 200, data: [] } })

    await getConversationMessages('conv-9')

    expect(mod.default.get).toHaveBeenCalledTimes(1)
    expect(mod.default.get.mock.calls[0][0]).toBe('/memory/conv-9')
  })

  it('conversationId 会被 URL 编码（防注入/特殊字符破坏路径）', async () => {
    const mod = await import('../api/index.js')
    vi.spyOn(mod.default, 'get').mockResolvedValue({ data: { code: 200, data: [] } })

    await getConversationMessages('a/b?c=1')

    expect(mod.default.get.mock.calls[0][0]).toBe('/memory/a%2Fb%3Fc%3D1')
  })
})

describe('会话 ID 生成（⛔ 非安全上下文下 crypto.randomUUID 是 undefined）', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it('安全上下文：优先用 randomUUID（有加密强度）', async () => {
    const mod = await import('../api/index.js')
    const id = mod.generateChatId()
    expect(typeof id).toBe('string')
    expect(id.length).toBeGreaterThan(10)
  })

  it('⛔ 非安全上下文（randomUUID 为 undefined）→ 必须仍能生成，**不能抛**', async () => {
    // 实测依据：http://probe.test（普通主机名 + HTTP）下 isSecureContext=false、crypto.randomUUID=undefined。
    // 而 generateChatId 在组件 setup 期被求值 ⇒ 抛错 = 聊天页**挂载失败**（白屏），不是降级。
    const real = globalThis.crypto
    vi.stubGlobal('crypto', { getRandomValues: real.getRandomValues.bind(real) })   // 有 getRandomValues、无 randomUUID
    const mod = await import('../api/index.js')
    expect(() => mod.generateChatId()).not.toThrow()
    expect(typeof mod.generateChatId()).toBe('string')
    expect(mod.generateChatId()).not.toBe(mod.generateChatId())   // 两次不同（确实是随机而非常量）
  })

  it('极端兜底：连 getRandomValues 都没有时也不抛（老浏览器/受限环境）', async () => {
    vi.stubGlobal('crypto', undefined)
    const mod = await import('../api/index.js')
    expect(() => mod.generateChatId()).not.toThrow()
    expect(mod.generateChatId().startsWith('c')).toBe(true)
  })
})
