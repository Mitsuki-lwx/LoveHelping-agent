import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Admin from '../views/Admin.vue'

/**
 * 管理后台（Admin.vue，271 行）此前交互逻辑 **0%** 覆盖。
 *
 * 这里守的是「管理员唯一能操作数据的界面」上几条一改就出事、手测又很难发现的契约：
 *   · 非 ADMIN 进不来（越权/安全）；
 *   · 列表请求失败**不能白屏**，loading 必须收敛（否则永远转圈）；
 *   · 删除**必须先二次确认**才发请求（误删不可恢复）；
 *   · 删除失败**不能假装删掉**（本地列表保持不变）；
 *   · 详情请求失败不能把整页拖挂。
 *
 * api 层整体 mock（绝不发真请求）；vue-router 也 mock（组件里用 useRouter 做登录守卫跳转）。
 */

// vi.mock 的回调会被提升到文件顶部 ⇒ 用 vi.hoisted 声明可变 mock，避免"const 未初始化"陷阱。
const mocks = vi.hoisted(() => ({
  listAllConversations: vi.fn(),
  getConversationMessages: vi.fn(),
  clearConversation: vi.fn(),
  listPendingSkills: vi.fn(),
  auditSkill: vi.fn(),
  bulkRejectSkills: vi.fn(),
  getAnalyticsOverview: vi.fn(),
  push: vi.fn(),
}))

vi.mock('../api/index.js', () => ({
  listAllConversations: mocks.listAllConversations,
  getConversationMessages: mocks.getConversationMessages,
  clearConversation: mocks.clearConversation,
  listPendingSkills: mocks.listPendingSkills,
  auditSkill: mocks.auditSkill,
  bulkRejectSkills: mocks.bulkRejectSkills,
  getAnalyticsOverview: mocks.getAnalyticsOverview,
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
  useRoute: () => ({ query: {} }),
}))

/** 挂载并等 onMounted 里的列表请求落地。 */
async function mountAdmin() {
  const wrapper = mount(Admin, { global: { stubs: { RouterLink: true } } })
  await flushPromises()
  return wrapper
}

/** 从 mock 返回值构造一条会话记录 */
const conv = (id, count = 1) => ({
  conversation_id: id,
  message_count: count,
  created_at: '2026-10-01T10:20:30',
})

describe('Admin.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 组件 onMounted 用真实 getUser()（读 localStorage）做管理员守卫
    localStorage.clear()
    localStorage.setItem('lwx_ai_user', JSON.stringify({ role: 'ADMIN' }))
    mocks.listAllConversations.mockResolvedValue({ data: { data: [] } })
    mocks.getConversationMessages.mockResolvedValue({ data: { data: [] } })
    mocks.clearConversation.mockResolvedValue({ data: { code: 200 } })
    // 进化技能审核区的默认兜底（空列表，不干扰既有会话管理断言）
    mocks.listPendingSkills.mockResolvedValue({ data: { data: [] } })
    mocks.auditSkill.mockResolvedValue({ data: { success: true } })
    mocks.bulkRejectSkills.mockResolvedValue({ data: { data: 0 } })
    // 批量驳回走原生确认框：默认"确认"（不确认的用例自己覆盖）
    window.confirm = vi.fn(() => true)
  })

  it('非 ADMIN 访问：不发列表请求，直接重定向回首页（越权守卫）', async () => {
    localStorage.setItem('lwx_ai_user', JSON.stringify({ role: 'USER' }))
    mount(Admin, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    // 关键：不能先发管理端请求再跳转（那已经是越权读取了）
    expect(mocks.listAllConversations).not.toHaveBeenCalled()
    // 技能审核同样是管理端数据：非管理员时一条都不许拉
    expect(mocks.listPendingSkills).not.toHaveBeenCalled()
    expect(mocks.push).toHaveBeenCalledWith('/')
  })

  it('加载中先显示提示，数据到达后收敛为列表（loading 不卡死）', async () => {
    let resolveList
    mocks.listAllConversations.mockReturnValue(new Promise((r) => { resolveList = r }))
    const wrapper = mount(Admin, { global: { stubs: { RouterLink: true } } })

    // 请求未 resolve 时必须是"加载中"，而不是空态（否则闪一下"暂无数据"很误导）
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.loading-state').exists()).toBe(true)
    expect(wrapper.find('.empty-state').exists()).toBe(false)

    resolveList({ data: { data: [conv('aaaa1111')] } })
    await flushPromises()
    expect(wrapper.find('.loading-state').exists()).toBe(false)
    expect(wrapper.findAll('.conv-card')).toHaveLength(1)
  })

  it('列表为空：显示可读空态提示，三项统计均为 0', async () => {
    const wrapper = await mountAdmin()
    expect(wrapper.find('.empty-state').text()).toContain('暂无对话记录')
    expect(wrapper.find('.conv-list').exists()).toBe(false)
    const stats = wrapper.findAll('.stat-value').map((n) => n.text())
    expect(stats).toEqual(['0', '0', '0'])
  })

  it('列表加载失败：不白屏，仍给出可读提示且 loading 收敛（catch+finally 契约）', async () => {
    mocks.listAllConversations.mockRejectedValue(new Error('network down'))
    const wrapper = await mountAdmin()

    // 失败后必须走 finally，loading 关掉；同时空态兜底，不能整页空白
    expect(wrapper.find('.loading-state').exists()).toBe(false)
    expect(wrapper.find('.empty-state').text()).toContain('暂无对话记录')
    expect(wrapper.find('.admin-page').exists()).toBe(true)
  })

  it('总消息数：累加 message_count，字符串/缺失值不能变成 NaN', async () => {
    mocks.listAllConversations.mockResolvedValue({
      data: {
        data: [
          conv('a', '3'), // 后端偶尔把数字序列化成字符串
          conv('b', null), // 空值
          { conversation_id: 'c', created_at: '' }, // 字段整体缺失
        ],
      },
    })
    const wrapper = await mountAdmin()
    const values = wrapper.findAll('.stat-value').map((n) => n.text())
    // 索引 1 是"总消息数"：3 + 0 + 0
    expect(values[1]).toBe('3')
  })

  it('用户数：按 **user_id** 去重（同一用户多个会话只算一个）', async () => {
    // ⛔ 这条测试原先断言"按 conversation_id 去重"——那**正是缺陷**：
    //    按会话去重得到的就是会话数，标签却写着"用户数"（同一用户开两个会话被算成两个人）。
    //    实现改为按 user_id 去重后（后端补 SELECT user_id，ADR-77），断言随之更新。
    mocks.listAllConversations.mockResolvedValue({
      data: {
        data: [
          { conversation_id: 'c1', user_id: 'u1', message_count: 2 },
          { conversation_id: 'c2', user_id: 'u1', message_count: 3 },  // 同一用户的另一个会话
          { conversation_id: 'c3', user_id: 'u2', message_count: 1 },
        ],
      },
    })
    const wrapper = await mountAdmin()
    const values = wrapper.findAll('.stat-value').map((n) => n.text())
    expect(values[0]).toBe('3') // 总对话数：3 个会话
    expect(values[1]).toBe('6') // 总消息数：2+3+1
    expect(values[2]).toBe('2') // ⭐ 用户数：只有 u1 / u2 两个用户（c1/c2 同属 u1）
  })

  it('用户数：数据缺 user_id 时不计入（不把"未知用户"算成一个人）', async () => {
    mocks.listAllConversations.mockResolvedValue({
      data: { data: [{ conversation_id: 'c1', message_count: 1 }, { conversation_id: 'c2', user_id: 'u1', message_count: 1 }] },
    })
    const wrapper = await mountAdmin()
    const values = wrapper.findAll('.stat-value').map((n) => n.text())
    expect(values[2]).toBe('1')
  })

  it('查看详情：调接口取消息，USER 显示“你/msg-user”，其余显示“AI/msg-ai”', async () => {
    mocks.listAllConversations.mockResolvedValue({ data: { data: [conv('conv-1')] } })
    mocks.getConversationMessages.mockResolvedValue({
      data: {
        data: [
          { id: 1, messageType: 'USER', text: '你好' },
          { id: 2, messageType: 'AI', text: '在的' },
        ],
      },
    })
    const wrapper = await mountAdmin()
    await wrapper.find('.conv-card').trigger('click')
    await flushPromises()

    expect(mocks.getConversationMessages).toHaveBeenCalledWith('conv-1')
    const msgs = wrapper.findAll('.detail-msg')
    expect(msgs).toHaveLength(2)
    expect(msgs[0].classes()).toContain('msg-user')
    expect(msgs[0].find('.msg-role').text()).toBe('你')
    expect(msgs[1].classes()).toContain('msg-ai')
    expect(msgs[1].find('.msg-role').text()).toBe('AI')
  })

  it('详情超长文本按 500 字截断并加省略号（避免一次渲染几十 KB 正文）', async () => {
    mocks.listAllConversations.mockResolvedValue({ data: { data: [conv('conv-1')] } })
    mocks.getConversationMessages.mockResolvedValue({
      data: { data: [{ id: 1, messageType: 'USER', text: 'x'.repeat(501) }] },
    })
    const wrapper = await mountAdmin()
    await wrapper.find('.conv-card').trigger('click')
    await flushPromises()

    const text = wrapper.find('.detail-msg .msg-text').text()
    expect(text).toHaveLength(503) // 500 + '...'
    expect(text.endsWith('...')).toBe(true)
  })

  it('详情加载失败：显示“无消息”，列表与页面仍在，详情 loading 收敛', async () => {
    mocks.listAllConversations.mockResolvedValue({ data: { data: [conv('conv-1')] } })
    mocks.getConversationMessages.mockRejectedValue(new Error('500'))
    const wrapper = await mountAdmin()
    await wrapper.find('.conv-card').trigger('click')
    await flushPromises()

    // 详情请求失败不能把整个页面拖挂：弹层兜底"无消息"，外层列表不受影响
    expect(wrapper.find('.modal').exists()).toBe(true)
    expect(wrapper.find('.modal-body').text()).toContain('无消息')
    expect(wrapper.findAll('.conv-card')).toHaveLength(1)
  })

  it('删除需二次确认：点删除只弹确认框不发请求，取消后仍不发', async () => {
    mocks.listAllConversations.mockResolvedValue({ data: { data: [conv('conv-1')] } })
    const wrapper = await mountAdmin()

    await wrapper.find('.conv-card .del-btn').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.confirm-modal').exists()).toBe(true)
    expect(mocks.clearConversation).not.toHaveBeenCalled() // 未确认前绝不能删

    await wrapper.find('.confirm-modal .cancel-btn').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.confirm-modal').exists()).toBe(false)
    expect(mocks.clearConversation).not.toHaveBeenCalled()
  })

  it('确认删除成功：调用接口、卡片从列表移除，并关闭同一会话的详情弹层', async () => {
    mocks.listAllConversations.mockResolvedValue({
      data: { data: [conv('conv-1'), conv('conv-2')] },
    })
    const wrapper = await mountAdmin()

    // 先打开 conv-1 详情，再删它 ⇒ 详情必须一并关闭，不能残留已删会话的弹层
    await wrapper.find('.conv-card').trigger('click')
    await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(true)

    await wrapper.find('.conv-card .del-btn').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.find('.confirm-modal .delete-btn').trigger('click')
    await flushPromises()

    expect(mocks.clearConversation).toHaveBeenCalledWith('conv-1')
    const cards = wrapper.findAll('.conv-card')
    expect(cards).toHaveLength(1)
    expect(cards[0].text()).toContain('conv-2'.substring(0, 8))
    expect(wrapper.find('.confirm-modal').exists()).toBe(false)
    expect(wrapper.find('.modal').exists()).toBe(false) // 详情弹层已关
  })

  it('删除失败：卡片保留、确认框关闭，绝不谎报删除成功', async () => {
    mocks.listAllConversations.mockResolvedValue({ data: { data: [conv('conv-1')] } })
    mocks.clearConversation.mockRejectedValue(new Error('403'))
    const wrapper = await mountAdmin()

    await wrapper.find('.conv-card .del-btn').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.find('.confirm-modal .delete-btn').trigger('click')
    await flushPromises()

    expect(mocks.clearConversation).toHaveBeenCalledWith('conv-1')
    expect(wrapper.findAll('.conv-card')).toHaveLength(1) // 还在
    expect(wrapper.find('.confirm-modal').exists()).toBe(false) // 但弹层要收掉
  })

  it('详情内“删除此对话”不得绕过二次确认直接发删除请求', async () => {
    mocks.listAllConversations.mockResolvedValue({ data: { data: [conv('conv-1')] } })
    const wrapper = await mountAdmin()
    await wrapper.find('.conv-card').trigger('click')
    await flushPromises()

    // ⚠️ 该按钮当前绑定的是**未定义**的 deleteConfirmed（见缺陷报告），点击不会发生任何事；
    // 但无论实现如何，"删除必须经过确认框"这条安全契约都不能被绕过。
    await wrapper.find('.modal-footer .delete-btn').trigger('click')
    await flushPromises()
    expect(mocks.clearConversation).not.toHaveBeenCalled()
  })
})

/**
 * 进化技能审核区（2026-10-13 合规/治理新增）。
 *
 * ⛔ 守的是三件事，都是"改回去就出事、手测又难发现"的：
 *   1. 只拉**一页**（limit=50）—— 积压上万条，改成不传 limit 会一次性拉全量；
 *   2. 通过/驳回后必须调**正确端点 + 正确 status**，且该条从"待审"列表消失（否则管理员会重复审）；
 *   3. 批量驳回**不可逆** ⇒ 必须先二次确认，未确认绝不发请求；驳回条数要显示给用户。
 */
describe('Admin.vue · 进化技能审核', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('lwx_ai_user', JSON.stringify({ role: 'ADMIN' }))
    mocks.listAllConversations.mockResolvedValue({ data: { data: [] } })
    mocks.getConversationMessages.mockResolvedValue({ data: { data: [] } })
    mocks.listPendingSkills.mockResolvedValue({ data: { data: [] } })
    mocks.auditSkill.mockResolvedValue({ data: { success: true } })
    mocks.bulkRejectSkills.mockResolvedValue({ data: { data: 0 } })
    window.confirm = vi.fn(() => true)
  })

  const skill = (id, name = '技能' + id, score = 8) => ({
    id,
    skillName: name,
    description: '从真实对话里沉淀出的候选技能描述',
    qualityScore: score,
    createdAt: '2026-10-12T09:30:00',
  })

  it('只拉一页待审技能（limit=50），列表渲染名称/描述/质量分/时间，并说明"仅显示前 50 条"', async () => {
    mocks.listPendingSkills.mockResolvedValue({ data: { data: [skill(1, '共情追问', 9)] } })
    const wrapper = await mountAdmin()

    expect(mocks.listPendingSkills).toHaveBeenCalledWith('PENDING', 50)
    const card = wrapper.find('.skill-card')
    expect(card.exists()).toBe(true)
    expect(card.find('.skill-name').text()).toBe('共情追问')
    expect(card.find('.skill-desc').text()).toContain('沉淀')
    expect(card.find('.skill-meta').text()).toContain('9')
    expect(card.find('.skill-meta').text()).toContain('2026-10-12')
    expect(wrapper.find('.skill-head').text()).toContain('仅显示前 50 条')
  })

  it('点“通过”：调 auditSkill(id, APPROVED)，该条从待审列表移除', async () => {
    mocks.listPendingSkills.mockResolvedValue({ data: { data: [skill(1), skill(2)] } })
    const wrapper = await mountAdmin()
    expect(wrapper.findAll('.skill-card')).toHaveLength(2)

    await wrapper.find('.skill-card .audit-btn.approve').trigger('click')
    await flushPromises()

    expect(mocks.auditSkill).toHaveBeenCalledWith(1, 'APPROVED')
    const cards = wrapper.findAll('.skill-card')
    expect(cards).toHaveLength(1)
    expect(cards[0].find('.skill-name').text()).toBe('技能2')
  })

  it('点“驳回”：调 auditSkill(id, REJECTED)，同样从待审列表移除', async () => {
    mocks.listPendingSkills.mockResolvedValue({ data: { data: [skill(7)] } })
    const wrapper = await mountAdmin()

    await wrapper.find('.skill-card .audit-btn.reject').trigger('click')
    await flushPromises()

    expect(mocks.auditSkill).toHaveBeenCalledWith(7, 'REJECTED')
    expect(wrapper.findAll('.skill-card')).toHaveLength(0)
    expect(wrapper.find('.skill-empty').exists()).toBe(true)
  })

  it('审核失败：该条**留在列表里**并给出可读提示（不能假装审过了）', async () => {
    mocks.listPendingSkills.mockResolvedValue({ data: { data: [skill(3)] } })
    mocks.auditSkill.mockRejectedValue(Object.assign(new Error('500'), { response: { data: { message: '审核失败' } } }))
    const wrapper = await mountAdmin()

    await wrapper.find('.skill-card .audit-btn.approve').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.skill-card')).toHaveLength(1)
    expect(wrapper.find('.skill-error').text()).toContain('审核失败')
  })

  it('技能列表加载失败：给出提示、loading 收敛，会话管理区不受影响', async () => {
    mocks.listAllConversations.mockResolvedValue({ data: { data: [conv('conv-1')] } })
    mocks.listPendingSkills.mockRejectedValue(new Error('boom'))
    const wrapper = await mountAdmin()

    expect(wrapper.find('.skill-loading').exists()).toBe(false)
    expect(wrapper.find('.skill-empty').text()).toContain('暂无待审技能')
    expect(wrapper.findAll('.conv-card')).toHaveLength(1)
  })

  it('批量驳回：**未确认**时一个请求都不发', async () => {
    window.confirm = vi.fn(() => false)
    mocks.listPendingSkills.mockResolvedValue({ data: { data: [skill(1)] } })
    const wrapper = await mountAdmin()

    await wrapper.find('.bulk-btn').trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalled()
    expect(mocks.bulkRejectSkills).not.toHaveBeenCalled()
  })

  it('批量驳回：确认后调 bulkRejectSkills(5, 2000)，显示“已驳回 N 条”并刷新列表', async () => {
    mocks.listPendingSkills.mockResolvedValue({ data: { data: [skill(1)] } })
    mocks.bulkRejectSkills.mockResolvedValue({ data: { data: 37 } })
    const wrapper = await mountAdmin()
    expect(mocks.listPendingSkills).toHaveBeenCalledTimes(1)

    await wrapper.find('.bulk-btn').trigger('click')
    await flushPromises()

    expect(mocks.bulkRejectSkills).toHaveBeenCalledWith(5, 2000)
    expect(wrapper.find('.bulk-msg').text()).toContain('已驳回 37 条')
    // 驳回后必须重新拉一次列表（否则界面上还挂着已被清掉的候选）
    expect(mocks.listPendingSkills).toHaveBeenCalledTimes(2)
  })
})

/**
 * 数据概览区（2026-10-14，只读分析层）。
 *
 * ⛔ 守的是"数字被误读"这件事本身：
 *   1. 拉取必须带 days，切 7/30/90 要以对应参数重新请求；
 *   2. excludedTestUsers 必须显著展示 —— 不说明"已排除测试账号"，日活/新增会被误读成全体用户；
 *   3. 留存必须显示百分比且样本量可见（小样本的百分比会骗人）；
 *   4. 漏斗每步相对**首步**算百分比；
 *   5. series 渲染出正确数量的条形、高度随数据变化（纯 CSS，无图表库）；
 *   6. 空 series / 接口失败不能白屏，失败要给可读提示并可重试。
 */
describe('Admin.vue · 数据概览', () => {
  /** 契约样例：与后端 GET /admin/analytics/overview 的返回结构一致。 */
  const overview = (overrides = {}) => ({
    days: 30,
    generatedAt: '2026-10-14T20:00:00',
    excludedTestUsers: 2400,
    // 数据质量自述（2026-10-14 补）：排除清单是枚举式的，追不上脚本造账号的速度，
    // 所以接口必须自报"排除了多少、占比多高"，界面必须展示（否则数字会被误读）。
    dataQuality: { totalUsers: 2642, excludedUsers: 2400, excludedRatio: 0.908, suspect: true },
    series: [
      { date: '2026-10-12', activeUsers: 3, newUsers: 1, messages: 12 },
      { date: '2026-10-13', activeUsers: 6, newUsers: 2, messages: 20 },
      { date: '2026-10-14', activeUsers: 0, newUsers: 0, messages: 0 },
    ],
    retention: { cohortSize: 50, d1: 0.42, d7: 0.18 },
    funnel: [
      { step: '注册', count: 50 },
      { step: '首次对话', count: 45 },
      { step: '次日回访', count: 21 },
    ],
    features: [ { name: '聊天会话', count: 120 }, { name: '角色模拟屋', count: 60 } ],
    ...overrides,
  })

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('lwx_ai_user', JSON.stringify({ role: 'ADMIN' }))
    mocks.listAllConversations.mockResolvedValue({ data: { data: [] } })
    mocks.getConversationMessages.mockResolvedValue({ data: { data: [] } })
    mocks.clearConversation.mockResolvedValue({ data: { code: 200 } })
    mocks.listPendingSkills.mockResolvedValue({ data: { data: [] } })
    mocks.auditSkill.mockResolvedValue({ data: { success: true } })
    mocks.bulkRejectSkills.mockResolvedValue({ data: { data: 0 } })
    mocks.getAnalyticsOverview.mockResolvedValue({ data: { data: overview() } })
    window.confirm = vi.fn(() => true)
  })

  it('挂载时按默认 days=30 拉取概览', async () => {
    const wrapper = await mountAdmin()
    expect(mocks.getAnalyticsOverview).toHaveBeenCalledWith(30)
    expect(wrapper.find('.analytics-section').exists()).toBe(true)
  })

  it('切换 7/30/90 天：以对应 days 参数重新请求，请求参数与按钮一致', async () => {
    const wrapper = await mountAdmin()
    expect(mocks.getAnalyticsOverview).toHaveBeenCalledTimes(1)

    await wrapper.findAll('.range-btn').filter(b => b.text() === '7 天').at(0).trigger('click')
    await flushPromises()
    expect(mocks.getAnalyticsOverview).toHaveBeenLastCalledWith(7)
    expect(wrapper.find('.analytics-generated').text()).toContain('最近 7 天')

    await wrapper.findAll('.range-btn').filter(b => b.text() === '90 天').at(0).trigger('click')
    await flushPromises()
    expect(mocks.getAnalyticsOverview).toHaveBeenLastCalledWith(90)
  })

  it('核心数字卡：峰值日活 = series 最大值，期末日活 = 最后一天，新增 = newUsers 合计', async () => {
    const wrapper = await mountAdmin()
    const values = wrapper.findAll('.ana-value').map((n) => n.text())
    expect(values).toEqual(['6', '0', '3']) // 峰值 6 / 期末(最后一天 activeUsers=0) 0 / 新增 1+2+0
  })

  it('series 渲染出正确数量的条形，且高度随当天数据归一化（峰值为 100%）', async () => {
    const wrapper = await mountAdmin()
    const bars = wrapper.findAll('.trend-bar')
    expect(bars).toHaveLength(3)

    // 峰值日（activeUsers=6）→ 100%；0 值日给最小可见高度而不是 0（彻底消失等于"那天不存在"）
    const styles = bars.map(b => b.attributes('style'))
    expect(styles[1]).toContain('height: 100%')
    expect(parseFloat(styles[2].match(/height:\s*([\d.]+)%/)[1])).toBeLessThanOrEqual(4)
  })

  it('趋势条 title 带当日明细（悬停可读，不用图表库）', async () => {
    const wrapper = await mountAdmin()
    expect(wrapper.findAll('.trend-bar')[0].attributes('title')).toContain('2026-10-12')
    expect(wrapper.findAll('.trend-bar')[0].attributes('title')).toContain('活跃 3')
  })

  it('留存渲染为百分比且样本量可见（0.42 → 42%，cohortSize=50 必须显示）', async () => {
    const wrapper = await mountAdmin()
    const block = wrapper.findAll('.ana-block').find(b => b.find('.retention-pct').exists())
    const pcts = block.findAll('.retention-pct').map((n) => n.text())
    expect(pcts).toEqual(['42%', '18%'])
    expect(block.text()).toContain('样本 50 人')
  })

  it('漏斗每步百分比相对首步计算（45/50=90%，21/50=42%）', async () => {
    const wrapper = await mountAdmin()
    const funnelBlock = wrapper.findAll('.ana-block').find(b => b.text().includes('转化漏斗'))
    const rows = funnelBlock.findAll('.funnel-row')
    expect(rows).toHaveLength(3)
    expect(rows[0].find('.funnel-num').text()).toBe('50 人（100%）')
    expect(rows[1].find('.funnel-num').text()).toBe('45 人（90%）')
    expect(rows[2].find('.funnel-num').text()).toBe('21 人（42%）')
    // 条宽同步相对首步
    expect(rows[1].find('.funnel-bar').attributes('style')).toContain('width: 90%')
  })

  it('功能使用按次数归一化为横向条形（最大值=100%）', async () => {
    const wrapper = await mountAdmin()
    const rows = wrapper.findAll('.ana-block').find(b => b.text().includes('功能使用')).findAll('.funnel-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].find('.funnel-bar').attributes('style')).toContain('width: 100%')
    expect(rows[1].find('.funnel-bar').attributes('style')).toContain('width: 50%')
    expect(rows[0].find('.funnel-num').text()).toContain('120 次')
  })

  it('excludedTestUsers 显著展示：文案必须说明"已排除 N 个测试账号"', async () => {
    const wrapper = await mountAdmin()
    const banner = wrapper.find('.excluded-banner')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('已排除 2,400 个测试账号')
  })

  /**
   * ⛔ 回归：`loadAnalytics` 是**白名单式挑字段**，后端新增字段会被静默丢弃 ——
   * 实测踩过：`dataQuality` 没接上，横幅显示"已排除 2,521 个（占 0%）、剩余 -2,521 个账号"。
   */
  it('dataQuality 必须接上：横幅显示排除比例与剩余账号数', async () => {
    const wrapper = await mountAdmin()
    const banner = wrapper.find('.excluded-banner').text()
    expect(banner).toContain('占 91%')          // 0.908 → 91%
    expect(banner).toContain('242')             // 2642 − 2400
    expect(banner).not.toContain('-2,')         // 不许出现负数（字段没接上时的症状）
  })

  it('排除比例过半时给出「仅供参考」警示', async () => {
    const wrapper = await mountAdmin()
    expect(wrapper.find('.ana-suspect').exists()).toBe(true)
    expect(wrapper.find('.ana-suspect').text()).toContain('仅供参考')
  })

  it('series 为空：核心卡片仍渲染（0 值兜底），显示空态而不是白屏', async () => {
    mocks.getAnalyticsOverview.mockResolvedValue({
      data: { data: overview({ series: [], retention: { cohortSize: 0, d1: 0, d7: 0 } }) },
    })
    const wrapper = await mountAdmin()

    expect(wrapper.find('.trend-chart').exists()).toBe(false)
    expect(wrapper.findAll('.ana-value').map(n => n.text())).toEqual(['0', '0', '0'])
    expect(wrapper.find('.analytics-section').text()).toContain('该时间窗内暂无数据')
  })

  it('漏斗首步为 0：不除零（显示 100% 而不是 NaN%），如实显示人数', async () => {
    mocks.getAnalyticsOverview.mockResolvedValue({
      data: { data: overview({ funnel: [ { step: '注册', count: 0 }, { step: '首次对话', count: 0 } ] }) },
    })
    const wrapper = await mountAdmin()
    const funnelBlock = wrapper.findAll('.ana-block').find(b => b.text().includes('转化漏斗'))
    expect(funnelBlock.find('.funnel-num').text()).toContain('0 人（100%）')
    expect(funnelBlock.text()).not.toContain('NaN')
  })

  it('接口失败：给出可读提示、保留重试按钮、不白屏；重试成功后提示收敛并渲染数据', async () => {
    mocks.getAnalyticsOverview.mockRejectedValue(new Error('network down'))
    const wrapper = await mountAdmin()

    const section = wrapper.find('.analytics-section')
    expect(section.exists()).toBe(true) // 整页不白屏
    const alert = section.find('.admin-error')
    expect(alert.text()).toContain('数据概览加载失败')
    expect(section.find('.retry-row button').exists()).toBe(true)

    // 重试成功：提示收敛、数据渲染出来
    mocks.getAnalyticsOverview.mockResolvedValue({ data: { data: overview() } })
    await section.find('.retry-row button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.analytics-section .admin-error').exists()).toBe(false)
    expect(wrapper.findAll('.trend-bar')).toHaveLength(3)
  })
})
