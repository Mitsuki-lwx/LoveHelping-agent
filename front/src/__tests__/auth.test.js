import { describe, it, expect, beforeEach } from 'vitest'
import { getToken, setToken, removeToken, getUser, setUser, isAuthenticated, isAdmin, getAuthHeaders } from '../utils/auth.js'

describe('auth.js', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('setToken and getToken', () => {
    setToken('test-token')
    expect(getToken()).toBe('test-token')
  })

  it('removeToken clears token and user', () => {
    setToken('t')
    setUser({ username: 'test', role: 'USER' })
    removeToken()
    expect(getToken()).toBeNull()
    expect(getUser()).toBeNull()
  })

  it('setUser and getUser', () => {
    setUser({ username: 'alice', role: 'ADMIN' })
    expect(getUser()).toEqual({ username: 'alice', role: 'ADMIN' })
  })

  it('isAuthenticated returns false when no token', () => {
    expect(isAuthenticated()).toBe(false)
  })

  it('isAuthenticated returns true when token exists', () => {
    setToken('valid-token')
    expect(isAuthenticated()).toBe(true)
  })

  it('isAdmin returns false for regular user', () => {
    setUser({ username: 'u', role: 'USER' })
    expect(isAdmin()).toBe(false)
  })

  it('isAdmin returns true for admin', () => {
    setUser({ username: 'admin', role: 'ADMIN' })
    expect(isAdmin()).toBe(true)
  })

  it('isAdmin returns false when no user', () => {
    expect(isAdmin()).toBe(false)
  })

  it('getUser returns null when no stored user', () => {
    expect(getUser()).toBeNull()
  })
})

  // ── 下面三条补的是"脏数据"路径（此前未覆盖）：localStorage 是用户可改的，
  //    解析失败必须**返回 null 而不是抛**，否则整页初始化就崩了。
  it('getUser 在 localStorage 存了坏 JSON 时返回 null（不抛）', () => {
    localStorage.setItem('lwx_ai_user', '{不是合法JSON')
    expect(() => getUser()).not.toThrow()
    expect(getUser()).toBeNull()
  })

  it('isAdmin 在用户信息损坏时返回 false（不能因为解析失败就当管理员）', () => {
    localStorage.setItem('lwx_ai_user', 'broken')
    expect(isAdmin()).toBe(false)
  })

  it('getAuthHeaders：有 token 带 Bearer，无 token 返回空对象', () => {
    expect(getAuthHeaders()).toEqual({})
    setToken('t1')
    expect(getAuthHeaders()).toEqual({ Authorization: 'Bearer t1' })
  })

