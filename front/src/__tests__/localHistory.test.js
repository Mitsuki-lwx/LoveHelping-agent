import { describe, it, expect, beforeEach } from 'vitest'
import { getLocalConversations, saveLocalConversation, removeLocalConversation } from '../utils/history.js'

describe('history.js', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('getLocalConversations returns empty array initially', () => {
    expect(getLocalConversations()).toEqual([])
  })

  it('saveLocalConversation adds a conversation', () => {
    saveLocalConversation('conv-1', 'hello world')
    const list = getLocalConversations()
    expect(list).toHaveLength(1)
    expect(list[0].id).toBe('conv-1')
    expect(list[0].firstMessage).toBe('hello world')
  })

  it('saveLocalConversation keeps only last 50', () => {
    for (let i = 0; i < 60; i++) {
      saveLocalConversation(`conv-${i}`, `msg ${i}`)
    }
    expect(getLocalConversations()).toHaveLength(50)
    expect(getLocalConversations()[0].id).toBe('conv-59')
  })

  it('saveLocalConversation updates existing id', () => {
    saveLocalConversation('conv-1', 'first')
    saveLocalConversation('conv-2', 'second')
    saveLocalConversation('conv-1', 'updated')
    const list = getLocalConversations()
    expect(list).toHaveLength(2)
    expect(list[0].firstMessage).toBe('updated')
  })

  it('removeLocalConversation removes by id', () => {
    saveLocalConversation('a', 'aaa')
    saveLocalConversation('b', 'bbb')
    removeLocalConversation('a')
    expect(getLocalConversations()).toHaveLength(1)
    expect(getLocalConversations()[0].id).toBe('b')
  })

  it('removeLocalConversation does nothing for unknown id', () => {
    saveLocalConversation('a', 'aaa')
    removeLocalConversation('unknown')
    expect(getLocalConversations()).toHaveLength(1)
  })

  it('saveLocalConversation truncates firstMessage to 50 chars', () => {
    const long = 'a'.repeat(100)
    saveLocalConversation('conv-1', long)
    expect(getLocalConversations()[0].firstMessage).toHaveLength(50)
  })
})
