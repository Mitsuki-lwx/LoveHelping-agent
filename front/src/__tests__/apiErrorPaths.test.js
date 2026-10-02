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

  it('token 注入：URL 带 query 时用 & 拼接，且做 URL 编码', async () => {
    setToken('tok with space&sym')
    global.fetch = mockFetchOk(['data:x\n\n'])
    createSSE('/Love_app/chat/sse?prompt=a&chatId=b', { onMessage: vi.fn() })
    await drain()

    const calledUrl = global.fetch.mock.calls[0][0]
    expect(calledUrl).toContain('/api/Love_app/chat/sse?prompt=a&chatId=b')
    expect(calledUrl).toContain('&token=')
    expect(calledUrl).not.toContain('tok with space&sym')   // 必须编码过
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
