import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import TodaySection from '../components/TodaySection.vue'

/**
 * 「今日」区块（TodaySection.vue）的交互契约。
 *
 * 这个区块的风险点（都是用户直接看得见的）：
 *   1) 心情打卡要**真的换主题** —— 用户提的需求就是"不同心情代表不同 UI 风格"，
 *      只弹个提示不换配色 = 需求没实现；
 *   2) 纪念日写后端：**非法输入不能发请求**（白跑一趟还显示成功最糟），
 *      失败必须让用户看到原因（而不是静默什么都没发生）；
 *   3) 抽签/答题的状态要能跨刷新保留（当天）；
 *   4) 「去聊聊」要带上下文跳转 —— 只跳不带上问题，用户还得自己再打一遍。
 */

const { api, routerPush, setThemeMock, profile } = vi.hoisted(() => ({
  api: { updateProfileApi: vi.fn() },
  routerPush: vi.fn(),
  setThemeMock: vi.fn((id) => id),
  profile: { cached: {}, merged: [], refresh: vi.fn() },
}))

vi.mock('../api/index.js', () => api)
/**
 * ⛔ 资料读的是**缓存**（唯一取数点在 utils/profile.js）——
 *   组件自己发 /auth/me 会在单测里真连网（ECONNRESET + 拖慢整套），所以这里 mock 掉。
 */
vi.mock('../utils/profile.js', () => ({
  cachedProfile: () => profile.cached,
  mergeProfile: (patch) => { profile.merged.push(patch); Object.assign(profile.cached, patch); return profile.cached },
  refreshProfile: (...a) => profile.refresh(...a),
}))
vi.mock('../utils/theme.js', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, setTheme: setThemeMock }
})
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }) }))

const mountToday = () => mount(TodaySection, { global: { stubs: { PhArrowRight: true } } })

describe('TodaySection · 今日心情打卡', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    profile.cached = {}; profile.merged = []; profile.refresh.mockResolvedValue(null)
  })
  afterEach(() => localStorage.clear())

  it('渲染 6 个心情，且未选时没有回复', () => {
    const w = mountToday()
    expect(w.findAll('.mood-chip')).toHaveLength(6)
    expect(w.find('.mood-reply').exists()).toBe(false)
  })

  it('⛔ 点心情必须**真的换主题**（这是用户提的需求本身），并给出温柔回应', async () => {
    const w = mountToday()
    const miss = w.findAll('.mood-chip')[2]          // 想念
    await miss.trigger('click')
    expect(setThemeMock).toHaveBeenCalledWith('思念')
    expect(w.find('.mood-chip.on').text()).toContain('想念')
    expect(w.find('.mood-reply').text().length).toBeGreaterThan(6)
  })

  it('选过的心情会记住（刷新后仍是选中态）', async () => {
    const w1 = mountToday()
    await w1.findAll('.mood-chip')[5].trigger('click')   // 低落
    const w2 = mountToday()
    expect(w2.find('.mood-chip.on').text()).toContain('低落')
  })
})

describe('TodaySection · 每日一问', () => {
  beforeEach(() => {
    localStorage.clear(); vi.clearAllMocks()
    profile.cached = {}; profile.merged = []; profile.refresh.mockResolvedValue(null)
  })
  afterEach(() => localStorage.clear())

  it('未答时不显示解读与入口', () => {
    const w = mountToday()
    expect(w.find('.q-text').text().length).toBeGreaterThan(4)
    expect(w.findAll('.q-opt')).toHaveLength(3)
    expect(w.find('.q-read').exists()).toBe(false)
    expect(w.find('.q-go').exists()).toBe(false)
  })

  it('答完显示解读，并给出「去聊聊」', async () => {
    const w = mountToday()
    await w.findAll('.q-opt')[0].trigger('click')
    expect(w.find('.q-read').text().length).toBeGreaterThan(8)
    expect(w.find('.q-go').exists()).toBe(true)
  })

  it('⛔「去聊聊」要把问题与所选答案**带过去**（只跳不带 = 用户得重打一遍）', async () => {
    const w = mountToday()
    const q = w.find('.q-text').text()
    await w.findAll('.q-opt')[1].trigger('click')
    await w.find('.q-go').trigger('click')
    expect(routerPush).toHaveBeenCalledTimes(1)
    const arg = routerPush.mock.calls[0][0]
    expect(arg.path).toBe('/love-chat')
    expect(arg.query.q).toContain(q)
    expect(arg.query.q).toContain(w.findAll('.q-opt')[1].text())
  })
})

describe('TodaySection · 情话签', () => {
  beforeEach(() => {
    localStorage.clear(); vi.clearAllMocks()
    profile.cached = {}; profile.merged = []; profile.refresh.mockResolvedValue(null)
  })
  afterEach(() => localStorage.clear())

  it('抽之前没有签文，抽之后有；再抽能换到另一句', async () => {
    const w = mountToday()
    expect(w.find('.slip-text').exists()).toBe(false)
    await w.find('.slip-acts .btn-hand').trigger('click')
    const first = w.find('.slip-text').text()
    expect(first.length).toBeGreaterThan(4)
    await w.find('.slip-acts .btn-hand').trigger('click')
    expect(w.find('.slip-text').text()).not.toBe(first)
  })

  it('「折进信里」把签文带去聊天', async () => {
    const w = mountToday()
    await w.find('.slip-acts .btn-hand').trigger('click')
    const slip = w.find('.slip-text').text()
    await w.find('.slip-fold').trigger('click')
    expect(routerPush.mock.calls[0][0].query.q).toContain(slip)
  })
})

describe('TodaySection · 纪念日', () => {
  beforeEach(() => {
    localStorage.clear(); vi.clearAllMocks()
    profile.cached = {}; profile.merged = []; profile.refresh.mockResolvedValue(null)
  })
  afterEach(() => localStorage.clear())

  it('未设置时提示填日期，且不显示天数', () => {
    const w = mountToday()
    expect(w.find('.anniv-days').exists()).toBe(false)
    expect(w.find('.anniv-input').exists()).toBe(true)
  })

  it('⛔ 非法输入**不发请求**，并给出可读错误（白跑一趟最糟）', async () => {
    const w = mountToday()
    await w.find('.anniv-input').setValue('')
    await w.find('.anniv-form .btn-hand').trigger('click')
    expect(api.updateProfileApi).not.toHaveBeenCalled()
    expect(w.find('.anniv-err').text().length).toBeGreaterThan(4)
  })

  it('合法日期 → 调接口并显示「第 N 天」', async () => {
    api.updateProfileApi.mockResolvedValue({ data: { success: true } })
    const w = mountToday()
    const now = new Date()
    const iso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
    await w.find('.anniv-input').setValue(iso)
    await w.find('.anniv-form .btn-hand').trigger('click')
    await flushPromises()
    expect(api.updateProfileApi).toHaveBeenCalledWith({ anniversaryDate: iso })
    expect(w.find('.anniv-days').text()).toContain('1')
  })

  it('后端报错时把原因显示出来（不静默吞掉）', async () => {
    api.updateProfileApi.mockResolvedValue({ data: { success: false, message: '日期不合法' } })
    const w = mountToday()
    await w.find('.anniv-input').setValue('2026-01-01')
    await w.find('.anniv-form .btn-hand').trigger('click')
    await flushPromises()
    expect(w.find('.anniv-err').text()).toContain('日期不合法')
  })

  it('保存成功后**写进资料缓存**（其他组件靠它拿到新值）', async () => {
    api.updateProfileApi.mockResolvedValue({ data: { success: true } })
    const w = mountToday()
    await w.find('.anniv-input').setValue('2026-01-01')
    await w.find('.anniv-form .btn-hand').trigger('click')
    await flushPromises()
    expect(profile.merged).toContainEqual({ anniversaryDate: '2026-01-01' })
  })

  it('已设置过的纪念日从**缓存**读回来并直接显示天数（不发请求）', async () => {
    profile.cached = { anniversaryDate: '2026-01-01' }
    const w = mountToday()
    await flushPromises()
    expect(w.find('.anniv-days').text().length).toBeGreaterThan(0)
    expect(w.find('.anniv-input').exists()).toBe(false)
    expect(api.updateProfileApi).not.toHaveBeenCalled()
  })

  it('缓存里没有时会补刷一次（刚登录还没刷到的场景）', async () => {
    profile.cached = {}
    profile.refresh.mockResolvedValue({ anniversaryDate: '2026-01-01' })
    const w = mountToday()
    await flushPromises()
    expect(profile.refresh).toHaveBeenCalled()
    expect(w.find('.anniv-days').exists()).toBe(true)
  })
})
