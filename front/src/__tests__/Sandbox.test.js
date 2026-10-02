import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 角色模拟屋（Sandbox.vue，431 行）的**交互**契约。
 *
 * ⛔ 为什么值得测：这个视图 0% 行覆盖却被行覆盖统计报成"有人管"——它整套交互
 * （建会话 / SSE 发消息 / 记忆抽屉 / 复盘）全靠手测，而里面有好几处"只有踩过才知道"的约定：
 *   · `currentSession` 是 computed 查表，命中不了时**不能**退化成"随便挑一个会话"；
 *   · 发送后 loading 期间 UI 必须**锁死**输入与按钮，否则同一条消息会进两次；
 *   · SSE 错误/忙必须收敛 loading 并给可读文案，绝不能白屏；
 *   · 记忆抽屉是**会话级**的，切会话必须关掉（否则看着 B 却删 A 的记忆）。
 *
 * 做法：api 层整体 `vi.mock`（绝不发真请求），SSE 只捕获 handlers 由测试手动触发帧。
 */

const h = vi.hoisted(() => ({
  state: {
    personas: [],
    sessions: [],        // 模拟后端列表；sandboxCreate 会往里 push（=后端已落库）
    memories: [],
    review: { code: 200, data: {} },
    ghostCreateId: null, // 非空时 create 返回一个**不在列表里**的 id（模拟列表未同步）
    seq: 0,
    memSeq: 0
  },
  sse: { handlers: null, calls: [] }
}))

vi.mock('../api/index.js', () => ({
  listSandboxPersonas: vi.fn(async () => ({ data: { data: h.state.personas } })),
  listSandboxSessions: vi.fn(async () => ({ data: { data: h.state.sessions.map(s => ({ ...s })) } })),
  sandboxCreate: vi.fn(async (body) => {
    if (h.state.ghostCreateId) return { data: { code: 200, data: { sandboxId: h.state.ghostCreateId } } }
    const id = 'sb-' + (++h.state.seq)
    const p = h.state.personas.find(x => x.id === body.personaId)
    h.state.sessions.push({
      id,
      personaName: p ? p.name : String(body.customTraits || 'TA').slice(0, 4),
      createdAt: '2026-10-01T10:00:00'
    })
    return { data: { code: 200, data: { sandboxId: id } } }
  }),
  createSandboxChatSSE: vi.fn((sandboxId, message, handlers) => {
    h.sse.handlers = handlers
    h.sse.calls.push({ sandboxId, message })
    return () => {}
  }),
  listSandboxMemories: vi.fn(async () => ({ data: { data: h.state.memories.map(m => ({ ...m })) } })),
  addSandboxMemory: vi.fn(async (sandboxId, factText, type) => {
    h.state.memories.push({ id: 'mem-' + (++h.state.memSeq), factText, type })
    return { data: { code: 200 } }
  }),
  deleteSandboxMemory: vi.fn(async (sandboxId, memoryId) => {
    h.state.memories = h.state.memories.filter(m => m.id !== memoryId)
    return { data: { code: 200 } }
  }),
  sandboxReset: vi.fn(async () => ({ data: { code: 200 } })),
  sandboxDelete: vi.fn(async () => ({ data: { code: 200 } })),
  sandboxReview: vi.fn(async () => ({ data: h.state.review }))
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {} })
}))

import Sandbox from '../views/Sandbox.vue'
import { sandboxCreate, sandboxReview, sandboxReset, sandboxDelete, addSandboxMemory, deleteSandboxMemory, listSandboxMemories } from '../api/index.js'

async function mountSandbox({ personas = [], sessions = [], memories = [] } = {}) {
  h.state.personas = personas
  h.state.sessions = sessions
  h.state.memories = memories
  h.state.ghostCreateId = null
  h.state.review = { code: 200, data: {} }
  h.sse.handlers = null
  h.sse.calls = []
  const w = mount(Sandbox, { global: { stubs: { RouterLink: true } } })
  await flushPromises()
  return w
}

/** 进入第 i 个会话（点侧栏）——组件靠 currentId 查 currentSession，这是唯一入口。 */
async function openSessionAt(w, i = 0) {
  await w.findAll('.sb-session')[i].trigger('click')
  await flushPromises()
}

/**
 * ⛔ 取「真正的 AI 气泡」必须**排除 loading 占位**：loading 时页面有两个 `.sb-msg-ai`，
 * 占位那个的 text() 恰好是 `'...'`（三个 `<span class="dot">`），直接 `.at(-1)` 会抓到占位，
 * 看起来像"模型回了省略号"的产品 bug，其实是选择器错。
 */
const lastAi = w => w.findAll('.sb-msg-ai')
  .filter(m => !m.find('.typing-dots').exists())
  .at(-1)?.find('.sb-msg-content')

const toolBtn = (w, label) => w.findAll('.sb-chat-tools .sb-tool').find(b => b.text().includes(label))

async function send(w, text) {
  await w.find('.sb-chat-input').setValue(text)
  await w.find('.send-btn').trigger('click')
  await flushPromises()
}

describe('Sandbox：空态与创建会话', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('无会话时给侧栏引导 + 欢迎人设墙；点「新模拟」切到自定义面板（两面互斥）', async () => {
    const w = await mountSandbox({ personas: [{ id: 'p1', name: '林知夏' }] })

    expect(w.findAll('.sb-session')).toHaveLength(0)
    expect(w.find('.sb-side-empty').text()).toContain('还没有模拟记录')
    expect(w.find('.sb-welcome').exists()).toBe(true)

    await w.find('.new-chat-btn').trigger('click')
    // 面板与欢迎墙是 v-if/v-else-if：同时出现会让用户不知道该点哪
    expect(w.find('.sb-custom').exists()).toBe(true)
    expect(w.find('.sb-welcome').exists()).toBe(false)
  })

  it('坏 traitsJson 不炸渲染（personaLine 解析失败要静默兜底，不能整个页面白掉）', async () => {
    const w = await mountSandbox({
      personas: [
        { id: 'p1', name: '林知夏', archetype: '慢热', traitsJson: '{"catchphrase":"你猜呀"}' },
        { id: 'p2', name: '陈屿', archetype: '嘴硬', traitsJson: '{坏JSON' }
      ]
    })

    const cards = w.findAll('.persona-card')
    expect(cards[0].text()).toContain('你猜呀')       // 正常解析出金句
    expect(cards[1].text()).toContain('陈屿')          // 坏数据那张仍在（没被异常打断）
    expect(cards[1].find('.persona-line').exists()).toBe(false)
  })

  it('点预置人设 → 只带 personaId 创建 → 立刻进入新会话（头部显示该人设名）', async () => {
    const w = await mountSandbox({ personas: [{ id: 'p1', name: '林知夏', traitsJson: '{}' }] })

    await w.findAll('.persona-card')[0].trigger('click')
    await flushPromises()

    // 契约：请求体不能夹带 customTraits/relationshipStage 之类的空字段
    expect(sandboxCreate).toHaveBeenCalledWith({ personaId: 'p1' })
    expect(w.find('.sb-chat').exists()).toBe(true)
    expect(w.find('.sb-chat-name').text()).toBe('林知夏')
  })

  it('自定义创建：特征为空时「开演」锁死（点了也不发请求）；填写后带关系阶段提交并进会话', async () => {
    const w = await mountSandbox()
    await w.find('.new-chat-btn').trigger('click')

    const go = w.find('.sb-custom .primary')
    expect(go.element.disabled).toBe(true)

    // 这是"空特征不许开演"的**唯一**防线（startCustom 自身没有 trim 校验），
    // 谁把 :disabled 删掉，这里就会因为真的发请求而变红。
    await go.trigger('click')
    await flushPromises()
    expect(sandboxCreate).not.toHaveBeenCalled()

    await w.find('.sb-textarea').setValue('慢热但嘴硬，生气时爱用反问句')
    await w.find('.sb-custom input.sb-input').setValue('冷战了一周')
    expect(go.element.disabled).toBe(false)

    await go.trigger('click')
    await flushPromises()

    expect(sandboxCreate).toHaveBeenCalledWith({
      customTraits: '慢热但嘴硬，生气时爱用反问句',
      relationshipStage: '冷战了一周'
    })
    expect(w.find('.sb-custom').exists()).toBe(false)   // 面板收起（否则用户以为没提交成功又点一次）
    expect(w.find('.sb-chat').exists()).toBe(true)
    // 新会话已进侧栏且**就是当前会话**（而不是停在欢迎墙/旧会话上）
    expect(w.findAll('.sb-session')).toHaveLength(1)
    expect(w.findAll('.sb-session')[0].classes()).toContain('sb-session-active')
  })
})

describe('Sandbox：currentSession 解析与侧栏切换', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('侧栏高亮与聊天头都跟随 currentId（查表命中）', async () => {
    const w = await mountSandbox({
      sessions: [
        { id: 's1', personaName: '甲', createdAt: '2026-10-01T10:00:00' },
        { id: 's2', personaName: '乙', createdAt: '2026-10-02T11:00:00' }
      ]
    })

    await openSessionAt(w, 0)
    expect(w.find('.sb-chat-name').text()).toBe('甲')
    expect(w.findAll('.sb-session')[0].classes()).toContain('sb-session-active')

    // 抽屉是会话级的：切走必须关掉，否则会看着乙去删甲的记忆
    await toolBtn(w, '记忆').trigger('click')
    await flushPromises()
    expect(w.find('.sb-mem-drawer').exists()).toBe(true)

    await openSessionAt(w, 1)
    expect(w.find('.sb-chat-name').text()).toBe('乙')
    expect(w.findAll('.sb-session')[1].classes()).toContain('sb-session-active')
    expect(w.findAll('.sb-session')[0].classes()).not.toContain('sb-session-active')
    expect(w.find('.sb-mem-drawer').exists()).toBe(false)
  })

  it('currentId 落空（create 返回的 id 不在列表里）→ currentSession 为 null，不拿别的会话顶替', async () => {
    const w = await mountSandbox({
      sessions: [{ id: 's1', personaName: '旧会话', createdAt: '2026-10-01T10:00:00' }]
    })
    await openSessionAt(w, 0)
    await send(w, '旧会话里的一句话')
    expect(w.findAll('.sb-msg-user')).toHaveLength(1)

    // 后端给了 id，但 list 还没同步（loadSessions 静默失败/延迟）
    h.state.ghostCreateId = 'ghost-1'
    await w.find('.new-chat-btn').trigger('click')
    await w.find('.sb-textarea').setValue('嘴硬')
    await w.find('.sb-custom .primary').trigger('click')
    await flushPromises()

    // 不能把"旧会话"的会话对象/消息当成新会话渲染出来（张冠李戴比空白更危险）
    expect(w.find('.sb-chat').exists()).toBe(false)
    expect(w.find('.sb-chat-name').exists()).toBe(false)
    expect(w.findAll('.sb-msg')).toHaveLength(0)
    expect(w.find('.sb-session-active').exists()).toBe(false)
  })
})

describe('Sandbox：SSE 发消息（帧 → 气泡 / 错误收敛）', () => {
  beforeEach(() => { vi.clearAllMocks() })

  async function mountInChat() {
    const w = await mountSandbox({ sessions: [{ id: 's1', personaName: '甲', createdAt: '2026-10-01T10:00:00' }] })
    await openSessionAt(w, 0)
    return w
  }

  it('发送：用户气泡立刻出现、输入清空、loading 占位挂上；正文帧进 AI 气泡，onComplete 收敛', async () => {
    const w = await mountInChat()
    await send(w, '在吗')

    expect(w.find('.sb-msg-user .sb-msg-content').text()).toBe('在吗')
    expect(w.find('.sb-chat-input').element.value).toBe('')
    expect(w.find('.typing-dots').exists()).toBe(true)
    expect(h.sse.calls).toEqual([{ sandboxId: 's1', message: '在吗' }])

    h.sse.handlers.onMessage('嗯，你说')
    await w.vm.$nextTick()
    expect(lastAi(w).text()).toBe('嗯，你说')
    // loading 未收敛前占位仍在，且不能被误当成 AI 正文
    expect(w.find('.typing-dots').exists()).toBe(true)

    h.sse.handlers.onComplete()
    await w.vm.$nextTick()
    expect(w.find('.typing-dots').exists()).toBe(false)
    expect(lastAi(w).text()).toBe('嗯，你说')
  })

  it('onError：给可读错误文案 + 停掉 loading（不白屏、已发的内容不消失）', async () => {
    const w = await mountInChat()
    await send(w, '在吗')

    h.sse.handlers.onError(new Error('boom'))
    await w.vm.$nextTick()

    expect(lastAi(w).text()).toContain('信号断了')
    expect(w.find('.typing-dots').exists()).toBe(false)
    expect(w.findAll('.sb-msg-user')).toHaveLength(1)
    expect(w.findAll('.sb-msg')).toHaveLength(2)
  })

  it('onBusy 且没有 body → 走兜底文案并收敛 loading', async () => {
    const w = await mountInChat()
    await send(w, '在吗')

    h.sse.handlers.onBusy(null)
    await w.vm.$nextTick()

    expect(lastAi(w).text()).toContain('这场暂时进不去')
    expect(w.find('.typing-dots').exists()).toBe(false)
  })

  it('发送中按钮与输入被锁死：连点「说」不会重复发送（一条用户气泡 / 一路 SSE）', async () => {
    const w = await mountInChat()
    await send(w, '在吗')

    expect(w.find('.send-btn').element.disabled).toBe(true)
    expect(w.find('.sb-chat-input').element.disabled).toBe(true)

    await w.find('.send-btn').trigger('click')
    await w.find('.send-btn').trigger('click')
    await flushPromises()

    expect(w.findAll('.sb-msg-user')).toHaveLength(1)
    expect(h.sse.calls).toHaveLength(1)
  })

  it('空输入不发：不建流、不产生气泡', async () => {
    const w = await mountInChat()
    await send(w, '   ')

    expect(h.sse.handlers).toBe(null)
    expect(w.findAll('.sb-msg')).toHaveLength(0)
  })
})

describe('Sandbox：记忆抽屉', () => {
  beforeEach(() => { vi.clearAllMocks() })

  async function openDrawer(w) {
    await toolBtn(w, '记忆').trigger('click')
    await flushPromises()
  }

  it('打开抽屉即按当前会话拉取记忆；空列表给引导文案；「✕」能关掉', async () => {
    const w = await mountSandbox({ sessions: [{ id: 's1', personaName: '甲' }] })
    await openSessionAt(w, 0)
    await openDrawer(w)

    expect(w.find('.sb-mem-drawer').exists()).toBe(true)
    expect(listSandboxMemories).toHaveBeenCalledWith('s1')
    expect(w.find('.sb-mem-empty').text()).toContain('还没有记忆')

    await w.find('.sb-mem-head .sb-tool').trigger('click')
    await w.vm.$nextTick()
    expect(w.find('.sb-mem-drawer').exists()).toBe(false)
  })

  it('新增记忆：空白/纯空格被拒（不发请求）；有效内容按所选类型落库并刷新列表、清空输入', async () => {
    const w = await mountSandbox({
      sessions: [{ id: 's1', personaName: '甲' }],
      memories: [{ id: 'm1', factText: '他怕黑', type: 'FACT' }]
    })
    await openSessionAt(w, 0)
    await openDrawer(w)

    await w.find('.sb-mem-add .sb-input').setValue('   ')
    await w.find('.sb-mem-go').trigger('click')
    await flushPromises()
    expect(addSandboxMemory).not.toHaveBeenCalled()
    expect(w.findAll('.sb-mem-item')).toHaveLength(1)

    await w.find('.sb-mem-add .sb-input').setValue('讨厌被叫小名')
    await w.find('.sb-mem-type-select').setValue('EVENT')
    await w.find('.sb-mem-go').trigger('click')
    await flushPromises()

    expect(addSandboxMemory).toHaveBeenCalledWith('s1', '讨厌被叫小名', 'EVENT')
    expect(w.findAll('.sb-mem-item')).toHaveLength(2)
    const items = w.findAll('.sb-mem-item')
    expect(items[1].text()).toContain('经历')            // EVENT → 中文类型名
    expect(items[1].text()).toContain('讨厌被叫小名')
    expect(w.find('.sb-mem-add .sb-input').element.value).toBe('')
    expect(w.find('.sb-mem-empty').exists()).toBe(false)
  })

  it('删除记忆：按 id 调后端并把该条从列表移除（其余保留）', async () => {
    const w = await mountSandbox({
      sessions: [{ id: 's1', personaName: '甲' }],
      memories: [
        { id: 'm1', factText: '怕黑', type: 'FACT' },
        { id: 'm2', factText: '想养猫', type: 'RELATION' }
      ]
    })
    await openSessionAt(w, 0)
    await openDrawer(w)

    await w.findAll('.sb-mem-del')[0].trigger('click')
    await flushPromises()

    expect(deleteSandboxMemory).toHaveBeenCalledWith('s1', 'm1')
    const items = w.findAll('.sb-mem-item')
    expect(items).toHaveLength(1)
    expect(items[0].text()).toContain('想养猫')
    expect(w.text()).not.toContain('怕黑')
  })
})

describe('Sandbox：会话管理（破坏性操作的安全闸）', () => {
  beforeEach(() => { vi.clearAllMocks() })
  afterEach(() => { vi.unstubAllGlobals() })

  it('删除会话：confirm 取消 → 一条都不删；确认 → 从侧栏移除并退回欢迎墙（currentId 归零）', async () => {
    const w = await mountSandbox({ personas: [{ id: 'p1', name: '甲' }], sessions: [{ id: 's1', personaName: '甲' }] })
    await openSessionAt(w, 0)

    vi.stubGlobal('confirm', vi.fn(() => false))
    await toolBtn(w, '删除').trigger('click')
    await flushPromises()
    // 弹窗点"取消"却把会话删了 = 用户数据丢失，这条必须红
    expect(sandboxDelete).not.toHaveBeenCalled()
    expect(w.findAll('.sb-session')).toHaveLength(1)
    expect(w.find('.sb-chat').exists()).toBe(true)

    vi.stubGlobal('confirm', vi.fn(() => true))
    await toolBtn(w, '删除').trigger('click')
    await flushPromises()
    expect(sandboxDelete).toHaveBeenCalledWith('s1')
    expect(w.findAll('.sb-session')).toHaveLength(0)
    // currentId 归零后必须回到欢迎墙，不能停在一场已删掉的对话上
    expect(w.find('.sb-chat').exists()).toBe(false)
    expect(w.find('.sb-welcome').exists()).toBe(true)
  })

  it('重来：确认后清空本场消息（旧对话不能残留在新一场里）', async () => {
    const w = await mountSandbox({ sessions: [{ id: 's1', personaName: '甲' }] })
    await openSessionAt(w, 0)
    await send(w, '在吗')
    h.sse.handlers.onMessage('嗯')
    h.sse.handlers.onComplete()
    await w.vm.$nextTick()
    expect(w.findAll('.sb-msg')).toHaveLength(2)

    vi.stubGlobal('confirm', vi.fn(() => true))
    await toolBtn(w, '重来').trigger('click')
    await flushPromises()

    expect(sandboxReset).toHaveBeenCalledWith('s1')
    expect(w.findAll('.sb-msg')).toHaveLength(0)
    expect(w.find('.sb-chat').exists()).toBe(true)   // 清空对话≠删会话，人设还在
  })
})

describe('Sandbox：复盘', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('业务失败（HTTP 200 但 code!==200）→ 展示后端 message，且按钮复位可再试', async () => {
    const w = await mountSandbox({ sessions: [{ id: 's1', personaName: '甲' }] })
    await openSessionAt(w, 0)
    h.state.review = { code: 500, message: '演练对话太短' }

    await toolBtn(w, '复盘').trigger('click')
    await flushPromises()

    expect(sandboxReview).toHaveBeenCalledWith('s1')
    expect(w.find('.sb-review-summary').text()).toContain('演练对话太短')
    // reviewing 必须回到 false：否则按钮永远停在"复盘中…"，用户再也点不动
    expect(toolBtn(w, '复盘').text()).toContain('📋 复盘')
    expect(toolBtn(w, '复盘').element.disabled).toBe(false)
  })
})