<template>
  <div class="admin-page">
    <div class="page-header">
      <button class="back-btn" @click="$router.push('/')">← 返回</button>
      <span class="page-title">管理后台</span>
      <span class="count-badge" v-if="conversations.length">共 {{ conversations.length }} 条对话</span>
      <span class="role-badge admin">管理员</span>
    </div>

    <div class="content">
      <div class="stats-row">
        <div class="stat-card">
          <div class="stat-value">{{ conversations.length }}</div>
          <div class="stat-label">总对话数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ totalMessages }}</div>
          <div class="stat-label">总消息数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ uniqueUsers }}</div>
          <div class="stat-label">用户数</div>
        </div>
      </div>

      <div v-if="loading" class="loading-state">加载中...</div>

      <div v-else-if="conversations.length === 0" class="empty-state">
        <div class="empty-icon">📊</div>
        <p>暂无对话记录</p>
      </div>

      <div v-else class="conv-list">
        <div v-for="conv in conversations" :key="conv.conversation_id"
             class="conv-card" @click="viewConversation(conv)">
          <div class="conv-info">
            <div class="conv-id">{{ conv.conversation_id.substring(0, 8) }}...</div>
            <div class="conv-meta">
              <span>{{ conv.message_count }} 条</span>
              <span class="sep">·</span>
              <span>{{ formatTime(conv.created_at) }}</span>
            </div>
          </div>
          <div class="conv-actions">
            <button class="action-btn" title="查看" @click.stop="viewConversation(conv)">👁️</button>
            <button class="action-btn del-btn" title="删除" @click.stop="confirmDelete(conv)">🗑️</button>
          </div>
        </div>
      </div>
    </div>

    <!-- Detail modal -->
    <div v-if="detailConv" class="modal-overlay" @click.self="detailConv = null">
      <div class="modal">
        <div class="modal-header">
          <h3>对话详情</h3>
          <button class="close-btn" @click="detailConv = null">✕</button>
        </div>
        <div class="modal-body">
          <div v-if="detailLoading">加载中...</div>
          <div v-else-if="detailMessages.length === 0">无消息</div>
          <div v-else class="detail-msg-list">
            <div v-for="msg in detailMessages" :key="msg.id || $index"
                 :class="['detail-msg', msg.messageType === 'USER' ? 'msg-user' : 'msg-ai']">
              <div class="msg-role">{{ msg.messageType === 'USER' ? '你' : 'AI' }}</div>
              <div class="msg-text">{{ truncate(msg.text, 500) }}</div>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <span>{{ detailMessages.length }} 条消息</span>
          <button class="delete-btn" @click="deleteConfirmed">删除此对话</button>
        </div>
      </div>
    </div>

    <!-- Delete confirm -->
    <div v-if="deleteTarget" class="modal-overlay" @click.self="deleteTarget = null">
      <div class="modal confirm-modal">
        <h3>确认删除</h3>
        <p>确定删除此对话？不可恢复。</p>
        <div class="confirm-actions">
          <button class="cancel-btn" @click="deleteTarget = null">取消</button>
          <button class="delete-btn" @click="doDelete">确认删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listAllConversations, getConversationMessages, clearConversation } from '../api/index.js'
import { getUser } from '../utils/auth.js'

const router = useRouter()
const conversations = ref([])
const loading = ref(true)
const detailConv = ref(null)
const detailMessages = ref([])
const detailLoading = ref(false)
const deleteTarget = ref(null)

const totalMessages = computed(() =>
  conversations.value.reduce((s, c) => s + Number(c.message_count || 0), 0)
)
const uniqueUsers = computed(() => new Set(conversations.value.map(c => c.conversation_id)).size)

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return t.substring(0, 19).replace('T', ' ')
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function truncate(text, max) {
  if (!text) return ''
  return text.length > max ? text.substring(0, max) + '...' : text
}

async function load() {
  loading.value = true
  try {
    const res = await listAllConversations()
    conversations.value = Array.isArray(res.data?.data) ? res.data.data : []
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

async function viewConversation(conv) {
  detailConv.value = conv
  detailLoading.value = true
  detailMessages.value = []
  try {
    const res = await getConversationMessages(conv.conversation_id)
    detailMessages.value = Array.isArray(res.data?.data) ? res.data.data : []
  } catch (e) {
    console.error(e)
  } finally {
    detailLoading.value = false
  }
}

function confirmDelete(conv) {
  deleteTarget.value = conv
}

async function doDelete() {
  if (!deleteTarget.value) return
  try {
    await clearConversation(deleteTarget.value.conversation_id)
    conversations.value = conversations.value.filter(c => c.conversation_id !== deleteTarget.value.conversation_id)
    if (detailConv.value?.conversation_id === deleteTarget.value.conversation_id) {
      detailConv.value = null
    }
  } catch (e) {
    console.error(e)
  } finally {
    deleteTarget.value = null
  }
}

onMounted(() => {
  const user = getUser()
  if (!user || user.role !== 'ADMIN') {
    router.push('/')
    return
  }
  load()
})
</script>

<style scoped>
.admin-page {
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

.role-badge {
  font-size: 11px; font-weight: 600; padding: 2px 10px; border-radius: 10px;
  margin-left: auto;
}
.role-badge.admin { background: #e86880; color: #fff; }

.content { flex: 1; overflow-y: auto; padding: 24px; max-width: 800px; width: 100%; margin: 0 auto; }

.stats-row { display: flex; gap: 16px; margin-bottom: 24px; }
.stat-card {
  flex: 1; background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 12px; padding: 20px; text-align: center;
}
.stat-value { font-size: 28px; font-weight: 700; color: var(--accent); }
.stat-label { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }

.loading-state, .empty-state {
  display: flex; align-items: center; justify-content: center;
  height: 200px; color: var(--text-secondary);
}
.empty-icon { font-size: 48px; margin-bottom: 12px; }

.conv-list { display: flex; flex-direction: column; gap: 8px; }
.conv-card {
  display: flex; align-items: center; justify-content: space-between;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 12px; padding: 16px; cursor: pointer; transition: all 0.2s;
}
.conv-card:hover { border-color: var(--accent); }

.conv-info { flex: 1; min-width: 0; }
.conv-id { font-size: 13px; color: var(--text-primary); font-family: monospace; margin-bottom: 4px; }
.conv-meta { font-size: 12px; color: var(--text-secondary); }
.sep { margin: 0 4px; }
.conv-actions { display: flex; gap: 4px; flex-shrink: 0; margin-left: 12px; }
.action-btn {
  background: none; border: 1px solid transparent; cursor: pointer;
  font-size: 16px; padding: 4px 8px; border-radius: 6px; opacity: 0.5;
}
.action-btn:hover { opacity: 1; background: var(--input-bg); }

.modal-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center; z-index: 100;
}
.modal {
  background: var(--bg-secondary); border-radius: 16px; width: 90%;
  max-width: 560px; max-height: 80vh; display: flex; flex-direction: column;
  box-shadow: 0 8px 32px rgba(0,0,0,0.2);
}
.confirm-modal { max-width: 400px; padding: 32px; text-align: center; }
.confirm-modal h3 { font-size: 18px; color: var(--text-white); margin-bottom: 12px; }
.confirm-modal p { font-size: 14px; color: var(--text-secondary); margin-bottom: 24px; }
.confirm-actions { display: flex; gap: 12px; justify-content: center; }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px 0; }
.modal-header h3 { font-size: 16px; color: var(--text-white); }
.close-btn { background: none; border: none; color: var(--text-secondary); font-size: 18px; cursor: pointer; padding: 4px 8px; border-radius: 6px; }
.close-btn:hover { background: var(--input-bg); }
.modal-body { flex: 1; overflow-y: auto; padding: 16px 24px; }
.detail-msg-list { display: flex; flex-direction: column; gap: 8px; }
.detail-msg { padding: 10px 14px; border-radius: 10px; font-size: 13px; line-height: 1.5; }
.msg-user { background: rgba(232, 104, 128, 0.08); }
.msg-ai { background: var(--input-bg); }
.msg-role { font-size: 11px; font-weight: 600; color: var(--accent); margin-bottom: 4px; }
.msg-text { color: var(--text-primary); word-break: break-word; white-space: pre-wrap; }
.modal-footer { display: flex; align-items: center; justify-content: space-between; padding: 12px 24px 20px; border-top: 1px solid var(--border-color); }
.cancel-btn { padding: 8px 20px; border: 1px solid var(--border-color); border-radius: 8px; background: transparent; color: var(--text-primary); cursor: pointer; font-size: 13px; }
.cancel-btn:hover { background: var(--input-bg); }
.delete-btn { padding: 8px 20px; border: none; border-radius: 8px; background: #e74c3c; color: #fff; cursor: pointer; font-size: 13px; }
.delete-btn:hover { background: #c0392b; }
</style>
