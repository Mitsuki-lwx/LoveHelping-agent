import { describe, it, expect, beforeEach } from 'vitest'
import { getToken, setToken, removeToken, getUser, setUser, isAuthenticated, isAdmin } from '../utils/auth.js'

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
