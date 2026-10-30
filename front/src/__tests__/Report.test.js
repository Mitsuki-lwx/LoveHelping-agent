import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Report from '../views/Report.vue'

/**
 * 投诉举报页（/report，2026-10-13 合规改造新增）。
 *
 * ⛔ 监管要求"设置便捷的投诉举报入口，公布处理流程和反馈时限"，
 *    所以这里守的是三件事：
 *      1. 非法/空内容**不许发请求**（并且要给用户看得懂的提示，不能静默失败）；
 *      2. 提交时发给后端的是**枚举值**（CONTENT/PRIVACY/ABUSE/OTHER），不是中文；
 *      3. 页面**当场**写明反馈时限（15 个工作日），并能回看自己提交过的举报与状态。
 *
 * api 层 mock（绝不发真请求），但**保留真实常量**（COMPLAINT_CATEGORIES）——
 * 否则测试会自己编一份分类，漂移了也发现不了。
 */

const { api } = vi.hoisted(() => ({
  api: { submitComplaint: vi.fn(), listMyComplaints: vi.fn() },
}))

vi.mock('../api/index.js', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, submitComplaint: api.submitComplaint, listMyComplaints: api.listMyComplaints }
})

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ query: {} }),
}))

const mountReport = async () => {
  const w = mount(Report, { global: { mocks: { $router: { push } }, stubs: { RouterLink: true } } })
  await flushPromises()
  return w
}

describe('投诉举报页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listMyComplaints.mockResolvedValue({ data: { data: [] } })
    api.submitComplaint.mockResolvedValue({ data: { success: true, message: '已收到你的举报' } })
  })

  it('四个中文分类选项，值为后端枚举', async () => {
    const w = await mountReport()
    const opts = w.findAll('#report-category option')
    expect(opts).toHaveLength(4)
    expect(opts.map((o) => o.attributes('value'))).toEqual(['CONTENT', 'PRIVACY', 'ABUSE', 'OTHER'])
    expect(opts.map((o) => o.text())).toEqual(['内容违规', '隐私泄露', '辱骂/骚扰', '其他问题'])
  })

  it('公布处理时限：页面写明 15 个工作日', async () => {
    const w = await mountReport()
    expect(w.find('.report-sla').text()).toContain('15 个工作日')
  })

  it('内容为空：不发请求，给出可读提示', async () => {
    const w = await mountReport()
    await w.find('#report-content').setValue('   ')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(api.submitComplaint).not.toHaveBeenCalled()
    expect(w.find('.form-msg.err').text()).toContain('请填写具体情况')
  })

  it('内容超过 1000 字：不发请求', async () => {
    const w = await mountReport()
    await w.find('#report-content').setValue('x'.repeat(1001))
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(api.submitComplaint).not.toHaveBeenCalled()
    expect(w.find('.form-msg.err').text()).toContain('1000')
  })

  it('提交成功：发枚举值 + 可选联系方式，给出反馈并清空输入、刷新列表', async () => {
    api.listMyComplaints
      .mockResolvedValueOnce({ data: { data: [] } })
      .mockResolvedValueOnce({ data: { data: [{ id: 1, category: 'PRIVACY', content: '有人贴了我的聊天记录', status: 'OPEN', createdAt: '2026-10-13T10:00:00' }] } })
    const w = await mountReport()

    await w.find('#report-category').setValue('PRIVACY')
    await w.find('#report-content').setValue('有人贴了我的聊天记录')
    await w.find('#report-contact').setValue('me@example.com')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(api.submitComplaint).toHaveBeenCalledWith({
      category: 'PRIVACY',
      content: '有人贴了我的聊天记录',
      contact: 'me@example.com',
    })
    expect(w.find('.form-msg.ok').text()).toContain('已收到你的举报')
    expect(w.find('#report-content').element.value).toBe('')
    expect(api.listMyComplaints).toHaveBeenCalledTimes(2)
    expect(w.findAll('.report-item')).toHaveLength(1)
  })

  it('后端返回 success=false：显示失败原因，且**不清空**用户写的内容', async () => {
    api.submitComplaint.mockResolvedValue({ data: { success: false, message: '举报太频繁，请稍后再试' } })
    const w = await mountReport()
    await w.find('#report-content').setValue('我要举报')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(w.find('.form-msg.err').text()).toContain('举报太频繁')
    expect(w.find('#report-content').element.value).toBe('我要举报')
    expect(w.findAll('.report-item')).toHaveLength(0)
  })

  it('网络异常：显示可读提示（不静默失败）', async () => {
    api.submitComplaint.mockRejectedValue(Object.assign(new Error('x'), { response: { data: { message: '网络错误' } } }))
    const w = await mountReport()
    await w.find('#report-content').setValue('我要举报')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(w.find('.form-msg.err').text()).toContain('网络错误')
  })

  it('“我的举报”列表：渲染分类中文、处理状态中文与时间', async () => {
    api.listMyComplaints.mockResolvedValue({
      data: {
        data: [
          { id: 1, category: 'ABUSE', content: 'AI 回复里有辱骂词', status: 'PROCESSING', createdAt: '2026-10-13T14:20:00' },
          { id: 2, category: 'CONTENT', content: '内容不对', status: 'CLOSED', createdAt: '2026-10-12T09:00:00' },
        ],
      },
    })
    const w = await mountReport()

    const items = w.findAll('.report-item')
    expect(items).toHaveLength(2)
    expect(items[0].find('.report-cat').text()).toBe('辱骂/骚扰')
    expect(items[0].find('.report-status').text()).toBe('处理中')
    expect(items[0].text()).toContain('2026-10-13')
    expect(items[1].find('.report-cat').text()).toBe('内容违规')
    expect(items[1].find('.report-status').text()).toBe('已完结')
  })

  it('列表为空：给出空态而不是空白', async () => {
    const w = await mountReport()
    expect(w.find('.report-empty').text()).toContain('还没有提交过举报')
  })

  it('列表加载失败：给出可读提示，表单仍可用', async () => {
    api.listMyComplaints.mockRejectedValue(new Error('500'))
    const w = await mountReport()

    expect(w.find('.form-msg.err').text()).toContain('举报记录加载失败')
    expect(w.find('form').exists()).toBe(true)
  })
})
