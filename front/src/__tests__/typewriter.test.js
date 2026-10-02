import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createTypewriter } from '../utils/typewriter.js'

/**
 * 打字机（此前 0% 覆盖，却是聊天主路径的渲染器）。
 *
 * ⛔ 这组测试锁一个**用户可见**的坑：`start()` 播放中途再被调用时，必须**先停掉上一轮**。
 * 否则两个定时器同时往同一个气泡里写 → 用户看到新旧两段文字互相覆盖、闪烁。
 * 聊天场景里这条极容易发生：连续两封回信、或"排队告知"紧跟在正文之后。
 */
describe('createTypewriter', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  it('逐字播放，最终拼出完整文本', () => {
    const tw = createTypewriter({ intervalMs: 10, chunk: 1 })
    const seen = []
    tw.start('你好世界', p => seen.push(p))
    vi.advanceTimersByTime(100)

    expect(seen[0]).toBe('你')
    expect(seen[seen.length - 1]).toBe('你好世界')
    expect(seen).toEqual(['你', '你好', '你好世', '你好世界'])
  })

  it('⛔ 播放中途再次 start：必须先停上一轮（否则两段文字互相覆盖、闪烁）', () => {
    const tw = createTypewriter({ intervalMs: 10, chunk: 1 })
    const first = []
    const second = []
    tw.start('AAAA', p => first.push(p))
    vi.advanceTimersByTime(20)          // 第一轮播到 AA

    tw.start('BBB', p => second.push(p)) // 中途换文本
    const firstLenAtSwitch = first.length
    vi.advanceTimersByTime(100)

    // ⛔ Chai 没有 AssertJ 的 .as()（我第一版带错了习惯）—— 用普通断言 + 注释说明意图
    expect(first.length).toBe(firstLenAtSwitch)
    expect(second[second.length - 1]).toBe('BBB')
  })

  it('stop() 停止播放（组件卸载时调用）——停止后不再有任何回调', () => {
    const tw = createTypewriter({ intervalMs: 10 })
    const seen = []
    tw.start('一二三四五', p => seen.push(p))
    vi.advanceTimersByTime(20)
    tw.stop()
    const len = seen.length
    vi.advanceTimersByTime(500)
    expect(seen.length).toBe(len)
  })

  it('空文本：立刻回调一次空串并结束（不留下空转的定时器）', () => {
    const tw = createTypewriter({ intervalMs: 10 })
    const seen = []
    tw.start('', p => seen.push(p))
    // 语义：立刻回调一次空串（= 让调用方把气泡清空），**之后不再开火**
    expect(seen).toEqual([''])
    vi.advanceTimersByTime(100)
    expect(seen).toEqual([''])
  })

  it('chunk > 1：按块推进，且**不越过文本长度**', () => {
    const tw = createTypewriter({ intervalMs: 10, chunk: 2 })
    const seen = []
    tw.start('12345', p => seen.push(p))
    vi.advanceTimersByTime(100)
    expect(seen).toEqual(['12', '1234', '12345'])
    expect(seen[seen.length - 1].length).toBe(5)
  })

  it('播放到结尾自动停止（不会无限开火）', () => {
    const tw = createTypewriter({ intervalMs: 10, chunk: 1 })
    const seen = []
    tw.start('ab', p => seen.push(p))
    vi.advanceTimersByTime(1000)
    expect(seen).toEqual(['a', 'ab'])
  })
})