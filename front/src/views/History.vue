<template>
  <div class="history-page">
    <div class="page-header">
      <button class="back-btn" @click="$router.push('/')">← 返回</button>
      <span class="page-title">对话历史</span>
      <span class="count-badge" v-if="conversations.length">{{ conversations.length }} 条</span>
    </div>

    <div class="tabs">
      <button :class="['tab', activeTab === 'love' ? 'tab-active' : '']" @click="switchTab('love')">💕 恋爱专家</button>
      <button :class="['tab', activeTab === 'manus' ? 'tab-active' : '']" @click="switchTab('manus')">🤖 恋爱全能帮</button>
    </div>

    <div class="content">
      <div v-if="loading" class="loading-state">加载中...</div>

      <div v-else-if="conversations.length === 0" class="empty-state">
        <div class="empty-icon">📝</div>
        <p>暂无 {{ activeTab === 'love' ? '恋爱专家' : '恋爱全能帮' }} 对话记录</p>
      </div>

      <div v-else class="conv-list">
        <div v-for="conv in conversations" :key="conv.conversation_id"
             class="conv-card" @click="continueChat(conv)">
          <div class="conv-info">
            <div class="conv-title">{{ conv.title || '未命名对话' }}</div>
            <div class="conv-meta">
              <span>{{ conv.message_count || 0 }} 条消息</span>
              <span class="sep">·</span>
              <span>{{ formatTime(conv.created_at) }}</span>
            </div>
          </div>
          <div class="conv-actions">
            <button class="action-btn del-btn" title="删除" @click.stop="confirmDelete(conv)">🗑️</button>
          </div>
        </div>
      </div>
    </div>

    <!-- Delete confirm -->
    <div v-if="deleteTarget" class="modal-overlay" @click.self="deleteTarget = null">
      <div class="modal confirm-modal">
        <h3>确认删除</h3>
        <p>删除后不可恢复，确定继续？</p>
        <div class="confirm-actions">
          <button class="cancel-btn" @click="deleteTarget = null">取消</button>
          <button class="delete-btn" @click="doDelete">确认删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listConversations, clearConversation } from '../api/index.js'

const router = useRouter()
const conversations = ref([])
const loading = ref(true)
const activeTab = ref('love')
const deleteTarget = ref(null)

function formatTime(t) {
  if (!t) return ''
  // 处理 ISO 8601 时间戳，转换为本地时区显示
  const d = new Date(t)
  if (isNaN(d.getTime())) return t.substring(0, 19).replace('T', ' ')
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function load() {
  loading.value = true
  try {
    const res = await listConversations(activeTab.value)
    conversations.value = Array.isArray(res.data?.data) ? res.data.data : []
  } catch (e) {
    console.error(e)
    conversations.value = []
  } finally {
    loading.value = false
  }
}

function switchTab(tab) {
  activeTab.value = tab
  load()
}

function continueChat(conv) {
  const path = activeTab.value === 'love' ? '/love-chat' : '/manus-chat'
  router.push(`${path}?sessionId=${encodeURIComponent(conv.conversation_id)}`)
}

function confirmDelete(conv) {
  deleteTarget.value = conv
}

async function doDelete() {
  if (!deleteTarget.value) return
  try {
    await clearConversation(deleteTarget.value.conversation_id)
    conversations.value = conversations.value.filter(c => c.conversation_id !== deleteTarget.value.conversation_id)
  } catch (e) {
    console.error(e)
  } finally {
    deleteTarget.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.history-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-chat);
}

.page-header {
  display: flex; align-items: center; gap: 12px;
  padding: 16px 24px; background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color); flex-shrink: 0;
}

.back-btn {
  background: none; border: none; color: var(--text-secondary);
  font-size: 14px; cursor: pointer; padding: 4px 8px; border-radius: 6px;
}
.back-btn:hover { color: var(--text-white); background: var(--input-bg); }

.page-title { font-size: 16px; font-weight: 600; color: var(--text-white); }
.count-badge { font-size: 12px; color: var(--text-secondary); background: var(--input-bg); padding: 2px 10px; border-radius: 10px; }

.tabs {
  display: flex; gap: 0; padding: 12px 24px 0;
  background: var(--bg-secondary); border-bottom: 1px solid var(--border-color);
}
.tab {
  padding: 8px 20px; border: none; background: none;
  color: var(--text-secondary); font-size: 13px; cursor: pointer;
  border-bottom: 2px solid transparent; transition: all 0.2s;
}
.tab:hover { color: var(--text-white); }
.tab-active { color: var(--accent); border-bottom-color: var(--accent); font-weight: 600; }

.content { flex: 1; overflow-y: auto; padding: 16px 24px; max-width: 700px; width: 100%; margin: 0 auto; }

.loading-state, .empty-state {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  height: 200px; color: var(--text-secondary);
}
.empty-icon { font-size: 48px; margin-bottom: 12px; }

.conv-list { display: flex; flex-direction: column; gap: 6px; }
.conv-card {
  display: flex; align-items: center; justify-content: space-between;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 12px; padding: 14px 16px; cursor: pointer; transition: all 0.2s;
}
.conv-card:hover { border-color: var(--accent); box-shadow: 0 2px 12px rgba(232, 104, 128, 0.08); }

.conv-info { flex: 1; min-width: 0; }
.conv-title { font-size: 14px; color: var(--text-primary); font-weight: 500; margin-bottom: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-meta { font-size: 12px; color: var(--text-secondary); }
.sep { margin: 0 4px; }
.conv-actions { flex-shrink: 0; margin-left: 12px; }
.action-btn {
  background: none; border: 1px solid transparent; cursor: pointer;
  font-size: 16px; padding: 4px 8px; border-radius: 6px; opacity: 0.4;
}
.action-btn:hover { opacity: 1; background: var(--input-bg); }

.modal-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center; z-index: 100;
}
.modal { background: var(--bg-secondary); border-radius: 16px; width: 90%; max-width: 380px; padding: 32px; text-align: center; box-shadow: 0 8px 32px rgba(0,0,0,0.2); }
.modal h3 { font-size: 18px; color: var(--text-white); margin-bottom: 12px; }
.modal p { font-size: 14px; color: var(--text-secondary); margin-bottom: 24px; }
.confirm-actions { display: flex; gap: 12px; justify-content: center; }
.cancel-btn { padding: 8px 20px; border: 1px solid var(--border-color); border-radius: 8px; background: transparent; color: var(--text-primary); cursor: pointer; font-size: 13px; }
.cancel-btn:hover { background: var(--input-bg); }
.delete-btn { padding: 8px 20px; border: none; border-radius: 8px; background: #e74c3c; color: #fff; cursor: pointer; font-size: 13px; }
.delete-btn:hover { background: #c0392b; }
</style>
