import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { MOODS, QUESTIONS, SLIPS, pickByDate, todayKey, loadDaily, saveDaily, daysTogether } from '../utils/daily.js'

/**
 * 「今日」内容与规则的单测。
 *
 * 为什么这些值得永久守（都是**用户能看见**的契约）：
 *   · 同一天必须是同一题/同一签 —— 否则"每日一问"变成"随时一问"，用户会觉得被糊弄
 *   · 跨天必须失效 —— 否则昨天的心情/答案会一直粘着今天
 *   · 第 N 天必须含当天、未来日期必须拒绝 —— 算错一天用户立刻能看出来
 */
describe('daily · 按日期取内容', () => {
  it('同一天多次取到同一条（刷新不会变）', () => {
    const d = new Date(2026, 9, 6, 9, 0, 0)
    const a = pickByDate(QUESTIONS, d)
    const b = pickByDate(QUESTIONS, new Date(2026, 9, 6, 23, 59, 59))
    expect(a).toBe(b)
  })

  it('不同天会换（否则"每日"是假的）', () => {
    const seen = new Set()
    for (let i = 0; i < 10; i++) seen.add(pickByDate(QUESTIONS, new Date(2026, 9, 6 + i)))
    expect(seen.size).toBeGreaterThan(1)
  })

  it('索引永不越界（负数/极远日期也要落在列表内）', () => {
    for (const d of [new Date(1970, 0, 1), new Date(1960, 0, 1), new Date(2099, 11, 31)]) {
      expect(QUESTIONS).toContain(pickByDate(QUESTIONS, d))
      expect(SLIPS).toContain(pickByDate(SLIPS, d))
    }
  })
})

describe('daily · 每日状态存取', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => localStorage.clear())

  it('存了能读回', () => {
    saveDaily('mood', 'miss')
    expect(loadDaily('mood')).toBe('miss')
  })

  it('⛔ 跨天必须失效：昨天的心情不能粘到今天', () => {
    // 直接写一条"昨天"的记录（todayKey 由真实时间算，所以手工构造旧日期）
    localStorage.setItem('lwx_daily_mood', JSON.stringify({ date: '2000-01-01', value: 'low' }))
    expect(loadDaily('mood', null)).toBe(null)
  })

  it('没存过时返回给定的兜底值', () => {
    expect(loadDaily('answer', null)).toBe(null)
    expect(loadDaily('answer', 7)).toBe(7)
  })

  it('坏数据不抛异常（存储被写脏也要能起来）', () => {
    localStorage.setItem('lwx_daily_slip', '{不是 JSON')
    expect(loadDaily('slip', 'x')).toBe('x')
  })

  it('todayKey 是本地日期（不是 UTC），格式 YYYY-MM-DD', () => {
    const k = todayKey(new Date(2026, 0, 5, 23, 30))
    expect(k).toBe('2026-01-05')
  })
})

describe('daily · 在一起第 N 天', () => {
  it('纪念日当天 = 第 1 天（含当天）', () => {
    const now = new Date()
    const iso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
    expect(daysTogether(iso)).toBe(1)
  })

  it('未来日期返回 null（不能显示"第 -3 天"）', () => {
    const f = new Date(Date.now() + 5 * 86400000)
    const iso = `${f.getFullYear()}-${String(f.getMonth() + 1).padStart(2, '0')}-${String(f.getDate()).padStart(2, '0')}`
    expect(daysTogether(iso)).toBeNull()
  })

  it('空值/非法串返回 null（不是 NaN）', () => {
    expect(daysTogether(null)).toBeNull()
    expect(daysTogether('')).toBeNull()
    expect(daysTogether('2026/01/01')).toBeNull()
    expect(daysTogether('不是日期')).toBeNull()
  })
})

describe('daily · 内容自身的完整性', () => {
  it('每个心情都映射到一个真实存在的主题名（否则点了会切到不存在的主题）', () => {
    const THEME_IDS = ['心动', '思念', '疗愈', '深夜']
    for (const m of MOODS) {
      expect(THEME_IDS).toContain(m.theme)
      expect(m.reply.length).toBeGreaterThan(4)
    }
  })

  it('每道题都有题干、3 个选项、每项都有读法', () => {
    expect(QUESTIONS.length).toBeGreaterThanOrEqual(7)
    for (const q of QUESTIONS) {
      expect(q.q.length).toBeGreaterThan(4)
      expect(q.opts.length).toBe(3)
      for (const o of q.opts) {
        expect(o.label.length).toBeGreaterThan(0)
        expect(o.read.length).toBeGreaterThan(8)
      }
    }
  })

  it('签文没有重复（重复会让"再抽一张"抽到同一句）', () => {
    expect(new Set(SLIPS).size).toBe(SLIPS.length)
    expect(SLIPS.length).toBeGreaterThanOrEqual(10)
  })
})
