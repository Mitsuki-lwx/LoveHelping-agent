import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 退订落地页（ADR-92）。
 *
 * ⛔ 这个页面的真实处境：邮件客户端的内置浏览器、**没有登录态**、可能截断长链接。
 * 所以三件事必须成立：公开可用、缺参数不白屏、失败给人话。
 */
const { api, query } = vi.hoisted(() => ({
  api: { unsubscribeReengage: vi.fn() },
  query: { current: {} },
}))

vi.mock('../api/index.js', () => api)
vi.mock('vue-router', () => ({
  useRoute: () => query.current,
}))

import Unsubscribe from '../views/Unsubscribe.vue'

const mountPage = () => mount(Unsubscribe, { global: { stubs: { RouterLink: true } } })

describe('Unsubscribe：一键退订落地页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    query.current = {}
  })

  it('带合法参数 → 调接口并显示成功文案', async () => {
    query.current = { query: { u: 'u1', t: 'abc123' } }
    api.unsubscribeReengage.mockResolvedValue({ data: { code: 200, data: 'ok' } })

    const w = mountPage()
    await flushPromises()

    expect(api.unsubscribeReengage).toHaveBeenCalledWith('u1', 'abc123')
    expect(w.text()).toContain('已退订')
    expect(w.text()).toContain('不会再收到这类邮件')
  })

  /** ⛔ 链接被邮件客户端截断是常态 —— 必须给可读出口，不许白屏或一直"正在处理"。 */
  it('缺参数 → 不调接口，给出可读说明', async () => {
    query.current = { query: { u: 'u1' } }

    const w = mountPage()
    await flushPromises()

    expect(api.unsubscribeReengage).not.toHaveBeenCalled()
    expect(w.text()).toContain('不完整')
    expect(w.text()).toContain('个人中心')
  })

  /** 验签失败（后端 403 走 code 非 200）→ 说人话，不显示"成功"。 */
  it('后端拒绝 → 显示失败原因而不是成功', async () => {
    query.current = { query: { u: 'u1', t: 'bad' } }
    api.unsubscribeReengage.mockResolvedValue({ data: { code: 403, message: '退订链接无效或已过期' } })

    const w = mountPage()
    await flushPromises()

    expect(w.text()).toContain('退订没成功')
    expect(w.text()).toContain('退订链接无效或已过期')
    expect(w.text()).not.toContain('已退订')
  })

  /** 网络失败也要落到人话（邮件内置浏览器网络常常很差）。 */
  it('网络异常 → 不崩，给重试建议', async () => {
    query.current = { query: { u: 'u1', t: 'abc' } }
    api.unsubscribeReengage.mockRejectedValue(new Error('Network Error'))

    const w = mountPage()
    await flushPromises()

    expect(w.text()).toContain('退订没成功')
    expect(w.text()).toContain('稍后再试')
  })
})
