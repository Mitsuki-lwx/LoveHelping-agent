import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 旧信存档页（History.vue，330 行）此前**交互逻辑 0% 覆盖**，只有模板被顺手带到。
 *
 * 这里锁的是那些"重构一次就会悄悄坏、手测极难发现"的契约：
 *   · 切标签必须把**对应的 chatType** 传给后端（复制粘贴忘改参数 → 两个标签显示同一批数据）；
 *   · 拉列表失败 / 空列表都不能白屏，且 loading 必须收敛（否则永远停在"加载中..."）；
 *   · 删除是**两步**：点 🗑️ 只弹确认框，点了"确认删除"才真的调接口，且不能让点击冒泡到卡片
 *     （冒泡 = 用户想删，结果被带去聊天页）；
 *   · 删除失败**绝不能**把本地列表里那一条误删（否则刷新一下它又"复活"，用户以为删掉了）。
 *
 * ⛔ 本机文件系统大小写不敏感，`history.test.js`（utils/history.js 的用例）已改名为
 *    `localHistory.test.js`，否则本文件会把它整份覆盖掉（内容一字未改，仅改名）。
 *
 * 做法：api 层整体 mock（绝不发真请求）；vue-router 也 mock —— 但要断言 push 的参数，
 * 所以用 vi.hoisted 把 push 提出来共享（vi.mock 工厂会被提升，引用普通顶层 const 会踩 TDZ）。
 */

const mocks = vi.hoisted(() => ({
  listConversations: vi.fn(),
  clearConversation: vi.fn(),
  sentimentTimeline: vi.fn(),
  routerPush: vi.fn(), // 脚本里的 useRouter().push
  appRouterPush: vi.fn(), // 模板里的 $router.push（返回按钮）
}))

vi.mock('../api/index.js', () => ({
  listConversations: mocks.listConversations,
  clearConversation: mocks.clearConversation,
  sentimentTimeline: mocks.sentimentTimeline,
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.routerPush }),
  useRoute: () => ({ query: {} }),
}))

import History from '../views/History.vue'

/** 后端统一信封：{ data: { data } }（组件读的就是 res.data.data）。 */
const ok = (data) => ({ data: { code: 200, data } })

/** 用**本地时间**造 ISO 串，这样断言本地格式化结果与运行机器的时区无关。 */
const localIso = (y, m, d, h, mi) => new Date(y, m - 1, d, h, mi).toISOString()

const CONVS = [
  { conversation_id: 'c-1', title: '第一次心动', message_count: 3, created_at: localIso(2026, 9, 8, 9, 5) },
  { conversation_id: 'c-2', title: '后来的我们', message_count: 7, created_at: localIso(2026, 10, 1, 23, 59) },
]

function mountHistory() {
  return mount(History, {
    global: {
      stubs: { RouterLink: true },
      mocks: { $router: { push: mocks.appRouterPush } },
    },
  })
}

/** 挂载 + 等首屏两个请求（列表 / 情绪轨迹）落地。 */
async function mountSettled() {
  const wrapper = mountHistory()
  await flushPromises()
  return wrapper
}

const cardIds = (wrapper) => wrapper.findAll('.conv-card').map((c) => c.find('.conv-title').text())

beforeEach(() => {
  vi.clearAllMocks()
  mocks.listConversations.mockResolvedValue(ok([]))
  mocks.sentimentTimeline.mockResolvedValue(ok([]))
  mocks.clearConversation.mockResolvedValue({ data: { code: 200 } })
  // 组件在 catch 里 console.error；测试里静音，需要断言的用例再单独看它被没被调用。
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('History：列表加载与标签切换', () => {
  it('首屏按默认标签 love 拉列表：先出加载态，落地后才换成卡片', async () => {
    mocks.listConversations.mockResolvedValue(ok(CONVS))
    // 注意：mount 后**不** flush —— 请求还挂着，此时必须看到可读的加载态而不是白屏。
    const wrapper = mountHistory()
    expect(mocks.listConversations).toHaveBeenCalledWith('love')
    expect(wrapper.find('.loading-state').text()).toContain('加载中')
    expect(wrapper.findAll('.conv-card')).toHaveLength(0)

    await flushPromises()
    // loading 必须收敛，否则用户永远卡在"加载中..."（v-if/v-else 链写错就长这样）。
    expect(wrapper.find('.loading-state').exists()).toBe(false)
    expect(mocks.listConversations).toHaveBeenCalledTimes(1) // 首屏只该拉一次
    expect(cardIds(wrapper)).toEqual(['第一次心动', '后来的我们'])
    expect(wrapper.find('.count-badge').text()).toBe('2 条')
  })

  it('切到「恋爱全能帮」用 chatType=manus 重新拉列表，active 样式跟随', async () => {
    const wrapper = await mountSettled()
    await wrapper.findAll('.tab')[1].trigger('click')
    await flushPromises()

    // 参数必须是 manus。若这里仍是 love，则两个标签显示同一批数据 —— 静默的错数据。
    expect(mocks.listConversations).toHaveBeenLastCalledWith('manus')
    expect(wrapper.findAll('.tab')[1].classes()).toContain('tab-active')
    expect(wrapper.findAll('.tab')[0].classes()).not.toContain('tab-active')
  })

  it('切标签时旧列表不残留：新数据未回前先回到加载态', async () => {
    mocks.listConversations.mockResolvedValueOnce(ok(CONVS)) // 首屏 love
    const wrapper = await mountSettled()
    expect(cardIds(wrapper)).toHaveLength(2)

    // 第二次请求挂起不 resolve：此刻不能继续显示上一批数据（否则用户以为切标签没生效）。
    mocks.listConversations.mockImplementationOnce(() => new Promise(() => {}))
    await wrapper.findAll('.tab')[1].trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.loading-state').exists()).toBe(true)
    expect(wrapper.findAll('.conv-card')).toHaveLength(0)
  })

  it('列表请求失败：loading 收敛且给可读提示，不白屏', async () => {
    // ⛔ 这条测试原先钉的是**当时的实现**（失败 ⇒ 显示"暂无…"，与真实空列表一模一样）。
    //    那正是缺陷：后端 500/断网时用户会以为自己的历史被清空了。
    //    实现修好后（独立错误态 + 重试），断言改为"必须与空态可区分"。
    mocks.listConversations.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mount(History, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    expect(wrapper.find('.loading-state').exists()).toBe(false)
    expect(wrapper.find('.load-error').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('暂无')            // 不再伪装成"没有记录"
    expect(wrapper.text()).toContain('失败')                 // 有可读原因
  })

  it('空列表给出与当前标签一致的空态文案（切标签后文案不能串台）', async () => {
    const wrapper = await mountSettled()
    expect(wrapper.find('.empty-state').text()).toContain('恋爱专家')

    await wrapper.findAll('.tab')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('.empty-state').text()).toContain('恋爱全能帮')
    expect(wrapper.find('.empty-state').text()).not.toContain('恋爱专家')
  })
})

describe('History：formatTime 边界（非法时间戳不能显示 Invalid Date）', () => {
  it('正常 ISO 转本地时间并补零；非法串退化为原文；null/空串什么都不显示', async () => {
    mocks.listConversations.mockResolvedValue(
      ok([
        { conversation_id: 'a', title: '正常', message_count: 1, created_at: localIso(2026, 9, 8, 9, 5) },
        { conversation_id: 'b', title: '非法', message_count: 2, created_at: 'not-a-date' },
        { conversation_id: 'c', title: '空值', message_count: 0, created_at: null },
      ])
    )
    const wrapper = await mountSettled()

    const metas = wrapper.findAll('.conv-card .conv-meta').map((m) => m.text())
    // 09:05 若漏了 padStart 会渲染成 "9:5"；用本地时区构造再断言，换机器也成立。
    expect(metas[0]).toContain('2026-09-08 09:05')
    // 后端偶尔回脏数据：不能冒出 "Invalid Date" 这种给用户看的字符串。
    expect(metas[1]).toContain('not-a-date')
    expect(metas[1]).not.toContain('Invalid')
    expect(metas[2]).not.toContain('Invalid')
    expect(metas[2]).not.toMatch(/\d{4}-\d{2}-\d{2}/)
    expect(wrapper.findAll('.conv-card')).toHaveLength(3)
  })

  it('空标题退化为「未命名对话」，不渲染空白卡片', async () => {
    mocks.listConversations.mockResolvedValue(
      ok([{ conversation_id: 'x', title: '', message_count: 0, created_at: null }])
    )
    const wrapper = await mountSettled()
    expect(wrapper.find('.conv-title').text()).toBe('未命名对话')
  })
})

describe('History：删除是两步且不冒泡', () => {
  it('点 🗑️ 只记录待删项：弹确认框，不调接口、不删列表、也不跳去聊天页', async () => {
    mocks.listConversations.mockResolvedValue(ok(CONVS))
    const wrapper = await mountSettled()

    // @click.stop 必须生效：否则点删除 = 触发卡片点击 = 跳进对话，用户直接丢失上下文。
    await wrapper.findAll('.conv-card')[0].find('.del-btn').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.modal-overlay').exists()).toBe(true)
    expect(wrapper.find('.confirm-modal').text()).toContain('确认删除')
    expect(mocks.clearConversation).not.toHaveBeenCalled()
    expect(mocks.routerPush).not.toHaveBeenCalled()
    expect(cardIds(wrapper)).toEqual(['第一次心动', '后来的我们'])
  })

  it('点遮罩或取消：关掉弹层，仍然什么接口都不调', async () => {
    mocks.listConversations.mockResolvedValue(ok(CONVS))
    const wrapper = await mountSettled()

    // 先点弹层**内部**：@click.self 意味着这里不该关闭（否则用户点一下文案框就没了）。
    await wrapper.findAll('.conv-card')[0].find('.del-btn').trigger('click')
    await wrapper.find('.confirm-modal').trigger('click')
    expect(wrapper.find('.modal-overlay').exists()).toBe(true)

    await wrapper.find('.cancel-btn').trigger('click')
    expect(wrapper.find('.modal-overlay').exists()).toBe(false)
    expect(mocks.clearConversation).not.toHaveBeenCalled()

    // 遮罩空白处点击也要能关（移动端误触后的逃生通道）。
    await wrapper.findAll('.conv-card')[1].find('.del-btn').trigger('click')
    await wrapper.find('.modal-overlay').trigger('click')
    expect(wrapper.find('.modal-overlay').exists()).toBe(false)
    expect(mocks.clearConversation).not.toHaveBeenCalled()
  })

  it('确认删除：带 conversation_id 调 clearConversation，并只移除这一条', async () => {
    mocks.listConversations.mockResolvedValue(ok(CONVS))
    const wrapper = await mountSettled()

    await wrapper.findAll('.conv-card')[0].find('.del-btn').trigger('click')
    await wrapper.find('.confirm-modal .delete-btn').trigger('click')
    await flushPromises()

    expect(mocks.clearConversation).toHaveBeenCalledTimes(1)
    expect(mocks.clearConversation).toHaveBeenCalledWith('c-1')
    // 删的必须是卡片对应的那条，剩下那条还在（错位删除 = 删掉别人）。
    expect(cardIds(wrapper)).toEqual(['后来的我们'])
    expect(wrapper.find('.count-badge').text()).toBe('1 条')
    expect(wrapper.find('.modal-overlay').exists()).toBe(false)
  })

  it('删除失败：弹层照常关闭，但列表不能被误清（失败不能当成功）', async () => {
    mocks.listConversations.mockResolvedValue(ok(CONVS))
    mocks.clearConversation.mockRejectedValue(new Error('500'))
    const wrapper = await mountSettled()

    await wrapper.findAll('.conv-card')[0].find('.del-btn').trigger('click')
    await wrapper.find('.confirm-modal .delete-btn').trigger('click')
    await flushPromises()

    expect(mocks.clearConversation).toHaveBeenCalledWith('c-1')
    // 本地先斩后奏地移除 = 用户以为删成功了，刷新后又"复活"。
    expect(cardIds(wrapper)).toEqual(['第一次心动', '后来的我们'])
    expect(wrapper.find('.modal-overlay').exists()).toBe(false)
    // ⛔ 已知缺口（见交付报告）：删除失败没有**任何用户可见**反馈，唯一信号是这个 console.error。
    expect(console.error).toHaveBeenCalled()
  })

})

describe('History：继续对话与返回', () => {
  it('点卡片跳转到统一聊天页，sessionId 做 URL 编码（含空格/斜杠的 id 不能串参）', async () => {
    mocks.listConversations.mockResolvedValue(
      ok([{ conversation_id: 'c 3/4&x', title: '带怪字符的会话', message_count: 1, created_at: null }])
    )
    const wrapper = await mountSettled()

    await wrapper.find('.conv-card').trigger('click')
    // 不编码的话 & 会被后端当成参数分隔符，直接开错会话。
    expect(mocks.routerPush).toHaveBeenCalledWith('/love-chat?sessionId=c%203%2F4%26x')
  })

  it('返回按钮回首页', async () => {
    const wrapper = await mountSettled()
    await wrapper.find('.back-btn').trigger('click')
    expect(mocks.appRouterPush).toHaveBeenCalledWith('/')
  })
})

describe('History：情绪轨迹只有 ≥2 个点才有意义', () => {
  it('1 个点不渲染卡片；≥2 个点渲染且圆点数=数据条数、趋势文案跟着首尾走', async () => {
    mocks.sentimentTimeline.mockResolvedValue(ok([{ score: 1 }]))
    const one = await mountSettled()
    // 单点画不出"轨迹"（首尾相同、趋势无意义），必须整块隐藏而不是画个孤点。
    expect(one.find('.sentiment-card').exists()).toBe(false)

    mocks.sentimentTimeline.mockResolvedValue(ok([{ score: -1 }, { score: 0 }, { score: 2 }]))
    const many = await mountSettled()
    expect(many.find('.sentiment-card').exists()).toBe(true)
    expect(many.findAll('.sentiment-card circle')).toHaveLength(3)
    expect(many.find('.sentiment-caption').text()).toContain('往上走')
  })
})
