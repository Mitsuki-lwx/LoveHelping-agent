import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'

/**
 * 聊天主界面（923 行，此前 **0%** 覆盖）的关键行为。
 *
 * ⛔ 为什么这个组件最该被测：它是**用户唯一真正在用的界面**，而且里面堆着
 * 好几处"踩过坑才修好"的契约 —— 占位事件不能进正文、`@@ADVICE@@` 标记要剥离、
 * 错误/排队要打字机呈现而不是白屏。这些一旦被重构改回去，手测极难发现。
 *
 * 做法：api 层整体 mock（不真的发请求）；typewriter 换成**同步**实现，
 * 否则断言要等 35ms/字的真实定时器，既慢又不稳。
 */

const sseHandlers = { current: null }

vi.mock('../api/index.js', () => ({
  createLoveChatSSE: vi.fn((prompt, chatId, handlers) => {
    sseHandlers.current = handlers
    return () => {}                       // 取消函数
  }),
  generateChatId: () => 'chat-fixed',
  voteMessage: vi.fn(async () => ({ data: { code: 200 } })),
  registerConversation: vi.fn(async () => ({ data: { code: 200 } })),
  getConversationMessages: vi.fn(async () => ({ data: { code: 200, data: [] } })),
  sandboxTaView: vi.fn(async () => ({ data: { data: {} } })),
  listSandboxPersonas: vi.fn(async () => ({ data: { data: [] } })),
  listActionItems: vi.fn(async () => ({ data: { data: [] } })),
  createActionItemFromReply: vi.fn(async () => ({ data: { data: { created: false } } })),
  doneActionItem: vi.fn(async () => ({})),
  removeActionItem: vi.fn(async () => ({})),
  reportSentiment: vi.fn(async () => ({})),
  markOnboarded: vi.fn(async () => ({ data: { code: 200 } })),
}))

// typewriter 换同步：立刻写完整文本（等价于"瞬间播完"）
vi.mock('../utils/typewriter.js', () => ({
  createTypewriter: () => ({
    start: (text, onTick) => onTick(text),
    stop: () => {},
  }),
  default: () => ({ start: (t, f) => f(t), stop: () => {} }),
}))

const routerPush = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush }),
  useRoute: () => ({ query: {} }),
}))

vi.mock('../utils/history.js', () => ({ saveLocalConversation: vi.fn() }))

import LoveChat from '../views/LoveChat.vue'
import { markOnboarded } from '../api/index.js'

function mountChat() {
  return mount(LoveChat, { global: { stubs: { RouterLink: true } } })
}

async function send(wrapper, text = '你好') {
  await wrapper.find('.chat-input').setValue(text)
  await wrapper.find('.send-btn').trigger('click')
  await wrapper.vm.$nextTick()
}

/**
 * ⛔ 取「真正的 AI 回信气泡」，必须**排除 loading 占位**：
 * loading 时页面里有**两个** `.message-ai .message-content` —— 真气泡 + 打字点气泡，
 * 而打字点那个的 text() 恰好是 '...'（三个 `<span class="dot">` 里的点）。
 * 我第一版直接 `.at(-1)` → 抓到占位、断言全错，**看起来像产品 bug 其实是我选择器错**。
 */
const aiContent = w => w.findAll('.message-ai')
  .filter(m => !m.find('.typing-dots').exists())
  .at(-1)
  .find('.message-content')

describe('LoveChat：发送与占位（phase17 契约）', () => {
  beforeEach(() => { sseHandlers.current = null; localStorage.clear() })
  afterEach(() => { vi.clearAllMocks() })

  it('空输入不发送（不发请求、不产生气泡）', async () => {
    const w = mountChat()
    await send(w, '   ')
    expect(w.findAll('.message').length).toBe(0)
    expect(sseHandlers.current).toBe(null)
  })

  it('发送：用户气泡出现、输入框清空、进入 loading', async () => {
    const w = mountChat()
    await send(w, '他总是冷战')

    expect(w.find('.message-user .message-content').text()).toContain('他总是冷战')
    expect(w.find('.chat-input').element.value).toBe('')
    // 发送后必须出现「AI 正在回」的气泡（三个点/占位）
    expect(w.findAll('.message-ai').length).toBeGreaterThan(0)
    expect(sseHandlers.current).not.toBe(null)
  })

  it('⛔ 占位事件显示在气泡里，且**首个正文帧到达即清空**（不是一直挂着）', async () => {
    const w = mountChat()
    await send(w, '在吗')

    sseHandlers.current.onStatus({ stage: 'thinking', text: '让我想想…' })
    await w.vm.$nextTick()
    expect(w.text()).toContain('让我想想…')

    sseHandlers.current.onMessage('第一段正文')
    await w.vm.$nextTick()
    expect(w.text()).not.toContain('让我想想…')
  })

  it('⛔ 占位载荷绝不进正文（否则用户看到一坨 JSON）', async () => {
    const w = mountChat()
    await send(w, '在吗')

    sseHandlers.current.onStatus({ stage: 'thinking', text: '让我想想…' })
    sseHandlers.current.onMessage('正文')
    await w.vm.$nextTick()

    expect(aiContent(w).text()).toContain('正文')
    expect(aiContent(w).text()).not.toContain('"stage"')
  })

  it('⛔ @@ADVICE@@ 标记帧被剥离，且**不清空占位**（标记帧不代表正文开始）', async () => {
    const w = mountChat()
    await send(w, '怎么回复')

    sseHandlers.current.onStatus({ text: '让我想想…' })
    await w.vm.$nextTick()

    sseHandlers.current.onMessage('@@ADVICE@@{"tiers":[]}')
    await w.vm.$nextTick()

    expect(aiContent(w).text()).not.toContain('@@ADVICE@@')
    expect(aiContent(w).text()).not.toContain('tiers')
    expect(w.text()).toContain('让我想想…')
  })

  it('错误：把错误文案**打字机呈现**在气泡里并退出 loading（不是白屏）', async () => {
    const w = mountChat()
    await send(w, '在吗')

    sseHandlers.current.onError(new Error('连接失败，请稍后重试。'))
    await w.vm.$nextTick()

    expect(aiContent(w).text()).toContain('连接失败')
    // loading 结束后不再显示打字点
    expect(w.find('.typing-dots').exists()).toBe(false)
  })

  it('"Failed to fetch" 这种无信息错误 → 换成可读文案', async () => {
    const w = mountChat()
    await send(w, '在吗')

    sseHandlers.current.onError(new Error('Failed to fetch'))
    await w.vm.$nextTick()

    expect(aiContent(w).text()).toContain('连接失败，请稍后重试')
  })

  it('4003 排队：显示后端文案 + **重试秒数提示**', async () => {
    const w = mountChat()
    await send(w, '在吗')

    sseHandlers.current.onBusy({ message: '当前咨询较多', data: { retryAfterSec: 20 } })
    await w.vm.$nextTick()

    expect(aiContent(w).text()).toContain('当前咨询较多')
    expect(aiContent(w).text()).toContain('20')
  })

  it('流结束但正文为空 → 补一个 "..."（不留空气泡）', async () => {
    const w = mountChat()
    await send(w, '在吗')

    sseHandlers.current.onComplete()
    await w.vm.$nextTick()

    expect(aiContent(w).text()).toContain('...')
  })

  it('流结束有正文 → 不覆盖成 "..."', async () => {
    const w = mountChat()
    await send(w, '在吗')
    sseHandlers.current.onMessage('完整回信')
    sseHandlers.current.onComplete()
    await w.vm.$nextTick()

    expect(aiContent(w).text()).toContain('完整回信')
    expect(aiContent(w).text()).not.toBe('...')
  })
})

describe('LoveChat：XSS 与渲染', () => {
  beforeEach(() => { sseHandlers.current = null; localStorage.clear() })

  it('⛔ 模型返回的 HTML 被转义（不能当 HTML 执行）', async () => {
    const w = mountChat()
    await send(w, '在吗')
    sseHandlers.current.onMessage('<img src=x onerror=alert(1)>正常文本')
    await w.vm.$nextTick()

    // ⛔ 安全属性 = **没有生成可执行元素**，而不是"HTML 里不出现这些字符"：
    //    转义后 onerror=alert 仍会作为**文本内容**出现在 HTML 源码里（那是正确的），
    //    我第一版按"字符串不出现"断言，等于用一个错误的判据去验一个正确的实现。
    expect(aiContent(w).find('img').exists()).toBe(false)
    expect(aiContent(w).html()).toContain('&lt;img')                       // 尖括号被转义
    expect(aiContent(w).text()).toContain('<img src=x onerror=alert(1)>')  // 原样显示为文本
  })

  it('Markdown 的加粗/标题被渲染为真标签', async () => {
    const w = mountChat()
    await send(w, '在吗')
    sseHandlers.current.onMessage('## 小标题\n**重点**')
    await w.vm.$nextTick()

    const html = aiContent(w).html()
    expect(html).toContain('<h2>')
    expect(html).toContain('<strong>重点</strong>')
  })
})

describe('LoveChat：markdown 渲染的 XSS 防线（ADR-79，P0）', () => {
  beforeEach(() => { sseHandlers.current = null; localStorage.clear() })

  /** 把一段模型/用户文本喂进 AI 气泡（走的就是 v-html 渲染路径） */
  async function render(text) {
    const w = mountChat()
    await send(w, '在吗')
    sseHandlers.current.onMessage(text)
    await w.vm.$nextTick()
    return aiContent(w)
  }

  it('⛔ 图片 URL 里的引号不能逃出属性（真实可用的偷 token 载荷）', async () => {
    // 实测过的载荷（ADR-79）：修复前它渲染成
    // <img src="y" onerror="window.__pwned=localStorage['lwx_ai_token']" ...> 并**真的执行**
    const el = await render('![x](y" onerror="window.__pwned=localStorage[\'lwx_ai_token\'])')
    expect(el.find('img[onerror]').exists()).toBe(false)
    expect(el.html()).not.toContain('onerror')
  })

  it('⛔ 链接 URL 里的引号同样不能逃出属性', async () => {
    const el = await render('[点我](x" onmouseover="window.__pwned=1")')
    expect(el.find('[onmouseover]').exists()).toBe(false)
    expect(el.html()).not.toContain('onmouseover')
  })

  it('⛔ javascript: / data: 这类可执行 scheme 不给可点链接', async () => {
    for (const payload of ['[点我](javascript:window.__pwned=1)', '![x](data:text/html,<script>x</script>)']) {
      const el = await render(payload)
      const a = el.find('a')
      if (a.exists()) expect(a.attributes('href') || '').not.toMatch(/^(javascript|data):/i)
      expect(el.find('img').exists()).toBe(false)
    }
  })

  it('⛔ 协议相对 URL（//host）不给可点链接、不给 img', async () => {
    // change security-hardening：startsWith('/') 会把 //evil.example 当站内路径放行
    for (const payload of ['[点我](//evil.example/p)', '![x](//evil.example/a.png)']) {
      const el = await render(payload)
      const a = el.find('a')
      if (a.exists()) expect(a.attributes('href') || '').not.toMatch(/^\/\//)
      expect(el.find('img').exists()).toBe(false)
    }
  })

  it('站内路径（单斜杠）与 https 链接必须仍然可用（收口不许误杀）', async () => {
    const same = await render('[回信](/api/memory/1)')
    expect(same.find('a').exists()).toBe(true)
    expect(same.find('a').attributes('href')).toBe('/api/memory/1')

    const ext = await render('[文档](https://example.com/a)')
    expect(ext.find('a').attributes('href')).toBe('https://example.com/a')
  })

  it('裸 HTML 标签仍被转义（原有防线不许退化）', async () => {
    const el = await render('<img src=x onerror=window.__pwned=1>')
    expect(el.find('img').exists()).toBe(false)
    expect(el.text()).toContain('<img src=x')
  })

  it('正常图片/链接仍要能渲染（别把功能一起修没了）', async () => {
    const el = await render('![风景](https://example.com/a.png) 和 [文档](https://example.com/doc)')
    expect(el.find('img').attributes('src')).toBe('https://example.com/a.png')
    expect(el.find('a').attributes('href')).toBe('https://example.com/doc')
  })
})

/**
 * AI 生成标识 + 未成年人模式提示（2026-10-13 合规改造）。
 *
 * ⛔ 为什么这两条要永久守：
 *   · 标识是《人工智能生成合成内容标识办法》第四条(一)要求的**显式标识** ——
 *     它一旦被重构删掉，页面看起来完全正常，只有监管或用户才会发现"分不清是不是真人"；
 *   · 未成年人提示条读的是**资料缓存**（不额外发请求），很容易在改动取数点时被顺手删掉。
 */
describe('LoveChat：AI 生成标识与未成年人模式', () => {
  beforeEach(() => { sseHandlers.current = null; localStorage.clear() })
  afterEach(() => { vi.clearAllMocks() })

  it('每条 AI 回复旁都有「AI 生成」标识（常驻显式标识）', async () => {
    const w = mountChat()
    await send(w, '在吗')
    sseHandlers.current.onMessage('正文')
    await w.vm.$nextTick()

    const ai = w.findAll('.message-ai').filter((m) => !m.find('.typing-dots').exists()).at(-1)
    const tag = ai.find('.ai-tag')
    expect(tag.exists()).toBe(true)
    expect(tag.text()).toBe('AI 生成')
  })

  it('用户自己发的气泡不带 AI 标识（标识只能挂在 AI 内容上）', async () => {
    const w = mountChat()
    await send(w, '在吗')
    expect(w.find('.message-user .ai-tag').exists()).toBe(false)
  })

  it('非未成年人：不显示未成年人模式提示条', async () => {
    localStorage.setItem('lwx_ai_user', JSON.stringify({ username: 'u1', role: 'USER' }))
    const w = mountChat()
    expect(w.find('.minor-banner').exists()).toBe(false)
  })

  it('未成年人模式：提示条写明每日条数与休息时段', async () => {
    localStorage.setItem('lwx_ai_user', JSON.stringify({ username: 'kid', role: 'USER', minorMode: true }))
    const w = mountChat()

    const banner = w.find('.minor-banner')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('未成年人模式已开启')
    expect(banner.text()).toContain('30')
    expect(banner.text()).toContain('22:00')
    expect(banner.text()).toContain('6:00')
  })

  it('资料晚一步刷到（lh:user-updated）时提示条要跟着出现', async () => {
    localStorage.setItem('lwx_ai_user', JSON.stringify({ username: 'kid', role: 'USER' }))
    const w = mountChat()
    expect(w.find('.minor-banner').exists()).toBe(false)

    // 模拟 App.vue 的 refreshProfile() 写回缓存后广播事件
    localStorage.setItem('lwx_ai_user', JSON.stringify({ username: 'kid', role: 'USER', minorMode: true }))
    window.dispatchEvent(new CustomEvent('lh:user-updated'))
    await w.vm.$nextTick()
    expect(w.find('.minor-banner').exists()).toBe(true)
  })

  it('⛔ 产品级免责声明常驻可见（docs/07 §5.1）：不构成专业心理咨询或医疗诊断', async () => {
    const w = mountChat()
    const d = w.find('.ai-disclaimer')
    expect(d.exists()).toBe(true)
    expect(d.text()).toContain('仅供参考')
    expect(d.text()).toContain('不构成专业心理咨询或医疗诊断')
    // 发消息前后都在（常驻，不是一次性提示）
    await send(w, '在吗')
    expect(w.find('.ai-disclaimer').text()).toContain('不构成专业心理咨询或医疗诊断')
  })
})

describe('LoveChat：首次体验引导（ADR-91）', () => {
  beforeEach(() => {
    sseHandlers.current = null
    localStorage.clear()
    routerPush.mockClear()
  })
  afterEach(() => { vi.clearAllMocks() })

  /** 已看过 ⇒ 不再打扰（服务端记的，换设备也不会重弹）。 */
  it('已看过（onboarded=true）→ 不显示引导卡', () => {
    localStorage.setItem('lwx_ai_user', JSON.stringify({ username: 'u1', onboarded: true }))
    const w = mountChat()
    expect(w.find('.guide-card').exists()).toBe(false)
  })

  /** 未看过 ⇒ 显示，且四个入口都在（少一个就是又有一项能力沉没）。 */
  it('未看过 → 显示引导卡，四个入口齐全', () => {
    const w = mountChat()
    expect(w.find('.guide-card').exists()).toBe(true)
    const names = w.findAll('.guide-row-name').map(n => n.text())
    expect(names).toEqual(['直接说心事', '想要具体话术', '想先练一练', '想回头看'])
  })

  /** 收起：卡片消失 + 落库（失败也不打扰用户）。 */
  it('点「知道了」→ 收起并标记已看过', async () => {
    const w = mountChat()
    await w.find('.guide-close').trigger('click')
    expect(w.find('.guide-card').exists()).toBe(false)
    expect(markOnboarded).toHaveBeenCalledTimes(1)
  })

  /** 头部按钮可重开 —— 引导不是"一次性弹窗"，是随时查得到的说明书。 */
  it('头部「能做什么」可以重开引导', async () => {
    const w = mountChat()
    await w.find('.guide-close').trigger('click')
    expect(w.find('.guide-card').exists()).toBe(false)
    await w.find('.guide-open-btn').trigger('click')
    expect(w.find('.guide-card').exists()).toBe(true)
  })

  /** 「想要具体话术」必须**真的预填一句能触发三牌的话**（只写说明等于没引导）。 */
  it('「想要具体话术」→ 预填能触发话术三牌的样例', async () => {
    const w = mountChat()
    await w.findAll('.guide-row').at(1).trigger('click')
    expect(w.find('.chat-input').element.value).toContain('怎么回复')
    expect(w.find('.guide-card').exists()).toBe(false)
  })

  /** 两个跳转入口要真的跳（各自的目标页面）。 */
  it('「想先练一练」→ /sandbox；「想回头看」→ /history', async () => {
    const w1 = mountChat()
    await w1.findAll('.guide-row').at(2).trigger('click')
    expect(routerPush).toHaveBeenCalledWith('/sandbox')

    routerPush.mockClear()
    const w2 = mountChat()
    await w2.findAll('.guide-row').at(3).trigger('click')
    expect(routerPush).toHaveBeenCalledWith('/history')
  })
})
