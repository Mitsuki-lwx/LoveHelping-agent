import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MemoryArchive from '../views/MemoryArchive.vue'

/**
 * 记忆档案页（MemoryArchive.vue）的交互契约。
 *
 * 这个页面的风险全在"用户改数据"的三条路径上（新增 / 修正 / 删除）：
 * 它们都要**写后端 + 刷新列表**，任何一步错了用户会看到旧数据却以为改成功了。
 * 所以断言重点不是"渲染出来了"，而是：请求参数对不对（trim、只发非空）、
 * 有没有真的刷新、取消/失败时状态有没有收干净。
 *
 * ⛔ 这类测试最容易犯的错是"mock 返回 X 就断言收到 X"——那是同义反复。
 * 所以下面每条都断言**用户可见的结果**（气泡文案、列表条数、按钮 disabled）。
 */

// ⛔ vi.mock 会被提升到文件顶部，回调里不能引用普通顶层变量 —— 这里用 vi.hoisted 保证 api 先于 mock 存在。
const { api, routerPush } = vi.hoisted(() => ({
  api: {
    getMyMemoryFacts: vi.fn(),
    addMemoryFact: vi.fn(),
    updateMemoryFact: vi.fn(),
    deleteMemoryFact: vi.fn(),
  },
  routerPush: vi.fn(),
}))

vi.mock('../api/index.js', () => api)
// 本页只用 useRouter().push；真 router 会带来异步导航噪声，这里隔离掉。
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush }),
  useRoute: () => ({ query: {} }),
}))

/** 后端响应形状：axios 响应体 `.data`，再一层业务 `.data` 才是数组（视图里是 `res.data?.data`）。 */
const okFacts = (items) => ({ data: { data: items } })

const F = (over = {}) => ({
  id: 1,
  content: '默认内容',
  category: 'FACT',
  status: 'ACTIVE',
  confidence: 8,
  hitCount: 3,
  createdAt: '2026-09-01T10:00:00',
  ...over,
})

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const mountView = () => mount(MemoryArchive, { global: { stubs: { RouterLink: true } } })

/** 点击顶部「＋ 添加一条」，展开新增面板。 */
async function openAdd(wrapper) {
  const toggle = wrapper.findAll('button').find((b) => b.text().includes('添加一条'))
  await toggle.trigger('click')
}

describe('MemoryArchive.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routerPush.mockReset()
    api.getMyMemoryFacts.mockResolvedValue(okFacts([]))
    api.addMemoryFact.mockResolvedValue({ data: {} })
    api.updateMemoryFact.mockResolvedValue({ data: {} })
    api.deleteMemoryFact.mockResolvedValue({ data: {} })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals() // happy-dom 不提供 window.confirm，必须自己 stub 并清理
  })

  it('空白内容：不发请求也不给提示，提交按钮保持 disabled（防"空记忆"入库）', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openAdd(wrapper)

    const input = wrapper.find('.add-input')
    await input.setValue('   ')

    // 空格也算空：按钮必须是禁用的（用户根本没有可点的提交入口）。
    const submit = wrapper.find('.add-fact .btn-hand.primary')
    expect(submit.attributes('disabled')).toBeDefined()

    // 但 Enter 键绕过 disabled 直达 submitAdd —— 早返回必须挡住它。
    await input.trigger('keydown.enter')
    await flushPromises()

    expect(api.addMemoryFact).not.toHaveBeenCalled()
    expect(wrapper.find('.add-msg').exists()).toBe(false)
  })

  it('正常新增：用 trim 后的内容 + 所选分类调接口，清空输入并刷新列表', async () => {
    api.getMyMemoryFacts.mockResolvedValueOnce(okFacts([])).mockResolvedValue(okFacts([F({ content: '想被直球安慰' })]))

    const wrapper = mountView()
    await flushPromises()
    await openAdd(wrapper)

    await wrapper.find('.add-input').setValue('   想被直球安慰   ')
    await wrapper.find('.add-cat').setValue('经历')
    await wrapper.find('.add-fact .btn-hand.primary').trigger('click')
    await flushPromises()

    // 前后空格必须在此刻被削掉：否则同一句话会因空格差异存成两条。
    expect(api.addMemoryFact).toHaveBeenCalledWith('想被直球安慰', '经历')
    // 第 1 次是 onMounted，第 2 次是新增成功后——不刷新的话用户看不到刚加的那条。
    expect(api.getMyMemoryFacts).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.add-input').element.value).toBe('')
    expect(wrapper.findAll('.mem-card')).toHaveLength(1)
  })

  it('新增成功文案为绿色提示（addMsgErr=false），且 adding 收敛后按钮恢复可点', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openAdd(wrapper)

    await wrapper.find('.add-input').setValue('我会记得你')
    await wrapper.find('.add-fact .btn-hand.primary').trigger('click')
    await flushPromises()

    const msg = wrapper.find('.add-msg')
    expect(msg.text()).toContain('已添加')
    expect(msg.classes()).not.toContain('err') // 成功绝不能挂错误样式（否则用户以为失败）
    expect(wrapper.find('.add-fact .btn-hand.primary').text()).toBe('添加')
  })

  it('提交进行中：按钮显示"添加中…"且禁用，完成后恢复（防重复提交）', async () => {
    const d = deferred()
    api.addMemoryFact.mockReturnValueOnce(d.promise)

    const wrapper = mountView()
    await flushPromises()
    await openAdd(wrapper)

    await wrapper.find('.add-input').setValue('慢慢说')
    const submit = wrapper.find('.add-fact .btn-hand.primary')
    await submit.trigger('click')
    await wrapper.vm.$nextTick()

    // 请求还没回来：必须锁住按钮，否则连点会写入多条重复记忆。
    expect(submit.text()).toBe('添加中…')
    expect(submit.attributes('disabled')).toBeDefined()

    d.resolve({ data: {} })
    await flushPromises()
    expect(submit.text()).toBe('添加')
  })

  it('新增失败：透传后端 message 并标红，添加态必须收敛（可重试）', async () => {
    api.addMemoryFact.mockRejectedValueOnce({ response: { data: { message: '分类不合法' } } })

    const wrapper = mountView()
    await flushPromises()
    await openAdd(wrapper)

    await wrapper.find('.add-input').setValue('会被拒绝的内容')
    await wrapper.find('.add-fact .btn-hand.primary').trigger('click')
    await flushPromises()

    const msg = wrapper.find('.add-msg')
    expect(msg.text()).toBe('分类不合法')
    expect(msg.classes()).toContain('err')
    // loading 中途不收敛的话按钮永远停"添加中…"，用户再也提交不了。
    expect(wrapper.find('.add-fact .btn-hand.primary').text()).toBe('添加')
    // 失败时输入内容不能丢，否则用户得重打一遍。
    expect(wrapper.find('.add-input').element.value).toBe('会被拒绝的内容')
  })

  it('行内修正：保存调用 updateMemoryFact(trim 后内容) 并刷新，随后退出编辑态', async () => {
    api.getMyMemoryFacts
      .mockResolvedValueOnce(okFacts([F({ id: 7, content: '旧的说法' })]))
      .mockResolvedValue(okFacts([F({ id: 7, content: '新的说法' })]))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.mem-content').trigger('click')
    await wrapper.find('.mem-edit').setValue('  新的说法  ')
    await wrapper.find('.mem-edit-actions .primary').trigger('click')
    await flushPromises()

    expect(api.updateMemoryFact).toHaveBeenCalledWith(7, '新的说法')
    expect(api.getMyMemoryFacts).toHaveBeenCalledTimes(2) // 刷新
    expect(wrapper.find('.mem-edit').exists()).toBe(false) // 保存后编辑器收起
    expect(wrapper.find('.mem-content').text()).toBe('新的说法')
  })

  it('放弃修正：点取消不改后端、不改内容、编辑器收起', async () => {
    api.getMyMemoryFacts.mockResolvedValue(okFacts([F({ id: 7, content: '原来的话' })]))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.mem-content').trigger('click')
    await wrapper.find('.mem-edit').setValue('手滑打的内容')
    const cancel = wrapper.findAll('.mem-edit-actions button').find((b) => b.text() === '取消')
    await cancel.trigger('click')
    await flushPromises()

    expect(api.updateMemoryFact).not.toHaveBeenCalled()
    expect(wrapper.find('.mem-edit').exists()).toBe(false)
    expect(wrapper.find('.mem-content').text()).toBe('原来的话') // 草稿没有泄漏进列表
  })

  it('删除：确认后调 deleteMemoryFact 并刷新列表', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    api.getMyMemoryFacts
      .mockResolvedValueOnce(okFacts([F({ id: 7, content: '要删的' })]))
      .mockResolvedValue(okFacts([]))

    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.findAll('.mem-card')).toHaveLength(1)

    await wrapper.find('.mem-act-del').trigger('click')
    await flushPromises()

    expect(api.deleteMemoryFact).toHaveBeenCalledWith(7)
    expect(api.getMyMemoryFacts).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.mem-card')).toHaveLength(0) // 不刷新的话删掉的卡片还杵在那
  })

  it('删除取消：用户在 confirm 里点"否"时绝不发删除请求（防误删）', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false))
    api.getMyMemoryFacts.mockResolvedValue(okFacts([F({ id: 7, content: '宝贝记忆' })]))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.mem-act-del').trigger('click')
    await flushPromises()

    expect(api.deleteMemoryFact).not.toHaveBeenCalled()
    expect(api.getMyMemoryFacts).toHaveBeenCalledTimes(1) // 没刷新
    expect(wrapper.findAll('.mem-card')).toHaveLength(1)
  })

  it('状态徽章：ACTIVE 渲染"在档"、CANDIDATE 渲染"待确认"并附转正提示', async () => {
    api.getMyMemoryFacts.mockResolvedValue(okFacts([
      F({ id: 1, content: '已确认的', status: 'ACTIVE' }),
      F({ id: 2, content: '待确认的', status: 'CANDIDATE' }),
    ]))

    const wrapper = mountView()
    await flushPromises()

    const statuses = wrapper.findAll('.mem-status').map((s) => s.text())
    expect(statuses).toEqual(['在档', '待确认'])
    // 未知状态不能渲染成 undefined 把用户看懵（这里顺带钉住 catName/statusName 的兜底）。
    expect(wrapper.find('.mem-content-cand').text()).toBe('待确认的')
    // 候选条必须给"怎么转正"的提示，否则用户不知道点内容能改。
    expect(wrapper.findAll('.mem-cand-hint')).toHaveLength(1)
  })

  it('加载失败：不白屏，落到空态并解除 loading（错误不能吞掉整个页面）', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    api.getMyMemoryFacts.mockRejectedValueOnce(new Error('network down'))

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.mem-page').exists()).toBe(true)
    expect(wrapper.find('.mem-state').text()).toContain('档案还是空白的')
    // "翻开档案中……"必须消失，否则用户永远等不到结果。
    expect(wrapper.text()).not.toContain('翻开档案中')
    expect(wrapper.findAll('.mem-card')).toHaveLength(0)
    expect(err).toHaveBeenCalled()
  })
})
