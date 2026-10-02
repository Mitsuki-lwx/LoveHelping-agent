import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

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

describe('路由守卫', () => {
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
})
