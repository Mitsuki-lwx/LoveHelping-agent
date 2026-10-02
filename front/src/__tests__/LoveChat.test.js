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
}))

// typewriter 换同步：立刻写完整文本（等价于"瞬间播完"）
vi.mock('../utils/typewriter.js', () => ({
  createTypewriter: () => ({
    start: (text, onTick) => onTick(text),
    stop: () => {},
  }),
  default: () => ({ start: (t, f) => f(t), stop: () => {} }),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {} }),
}))

vi.mock('../utils/history.js', () => ({ saveLocalConversation: vi.fn() }))

import LoveChat from '../views/LoveChat.vue'

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
