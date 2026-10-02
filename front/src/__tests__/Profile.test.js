import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Profile from '../views/Profile.vue'
import { getUser, setUser, setToken } from '../utils/auth.js'

/**
 * 个人中心（Profile.vue）的交互契约。
 *
 * 这个页面的风险集中在「用户改自己的数据」上：
 *   1) 头像/加入时间是**纯展示计算**，一旦回退逻辑写错，用户看到的是空白或 undefined；
 *   2) 保存资料要写后端 + 同步 localStorage（顶部导航读它），失败必须让用户看出来；
 *   3) 改密码/注销是**不可逆操作**，本地校验漏了会白跑请求，二次确认漏了会误删账号。
 *
 * ⛔ 所以下面每条都断言**用户可见结果**（气泡文案、按钮 disabled、输入框是否被清空、
 *    localStorage 里到底存了什么），而不是「mock 返回 success 就断言 success」。
 */

// ⛔ vi.mock 回调会被提升到文件顶部，不能引用普通顶层变量 —— 用 vi.hoisted 让 api/routerPush 先存在。
const { api, routerPush } = vi.hoisted(() => ({
  api: {
    getMe: vi.fn(),
    updateProfileApi: vi.fn(),
    changePasswordApi: vi.fn(),
    deleteAccountApi: vi.fn(),
  },
  routerPush: vi.fn(),
}))

vi.mock('../api/index.js', () => api)
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush }),
  useRoute: () => ({ query: {} }),
}))

/** 后端响应形状：axios 响应体 `.data`，视图里再取业务字段（`(await x()).data.success`）。 */
const ok = (payload = {}) => ({ data: { success: true, ...payload } })
const fail = (message) => ({ data: { success: false, message } })
const boom = (message) => Object.assign(new Error(message), { response: { data: { message } } })

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const mountView = () => mount(Profile, { global: { stubs: { RouterLink: true } } })

const avatarText = (w) => w.find('.avatar-ring span').text()
const nick = (w) => w.find('.nick').text()
const joinedLine = (w) => w.find('.joined-line').text()
const saveBtn = (w) => w.find('.profile-main .save-btn')
const saveMsg = (w) => w.find('.profile-main .form-msg')
const pwdSection = (w) => w.findAll('.profile-side > section')[0]
const delSection = (w) => w.findAll('.profile-side > section')[1]
const pwdInput = (w, placeholder) => pwdSection(w).find(`input[placeholder="${placeholder}"]`)
const isDisabled = (btn) => btn.attributes('disabled') !== undefined

describe('Profile.vue', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    // 默认：会话有效但后端没给资料 —— 只测本地回退路径的用例不受 onMounted 干扰。
    api.getMe.mockResolvedValue({ data: { success: false } })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('头像回退：有 avatarEmoji 用它，没有则用用户名首字母大写', async () => {
    setToken('t')
    setUser({ username: 'alice', role: 'USER' })
    api.getMe.mockResolvedValue(ok({ username: 'alice', nickname: '', avatarEmoji: '', bio: '' }))
    const w = mountView()
    await flushPromises()
    // 回退分支：没有 emoji ⇒ 必须取用户名首字母并大写（写成原样小写 'a' 就是 bug）。
    expect(avatarText(w)).toBe('A')

    // 从选择盘点一个 emoji：既是「有 avatarEmoji 用它」的契约，也校验选完自动收面板。
    await w.find('.avatar-ring').trigger('click')
    expect(w.find('.emoji-picker').exists()).toBe(true)
    await w.findAll('.emoji-opt')[1].trigger('click')
    expect(avatarText(w)).toBe('🌙')
    expect(w.find('.emoji-picker').exists()).toBe(false)
  })

  it('头像回退：用户名缺失时显示 ?，不会渲染出 undefined', async () => {
    const w = mountView()
    await flushPromises()
    // 无 token、无缓存、后端 success=false：profile.username 为空，回退必须是 '?'。
    expect(avatarText(w)).toBe('?')
    expect(avatarText(w)).not.toMatch(/undefined|null/i)
  })

  it('加入时间只取日期部分，缺失时降级为 —', async () => {
    setUser({ username: 'bob', role: 'USER' })
    api.getMe.mockResolvedValue(ok({ username: 'bob', createdAt: '2026-01-02T03:04:05' }))
    const dated = mountView()
    await flushPromises()
    // 后端给的是 LocalDateTime.toString()，长这样：截到 10 位才是用户能读的日期。
    expect(joinedLine(dated)).toBe('加入于 2026-01-02')

    api.getMe.mockResolvedValue(ok({ username: 'bob', createdAt: null }))
    const noDate = mountView()
    await flushPromises()
    expect(joinedLine(noDate)).toBe('加入于 —')
  })

  it('createdAt 是异常类型时只降级这一行，不拖垮整张资料卡', async () => {
    api.getMe.mockResolvedValue(ok({ username: 'bob', createdAt: 1700000000000 }))
    const w = mountView()
    await flushPromises()
    // 契约：计算属性不能抛（抛了整个 profile-main 白屏）。异常值允许显示难看，但页面其余部分必须还在。
    expect(nick(w)).toBe('bob')
    expect(saveBtn(w).exists()).toBe(true)
    expect(joinedLine(w)).toContain('加入于')
  })

  it('onMounted 用后端资料覆盖本地缓存并填进表单', async () => {
    setUser({ username: 'bob', nickname: '旧昵称' })
    api.getMe.mockResolvedValue(ok({
      username: 'bob', role: 'USER', nickname: '新昵称', avatarEmoji: '🐰', bio: '四个字啊',
    }))
    const w = mountView()
    // 关键：onMounted 是异步的，不等 flushPromises 就断言会读到「尚未回填」的状态。
    // 未回填时 form.nickname 为空，标题回退成 @username（不会拿缓存里的旧昵称冒充）。
    expect(nick(w)).toBe('bob')
    await flushPromises()
    expect(nick(w)).toBe('新昵称')
    expect(avatarText(w)).toBe('🐰')
    expect(w.find('.profile-main textarea').element.value).toBe('四个字啊')
    // 计数器必须跟着远端数据走（写死 0/200 就是 bug）。
    expect(w.find('.counter').text()).toBe('4/200')
  })

  it('保存中按钮禁用并显示 保存中…；成功后提示不带 err 且头像昵称写回本地缓存', async () => {
    setUser({ username: 'bob', role: 'USER' })
    const gate = deferred()
    api.updateProfileApi.mockReturnValue(gate.promise)
    const updates = vi.fn()
    window.addEventListener('lh:user-updated', updates)

    const w = mountView()
    await flushPromises()
    await w.find('.profile-main input').setValue('  小含  ')
    await w.find('.profile-main textarea').setValue('  hi  ')
    await saveBtn(w).trigger('click')

    // 挂起期间：按钮必须锁死，否则用户会连点发出多次请求。
    expect(isDisabled(saveBtn(w))).toBe(true)
    expect(saveBtn(w).text()).toContain('保存中…')

    gate.resolve(ok({ message: '资料已更新' }))
    await flushPromises()

    expect(isDisabled(saveBtn(w))).toBe(false)
    expect(saveBtn(w).text()).toContain('保存修改')
    expect(saveMsg(w).text()).toBe('资料已更新')
    expect(saveMsg(w).classes('err')).toBe(false)
    // 写回本地缓存的必须是 trim 后的值（顶部导航直接读它，带空格会显示成「  小含  」）。
    expect(getUser().nickname).toBe('小含')
    expect(getUser().bio).toBe('hi')
    expect(getUser().username).toBe('bob')
    expect(updates).toHaveBeenCalledTimes(1)
    window.removeEventListener('lh:user-updated', updates)
  })

  it('保存返回 success=false：提示标红、不发广播、不污染本地缓存', async () => {
    setUser({ username: 'bob', role: 'USER', nickname: '旧' })
    api.updateProfileApi.mockResolvedValue(fail('昵称已被占用'))
    const updates = vi.fn()
    window.addEventListener('lh:user-updated', updates)

    const w = mountView()
    await flushPromises()
    await saveBtn(w).trigger('click')
    await flushPromises()

    expect(saveMsg(w).text()).toBe('昵称已被占用')
    expect(saveMsg(w).classes('err')).toBe(true)
    expect(isDisabled(saveBtn(w))).toBe(false)
    // 失败还广播 lh:user-updated 会让顶部导航显示一个根本没保存成功的昵称。
    expect(updates).not.toHaveBeenCalled()
    expect(getUser().nickname).toBe('旧')
    window.removeEventListener('lh:user-updated', updates)
  })

  it('保存请求异常：把后端 message 变成可见错误并让 saving 收敛', async () => {
    api.updateProfileApi.mockRejectedValue(boom('网关超时'))
    const w = mountView()
    await flushPromises()
    await saveBtn(w).trigger('click')
    await flushPromises()

    expect(saveMsg(w).text()).toBe('网关超时')
    expect(saveMsg(w).classes('err')).toBe(true)
    // finally 必须复位，否则按钮永远停在「保存中…」。
    expect(isDisabled(saveBtn(w))).toBe(false)
  })

  it('改密码本地校验：为空 / 不足 8 位 / 两次不一致 一律不发请求', async () => {
    const w = mountView()
    await flushPromises()
    const submit = pwdSection(w).find('.save-btn')
    const msg = () => pwdSection(w).find('.form-msg')

    // 空：必须能区分「没填」和「填错」，不能默默提交空密码。
    await submit.trigger('click')
    expect(msg().text()).toBe('请填写当前密码与新密码')
    expect(msg().classes('err')).toBe(true)

    // 太短：8 位下限是后端/前端的共同契约，本地拦下能省一次必然失败的请求。
    await pwdInput(w, '当前密码').setValue('oldpass1')
    await pwdInput(w, '新密码（至少 8 位）').setValue('short')
    await pwdInput(w, '再输一遍新密码').setValue('short')
    await submit.trigger('click')
    expect(msg().text()).toBe('新密码至少 8 位')

    // 不一致：这是最容易把用户锁在账号外的错，必须在本地就拦住。
    await pwdInput(w, '新密码（至少 8 位）').setValue('longenough')
    await pwdInput(w, '再输一遍新密码').setValue('different!')
    await submit.trigger('click')
    expect(msg().text()).toBe('两次输入的新密码不一致')

    expect(api.changePasswordApi).not.toHaveBeenCalled()
  })

  it('改密码成功：清空三个输入框，1.4s 后清 token 并跳登录页', async () => {
    vi.useFakeTimers()
    setToken('t')
    api.changePasswordApi.mockResolvedValue(ok({ message: '密码已修改，请重新登录' }))
    const w = mountView()
    await flushPromises()
    await pwdInput(w, '当前密码').setValue('oldpass1')
    await pwdInput(w, '新密码（至少 8 位）').setValue('newpass1')
    await pwdInput(w, '再输一遍新密码').setValue('newpass1')
    await pwdSection(w).find('.save-btn').trigger('click')
    await flushPromises()

    // 明文密码不能留在 DOM/内存里等人来瞟。
    expect(pwdInput(w, '当前密码').element.value).toBe('')
    expect(pwdInput(w, '新密码（至少 8 位）').element.value).toBe('')
    expect(pwdInput(w, '再输一遍新密码').element.value).toBe('')
    expect(pwdSection(w).find('.form-msg').classes('err')).toBe(false)
    // 提示语要留够时间给用户读完，1.4s 之内不该把他踢走。
    expect(localStorage.getItem('lwx_ai_token')).toBe('t')
    await vi.advanceTimersByTimeAsync(1400)
    expect(localStorage.getItem('lwx_ai_token')).toBeNull()
    expect(routerPush).toHaveBeenCalledWith('/login')
  })

  it('改密码失败：提示标红、输入保留、按钮恢复可用', async () => {
    api.changePasswordApi.mockResolvedValue(fail('当前密码不正确'))
    const w = mountView()
    await flushPromises()
    await pwdInput(w, '当前密码').setValue('wrongold')
    await pwdInput(w, '新密码（至少 8 位）').setValue('newpass1')
    await pwdInput(w, '再输一遍新密码').setValue('newpass1')
    await pwdSection(w).find('.save-btn').trigger('click')
    await flushPromises()

    expect(pwdSection(w).find('.form-msg').text()).toBe('当前密码不正确')
    expect(pwdSection(w).find('.form-msg').classes('err')).toBe(true)
    // 失败时清空输入 = 逼用户重敲两遍新密码，体验事故；这里钉住「不清空」。
    expect(pwdInput(w, '当前密码').element.value).toBe('wrongold')
    expect(isDisabled(pwdSection(w).find('.save-btn'))).toBe(false)
  })

  it('注销必须二次确认：首次点击只展开确认区，点「再想想」可安全退出', async () => {
    const w = mountView()
    await flushPromises()
    const danger = delSection(w)

    await danger.find('.btn-danger').trigger('click')
    await flushPromises()
    // 第一下绝不能删数据 —— 这是不可逆操作，误触代价是整个账号。
    expect(api.deleteAccountApi).not.toHaveBeenCalled()
    expect(danger.text()).toContain('真的要全部抹去吗？')

    await danger.find('.btn-cancel').trigger('click')
    expect(api.deleteAccountApi).not.toHaveBeenCalled()
    expect(delSection(w).text()).toContain('注销我的账号')
    // 取消后不该残留警告文案，否则用户会以为还在确认流程里。
    expect(delSection(w).text()).not.toContain('真的要全部抹去吗？')
  })

  it('确认注销：请求中按钮禁用且显示 注销中…，失败给出可读反馈并解禁', async () => {
    const gate = deferred()
    api.deleteAccountApi.mockReturnValue(gate.promise)
    const w = mountView()
    await flushPromises()
    await delSection(w).find('.btn-danger').trigger('click')
    const confirmBtn = () => delSection(w).find('.danger-actions .btn-danger')
    await confirmBtn().trigger('click')

    expect(isDisabled(confirmBtn())).toBe(true)
    expect(confirmBtn().text()).toContain('注销中…')

    gate.resolve(fail('账号已被禁用，无法注销'))
    await flushPromises()
    expect(delSection(w).find('.form-msg').text()).toBe('账号已被禁用，无法注销')
    expect(delSection(w).find('.form-msg').classes('err')).toBe(true)
    expect(isDisabled(confirmBtn())).toBe(false)
    // 失败绝不能顺手清 token 把人踢下线。
    expect(routerPush).not.toHaveBeenCalled()
  })

  it('注销成功：清 token 并跳转登录页', async () => {
    setToken('t')
    api.deleteAccountApi.mockResolvedValue(ok())
    const w = mountView()
    await flushPromises()
    await delSection(w).find('.btn-danger').trigger('click')
    await delSection(w).find('.danger-actions .btn-danger').trigger('click')
    await flushPromises()

    expect(localStorage.getItem('lwx_ai_token')).toBeNull()
    expect(routerPush).toHaveBeenCalledWith('/login')
  })
})