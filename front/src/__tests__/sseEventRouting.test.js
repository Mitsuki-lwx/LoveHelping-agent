import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createSSE } from '../api/index.js'

/**
 * SSE 事件名分流（2026-09-27 phase17）。
 *
 * 背景：后端在首个 token 之前发 `event:status` 占位（实测 0.06s 到达）。
 * 本项目用 fetch-stream **手写**解析（原生 EventSource 读不到 HTTP 错误体，2026-09-07 改的），
 * 原实现"凡是 data: 行一律当正文" → status 的 JSON 载荷会被拼进气泡。
 *
 * ⛔ 这组测试锁两件事：
 *   1. status **不进** onMessage（否则用户看到一坨 JSON，比白屏更糟）
 *   2. error / advice 事件的载荷**仍然进** onMessage（错误提示与三牌建议不能被吞）
 */
function mockStream(chunks) {
  const encoder = new TextEncoder()
  const body = new ReadableStream({
    start(controller) {
      for (const c of chunks) controller.enqueue(encoder.encode(c))
      controller.close()
    },
  })
  global.fetch = vi.fn(async () => ({ ok: true, body }))
}

/** 等微任务队列把流读完（createSSE 内部是 async 循环） */
const drain = () => new Promise(r => setTimeout(r, 30))

describe('createSSE 事件名分流', () => {
  beforeEach(() => { localStorage.clear() })
  afterEach(() => { vi.restoreAllMocks() })

  it('event:status 走 onStatus，不进 onMessage', async () => {
    mockStream([
      'event:status\ndata:{"stage":"thinking","text":"让我想想…"}\n\n',
      'data:正文第一段\n\n',
    ])
    const onStatus = vi.fn(), onMessage = vi.fn(), onComplete = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onStatus, onMessage, onComplete })
    await drain()

    expect(onStatus).toHaveBeenCalledTimes(1)
    expect(onStatus.mock.calls[0][0]).toEqual({ stage: 'thinking', text: '让我想想…' })
    // ⛔ 核心断言：占位载荷一个字都不许漏进正文
    expect(onMessage).toHaveBeenCalledTimes(1)
    expect(onMessage).toHaveBeenCalledWith('正文第一段')
    expect(onComplete).toHaveBeenCalledTimes(1)
  })

  it('event:error 的载荷仍然进 onMessage（回归：不能吞掉错误提示）', async () => {
    mockStream(['event:error\ndata:当前咨询较多，请稍后再试\n\n'])
    const onStatus = vi.fn(), onMessage = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onStatus, onMessage })
    await drain()

    expect(onMessage).toHaveBeenCalledWith('当前咨询较多，请稍后再试')
    expect(onStatus).not.toHaveBeenCalled()
  })

  it('event:advice 的载荷仍然进 onMessage（回归：三牌建议是正文）', async () => {
    mockStream(['event:advice\ndata:先确认自己的感受\n\n'])
    const onMessage = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onMessage })
    await drain()
    expect(onMessage).toHaveBeenCalledWith('先确认自己的感受')
  })

  it('帧被 chunk 边界切开也能正确归属事件名', async () => {
    // 真实网络不会按帧边界发包；解析必须跨 chunk 保持 curEvent
    mockStream(['event:st', 'atus\ndata:{"text":"让我', '想想…"}\n\n', 'data:正文\n\n'])
    const onStatus = vi.fn(), onMessage = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onStatus, onMessage })
    await drain()

    expect(onStatus).toHaveBeenCalledWith({ text: '让我想想…' })
    expect(onMessage).toHaveBeenCalledWith('正文')
  })

  it('status 载荷不是合法 JSON 时降级为纯文本，不抛也不吞正文', async () => {
    mockStream(['event:status\ndata:裸文本占位\n\n', 'data:正文\n\n'])
    const onStatus = vi.fn(), onMessage = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onStatus, onMessage })
    await drain()
    expect(onStatus).toHaveBeenCalledWith({ text: '裸文本占位' })
    expect(onMessage).toHaveBeenCalledWith('正文')
  })

  it('无 onStatus 时（其他端点/旧调用方）行为与从前逐字一致', async () => {
    mockStream(['data:甲\n\n', 'data:乙\n\n'])
    const onMessage = vi.fn()
    createSSE('/Love_app/chat/sse?x=1', { onMessage })
    await drain()
    expect(onMessage.mock.calls.map(c => c[0])).toEqual(['甲', '乙'])
  })
})
