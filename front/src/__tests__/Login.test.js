import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Login from '../views/Login.vue'
import { createRouter, createWebHistory } from 'vue-router'

// 找回密码用到的新接口（vi.mock 提升到顶部，用 vi.hoisted 保证先存在）
const { api } = vi.hoisted(() => ({
  api: { loginApi: vi.fn(), registerApi: vi.fn(), sendResetCode: vi.fn(), resetPassword: vi.fn() },
}))
vi.mock('../api/index.js', () => api)

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'Login', component: Login },
    // 注册态的协议链接指向公开法务页（2026-10-13）—— 给测试路由补上，避免 unmatched 警告。
    { path: '/terms', name: 'Terms', component: { template: '<div />' } },
    { path: '/privacy', name: 'Privacy', component: { template: '<div />' } },
    { path: '/', name: 'Home', component: { template: '<div />' } },
  ]
})

describe('Login.vue', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear() })

  it('renders login form', async () => {
    const wrapper = mount(Login, {
      global: { plugins: [router] }
    })
    // ⛔ 2026-10-01：原断言 `toContain('LoveHelping')` 是**旧英文品牌**，页面已改为中文名 ⇒ 永久红。
    //    改为判**结构 + 当前产品名**：品牌元素存在即可，不再钉旧字面量。
    const title = wrapper.find('.login-title')
    expect(title.exists()).toBe(true)
    expect(title.text()).toContain('恋爱解忧所')
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.findAll('input').length).toBe(2)
  })

  it('toggles between login and register', async () => {
    const wrapper = mount(Login, {
      global: { plugins: [router] }
    })
    expect(wrapper.text()).toContain('登录')
    // ⛔ 2026-10-13：注册态新增了协议勾选行，里面有两个链接（《用户服务协议》《隐私政策》）
    //    ⇒ `find('a')` 取"第一个 a"不再等于切换链接。改用语义选择器 `.switch-link a`，
    //    断言的行为没变（点它能在登录/注册之间切换）。
    const link = wrapper.find('.switch-link a')
    expect(link.text()).toContain('去注册')
    await link.trigger('click')
    expect(wrapper.text()).toContain('注册')
    expect(wrapper.find('.switch-link a').text()).toContain('去登录')
  })

  it('shows error on empty submit', async () => {
    const wrapper = mount(Login, {
      global: { plugins: [router] }
    })
    await wrapper.find('form').trigger('submit')
    expect(wrapper.text()).toContain('用户名和密码不能为空')
  })

  // ---------- 找回密码（2026-10-07）----------
  describe('找回密码', () => {
    const openReset = async (w) => {
      const btn = w.find('.forgot-btn')
      expect(btn.exists(), '登录态应有"忘记密码"入口').toBe(true)
      await btn.trigger('click')
    }

    it('登录态才有入口；点开后出现用户名/验证码/新密码三个输入', async () => {
      const w = mount(Login, { global: { plugins: [router] } })
      await openReset(w)
      expect(w.find('#reset-username').exists()).toBe(true)
      expect(w.find('#reset-code').exists()).toBe(true)
      expect(w.find('#reset-password').exists()).toBe(true)
      // 回到登录后入口还在、重置表单消失
      await w.find('.reset-form .switch-link a').trigger('click')
      expect(w.find('.reset-form').exists()).toBe(false)
    })

    it('⛔ 发码后的文案**不能**断言"已发送到你的邮箱"（后端故意不区分账号是否存在）', async () => {
      api.sendResetCode.mockResolvedValue({ data: { code: 200, message: '如果该账号绑定了邮箱，验证码已发出（5 分钟内有效）' } })
      const w = mount(Login, { global: { plugins: [router] } })
      await openReset(w)
      await w.find('#reset-username').setValue('someone')
      await w.find('.code-btn').trigger('click')
      await flushPromises()
      expect(api.sendResetCode).toHaveBeenCalledWith('someone')
      const txt = w.find('.notice-msg').text()
      expect(txt).toContain('如果')
      expect(txt).not.toMatch(/已发送到你的邮箱|发送成功/)
    })

    it('本地校验：没填用户名不发请求；新密码不足 8 位不发请求', async () => {
      const w = mount(Login, { global: { plugins: [router] } })
      await openReset(w)
      await w.find('.code-btn').trigger('click')
      expect(api.sendResetCode).not.toHaveBeenCalled()

      await w.find('#reset-username').setValue('u1')
      await w.find('#reset-code').setValue('123456')
      await w.find('#reset-password').setValue('short')
      await w.find('.reset-form').trigger('submit')   // ⛔ 点按钮不触发表单提交，要触发 form 的 submit
      expect(api.resetPassword).not.toHaveBeenCalled()
      expect(w.find('.error-msg').text()).toContain('8 位')
    })

    it('提交成功后提示并自动回到登录态', async () => {
      vi.useFakeTimers()
      api.resetPassword.mockResolvedValue({ data: { code: 200, message: '密码已重置，请用新密码登录' } })
      const w = mount(Login, { global: { plugins: [router] } })
      await openReset(w)
      await w.find('#reset-username').setValue('u1')
      await w.find('#reset-code').setValue('123456')
      await w.find('#reset-password').setValue('Passw0rd!123')
      await w.find('.reset-form').trigger('submit')   // ⛔ 点按钮不触发表单提交，要触发 form 的 submit
      await flushPromises()
      expect(api.resetPassword).toHaveBeenCalledWith('u1', '123456', 'Passw0rd!123')
      expect(w.find('.notice-msg').text()).toContain('密码已重置')
      vi.advanceTimersByTime(1300)
      await flushPromises()
      expect(w.find('.reset-form').exists()).toBe(false)
      vi.useRealTimers()
    })
  })

  // ---------- 注册：出生年份 + 协议勾选（2026-10-13 合规改造）----------
  describe('注册必填项', () => {
    const YEAR = new Date().getFullYear()
    const toRegister = async (w) => {
      await w.find('.switch-link a').trigger('click')
      expect(w.find('#register-birthyear').exists()).toBe(true)
    }
    const fill = async (w, { year = '1998', agree = true } = {}) => {
      await w.find('#login-username').setValue('newbie')
      await w.find('#login-password').setValue('Passw0rd!123')
      if (year !== null) await w.find('#register-birthyear').setValue(year)
      if (agree) await w.find('#register-agree').setValue(true)
    }

    it('注册态出现出生年份输入与协议勾选框；协议链接指向 /terms 与 /privacy', async () => {
      const w = mount(Login, { global: { plugins: [router] } })
      await toRegister(w)
      const hrefs = w.findAll('.agree-link').map((a) => a.attributes('href'))
      expect(hrefs).toContain('/terms')
      expect(hrefs).toContain('/privacy')
    })

    it('⛔ 缺出生年份：不发注册请求，并给出可读提示', async () => {
      const w = mount(Login, { global: { plugins: [router] } })
      await toRegister(w)
      await fill(w, { year: null })
      await w.find('form').trigger('submit')
      expect(api.registerApi).not.toHaveBeenCalled()
      expect(w.find('.error-msg').text()).toContain('出生年份')
    })

    it('⛔ 出生年份越界（1899 / 明年）：同样不发请求', async () => {
      for (const bad of ['1899', String(YEAR + 1)]) {
        vi.clearAllMocks()
        const w = mount(Login, { global: { plugins: [router] } })
        await toRegister(w)
        await fill(w, { year: bad })
        await w.find('form').trigger('submit')
        expect(api.registerApi, `year=${bad} 不该发请求`).not.toHaveBeenCalled()
        expect(w.find('.error-msg').text()).toContain('出生年份')
      }
    })

    it('⛔ 未勾选协议：不发注册请求，并提示需同意协议', async () => {
      const w = mount(Login, { global: { plugins: [router] } })
      await toRegister(w)
      await fill(w, { agree: false })
      await w.find('form').trigger('submit')
      expect(api.registerApi).not.toHaveBeenCalled()
      const msg = w.find('.error-msg').text()
      expect(msg).toContain('同意')
      expect(msg).toContain('隐私政策')
    })

    it('勾选 + 填出生年份：调 registerApi(username, password, birthYear) 并把数字传出去', async () => {
      api.registerApi.mockResolvedValue({ data: { token: 'tok', username: 'newbie' } })
      const w = mount(Login, { global: { plugins: [router] } })
      await toRegister(w)
      await fill(w)
      await w.find('form').trigger('submit')
      await flushPromises()

      expect(api.registerApi).toHaveBeenCalledTimes(1)
      expect(api.registerApi).toHaveBeenCalledWith('newbie', 'Passw0rd!123', 1998)
      expect(localStorage.getItem('lwx_ai_token')).toBe('tok')
    })

    it('登录态不受影响：不发 birthYear，也不要求勾选协议', async () => {
      api.loginApi.mockResolvedValue({ data: { token: 'tok2', username: 'u1' } })
      const w = mount(Login, { global: { plugins: [router] } })
      expect(w.find('#register-agree').exists()).toBe(false)
      await w.find('#login-username').setValue('u1')
      await w.find('#login-password').setValue('pw123456')
      await w.find('form').trigger('submit')
      await flushPromises()
      expect(api.loginApi).toHaveBeenCalledWith('u1', 'pw123456')
    })
  })
})
