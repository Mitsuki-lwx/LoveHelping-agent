import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Terms from '../views/Terms.vue'
import Privacy from '../views/Privacy.vue'
import { LEGAL_CONTACT } from '../utils/legal.js'

/**
 * 用户服务协议 / 隐私政策（2026-10-13 合规改造新增）。
 *
 * ⛔ 这两页此前**完全不存在**（审计发现"用户协议 / 隐私政策"为零），而它们要解决的是
 *    监管与用户都会真的去读的问题，所以这里断言的不是"页面能渲染"，而是**关键条款真的写在上面**：
 *      · AI 生成内容如何标识（办法第四条(一)要求显式标识）；
 *      · 投诉举报的反馈时限（15 个工作日）—— 不能是"尽快处理"这种空话；
 *      · 自伤信号的动作是**转介**（推送援助热线），不是治疗；
 *      · 数据出域清单逐项列出（DeepSeek / 硅基流动 / TypeSafe / 百度 / Langfuse）；
 *      · 删除权与导出权如实写明（对应"导出我的数据"与"注销即级联删除"两个真实功能）；
 *      · 联系方式**必须是占位**，不许编造一个收不到信的邮箱。
 */

const mountPage = (comp) => mount(comp, { global: { stubs: { RouterLink: true } } })

describe('用户服务协议（/terms）', () => {
  it('渲染标题与生效日期', () => {
    const w = mountPage(Terms)
    expect(w.find('.legal-title').text()).toContain('用户服务协议')
    expect(w.text()).toContain('生效日期：2026-10-13')
  })

  it('写明 AI 生成内容的标识方式（气泡标签 + 导出文件字段）', () => {
    const text = mountPage(Terms).text()
    expect(text).toContain('AI 生成')
    expect(text).toContain('aiGenerated')
    expect(text).toContain('由人工智能生成')
  })

  it('写明 AI 的能力边界：不得用于专业心理治疗，风险信号的动作是转介', () => {
    const text = mountPage(Terms).text()
    expect(text).toContain('不是专业心理治疗')
    expect(text).toContain('转介')
    expect(text).toContain('心理援助热线')
  })

  it('写明投诉举报的渠道与反馈时限（15 个工作日）', () => {
    const text = mountPage(Terms).text()
    expect(text).toContain('投诉举报')
    expect(text).toContain('15 个工作日')
  })

  it('写明未成年人需监护人同意，且已启用未成年人模式（额度 + 时段）', () => {
    const text = mountPage(Terms).text()
    expect(text).toContain('监护人')
    expect(text).toContain('未成年人模式')
    expect(text).toContain('30 条')
    expect(text).toContain('22:00')
  })

  it('⛔ 联系方式是占位说明，页面里不得出现编造的邮箱/电话', () => {
    const w = mountPage(Terms)
    expect(w.find('.legal-placeholder').exists()).toBe(true)
    // 未配置时 LEGAL_CONTACT 里就是空的 —— 页面不许凭空造一个联系方式出来
    expect(LEGAL_CONTACT.email).toBe('')
    expect(LEGAL_CONTACT.phone).toBe('')
    expect(w.text()).not.toMatch(/[\w.+-]+@[\w-]+\.[\w.]+/)
  })
})

describe('隐私政策（/privacy）', () => {
  it('渲染标题与生效日期', () => {
    const w = mountPage(Privacy)
    expect(w.find('.legal-title').text()).toContain('隐私政策')
    expect(w.text()).toContain('生效日期：2026-10-13')
  })

  it('逐项列出数据出域清单（含域名与"不含原文"的 Langfuse）', () => {
    const text = mountPage(Privacy).text()
    for (const domain of ['api.deepseek.com', 'api.siliconflow.cn', 'api.typesafe.ai']) {
      expect(text).toContain(domain)
    }
    expect(text).toContain('Langfuse')
    expect(text).toContain('不含对话原文')
    expect(text).toContain('生产默认关闭')
    // 表格里每一行都要有接收方与状态，不能只写"可能共享给第三方"
    expect(mountPage(Privacy).findAll('.legal-table tbody tr').length).toBeGreaterThanOrEqual(5)
  })

  it('写明收集的数据类别、用途与保存期限', () => {
    const text = mountPage(Privacy).text()
    expect(text).toContain('账号信息')
    expect(text).toContain('聊天内容')
    expect(text).toContain('记忆与关系档案')
    expect(text).toContain('30 天')
  })

  it('写明删除权与导出权（对应真实功能）', () => {
    const text = mountPage(Privacy).text()
    expect(text).toContain('导出我的数据')
    expect(text).toContain('注销账号')
    expect(text).toContain('删除')
  })

  it('写明 AI 生成标识与投诉举报时限', () => {
    const text = mountPage(Privacy).text()
    expect(text).toContain('AI 生成')
    expect(text).toContain('aiGeneratedNote')
    expect(text).toContain('15 个工作日')
  })

  it('写明未成年人模式', () => {
    const text = mountPage(Privacy).text()
    expect(text).toContain('未成年人模式')
    expect(text).toContain('监护人')
  })

  it('⛔ 与「进化模块单独同意」开关对得上：默认不做、开启才提炼、可随时关闭', () => {
    const text = mountPage(Privacy).text()
    expect(text).toContain('单独同意')
    expect(text).toContain('默认不做')
    expect(text).toContain('通用沟通技巧')
    expect(text).toContain('不保留你的原文')
    expect(text).toContain('总协议勾选')
  })
})
