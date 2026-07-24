const STORAGE_KEY = 'lwx_ai_conversations'

export function getLocalConversations() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
  } catch {
    return []
  }
}

export function saveLocalConversation(id, firstMessage) {
  const list = getLocalConversations().filter(c => c.id !== id)
  list.unshift({ id, firstMessage: firstMessage.substring(0, 50), createdAt: new Date().toISOString() })
  // Keep only the most recent 50 entries
  localStorage.setItem(STORAGE_KEY, JSON.stringify(list.slice(0, 50)))
}

export function removeLocalConversation(id) {
  const list = getLocalConversations().filter(c => c.id !== id)
  localStorage.setItem(STORAGE_KEY, JSON.stringify(list))
}
