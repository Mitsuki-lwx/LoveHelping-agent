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
  push: vi.fn(),
}))

vi.mock('../api/index.js', () => ({
  listAllConversations: mocks.listAllConversations,
  getConversationMessages: mocks.getConversationMessages,
  clearConversation: mocks.clearConversation,
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
  })

  it('非 ADMIN 访问：不发列表请求，直接重定向回首页（越权守卫）', async () => {
    localStorage.setItem('lwx_ai_user', JSON.stringify({ role: 'USER' }))
    mount(Admin, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    // 关键：不能先发管理端请求再跳转（那已经是越权读取了）
    expect(mocks.listAllConversations).not.toHaveBeenCalled()
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
