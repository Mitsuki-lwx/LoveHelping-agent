import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

/**
 * ⛔ 这个文件测的是**路由守卫**，不是页面渲染 —— 所以把视图组件 stub 掉。
 *   为什么必须 stub（2026-10-06 实测）：本文件每条用例都 `vi.resetModules()` 重开模块图，
 *   而视图现在 import 了图标库（`@phosphor-icons/vue` 的 barrel 很大）⇒
 *   第一条**真正加载视图**的用例（"已登录 → 可进入受保护页"推 /history）冷编译超过 5s 默认超时，
 *   红出来的样子和"守卫坏了"**一模一样**（实测 6092ms 超时）。
 *   stub 之后测的还是同一条契约（能不能进），但不再为无关的编译买单。
 *   ⚠️ 本文件从头到尾只断言 `currentRoute.path`，不碰组件 —— 所以 stub 不削弱任何断言。
 */
for (const name of ['Login', 'Home', 'LoveChat', 'Sandbox', 'MemoryArchive', 'Profile', 'History', 'Admin', 'NotFound', 'Terms', 'Privacy', 'Report']) {
  vi.mock(`../views/${name}.vue`, () => ({ default: { name: `Stub${name}`, template: '<div />' } }))
}

/**
 * 路由守卫（此前 0% 覆盖）—— 它是**前端的第一道门**。
 *
 * ⛔ 为什么值得测：守卫坏掉有两种后果，且都**不报错**：
 *   1. `requiresAuth` 失效 → 未登录用户直接进入受保护页面，看到满屏空白/报错（体验崩）；
 *   2. `requiresAdmin` 失效 → 普通用户能打开管理端路由（**越权面**，虽然后端也会拦，
 *      但前端不该把入口给出去）。
 * 这两条一旦被重构改回去，手测很容易漏（毕竟"页面还能打开"）。
 */
async function freshRouter() {
  vi.resetModules()                      // 每条测试用干净的路由实例与守卫
  const mod = await import('../router/index.js')
  return mod.default
}

/**
 * ⛔ 显式放宽超时（2026-10-06 实测）：本文件每条用例都 `vi.resetModules()` 重开模块图并动态
 *   import 路由 —— 单跑 9 条 4.4s，但**全量并行**（16 个文件同时 transform）时会撞破默认 5s，
 *   红出来的样子和"守卫坏了"一模一样（实测 5671ms 超时，单独跑却全绿）。
 *   放宽的只是**等待上限**，断言一条没动 —— 这是"承认它慢"，不是"放过它"。
 */
describe('路由守卫', { timeout: 20000 }, () => {
  beforeEach(() => {
    localStorage.clear()
  })
  afterEach(() => {
    localStorage.clear()
    vi.resetModules()
  })

  it('未登录访问受保护页 → 重定向到 /login', async () => {
    const router = await freshRouter()
    await router.push('/love-chat')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('未登录访问首页 / → 也重定向到 /login（首页本身 requiresAuth）', async () => {
    const router = await freshRouter()
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('/login 本身不需要鉴权（否则会死循环）', async () => {
    const router = await freshRouter()
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('已登录 → 可进入受保护页', async () => {
    localStorage.setItem('lwx_ai_token', 'tok')
    localStorage.setItem('lwx_ai_user', JSON.stringify({ username: 'u1', role: 'USER' }))
    const router = await freshRouter()
    await router.push('/history')
    expect(router.currentRoute.value.path).toBe('/history')
  })

  it('⛔ 普通用户访问 /admin → 被挡回首页（不能把管理入口给出去）', async () => {
    localStorage.setItem('lwx_ai_token', 'tok')
    localStorage.setItem('lwx_ai_user', JSON.stringify({ username: 'u1', role: 'USER' }))
    const router = await freshRouter()
    await router.push('/admin')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('管理员访问 /admin → 放行', async () => {
    localStorage.setItem('lwx_ai_token', 'tok')
    localStorage.setItem('lwx_ai_user', JSON.stringify({ username: 'boss', role: 'ADMIN' }))
    const router = await freshRouter()
    await router.push('/admin')
    expect(router.currentRoute.value.path).toBe('/admin')
  })

  it('⛔ 有 token 但用户信息缺失 → 仍不能进 /admin（不能因为"缺数据"而放行）', async () => {
    localStorage.setItem('lwx_ai_token', 'tok')     // 故意不写 user
    const router = await freshRouter()
    await router.push('/admin')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('旧路径 /manus-chat 重定向到 /love-chat（已登录时）', async () => {
    localStorage.setItem('lwx_ai_token', 'tok')
    localStorage.setItem('lwx_ai_user', JSON.stringify({ username: 'u1', role: 'USER' }))
    const router = await freshRouter()
    await router.push('/manus-chat')
    expect(router.currentRoute.value.path).toBe('/love-chat')
  })

  it('未登录访问旧路径 /manus-chat：重定向后仍要被守卫拦到 /login', async () => {
    const router = await freshRouter()
    await router.push('/manus-chat')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  // ---------- 合规面路由（2026-10-13）----------
  it('⛔ 未登录也能打开法务页 /terms 与 /privacy（注册前必须能读到协议）', async () => {
    for (const path of ['/terms', '/privacy']) {
      const router = await freshRouter()
      await router.push(path)
      expect(router.currentRoute.value.path, `${path} 不该被登录墙挡住`).toBe(path)
    }
  })

  it('投诉举报 /report 需要登录：未登录 → /login（举报要归属到具体账号）', async () => {
    const router = await freshRouter()
    await router.push('/report')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('已登录可进入 /report', async () => {
    localStorage.setItem('lwx_ai_token', 'tok')
    localStorage.setItem('lwx_ai_user', JSON.stringify({ username: 'u1', role: 'USER' }))
    const router = await freshRouter()
    await router.push('/report')
    expect(router.currentRoute.value.path).toBe('/report')
  })
})
