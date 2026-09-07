<template>
  <div class="history-page">
    <div class="page-header">
      <button class="back-btn" @click="$router.push('/')">←</button>
      <span class="page-title hand">对话历史 · 旧信存档</span>
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
  // 2026-09-07：LoveManus 通道合并进统一聊天——历史里的 agent 会话也在解忧信箱继续
  router.push(`/love-chat?sessionId=${encodeURIComponent(conv.conversation_id)}`)
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
  background: var(--paper);
}
.page-header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 22px 14px;
  flex-shrink: 0;
  border-bottom: 1.5px solid var(--ink-line);
  background: linear-gradient(180deg, oklch(99% 0.008 78), var(--paper));
}
.back-btn {
  font-family: var(--font-hand);
  font-size: 20px;
  color: var(--ink-soft);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 50%;
}
.back-btn:hover { background: var(--paper-deep); transform: translateX(-2px); }
.page-title { font-size: 21px; letter-spacing: 0.12em; flex: 1; }
.count-badge {
  font-family: var(--font-hand);
  font-size: 12px;
  color: var(--wine-deep);
  background: var(--wine-soft);
  border: 1px dashed var(--wine);
  border-radius: 12px;
  padding: 3px 10px;
  transform: rotate(-2deg);
}
.tabs {
  display: flex;
  gap: 8px;
  padding: 12px 22px 0;
  flex-shrink: 0;
}
.tab {
  font-family: var(--font-hand);
  font-size: 14px;
  letter-spacing: 0.06em;
  color: var(--ink-soft);
  background: transparent;
  border: 1.4px solid var(--ink-line);
  border-bottom: none;
  border-radius: 12px 12px 0 0;
  padding: 8px 18px;
  cursor: pointer;
  transition: all 0.18s;
  transform: translateY(1px);
}
.tab:hover { color: var(--wine-deep); }
.tab-active {
  background: var(--paper-card);
  border-color: var(--wine);
  color: var(--wine-deep);
  font-weight: 700;
  transform: translateY(0);
}
.content { flex: 1; overflow-y: auto; padding: 18px 22px 24px; }
.loading-state, .empty-state { text-align: center; padding: 60px 0; color: var(--ink-faint); }
.empty-icon { font-size: 40px; margin-bottom: 10px; }
.empty-state p { font-family: var(--font-hand); font-size: 15px; }
.conv-list { display: flex; flex-direction: column; gap: 12px; }
.conv-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  background: var(--paper-card);
  border: 1px solid var(--ink-line);
  border-left: 3px solid var(--wine);
  border-radius: 8px;
  padding: 15px 18px;
  cursor: pointer;
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1), box-shadow 0.2s, border-color 0.2s;
  animation: letter-in 0.4s cubic-bezier(0.16, 1, 0.3, 1) both;
}
.conv-card:hover {
  transform: translateX(3px);
  border-color: var(--wine);
  box-shadow: 0 10px 22px -14px oklch(40% 0.1 25 / 0.4);
}
.conv-title { font-size: 15px; font-weight: 700; color: var(--ink); margin-bottom: 5px; }
.conv-meta { font-size: 12px; color: var(--ink-faint); font-family: var(--font-hand); letter-spacing: 0.04em; }
.sep { margin: 0 6px; }
.conv-actions { flex-shrink: 0; }
.action-btn {
  background: transparent;
  border: none;
  font-size: 16px;
  cursor: pointer;
  padding: 6px;
  border-radius: 50%;
  opacity: 0.55;
  transition: opacity 0.15s, transform 0.15s;
}
.action-btn:hover { opacity: 1; transform: scale(1.12); }
/* 删除确认弹层 */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: oklch(28% 0.02 62 / 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
  backdrop-filter: blur(2px);
}
.modal {
  background: var(--paper-card);
  border: 1px solid var(--ink-line);
  border-radius: 8px;
  padding: 26px 30px;
  width: min(340px, 90%);
  animation: letter-in 0.3s cubic-bezier(0.16, 1, 0.3, 1) both;
}
.modal h3 { font-family: var(--font-hand); font-size: 19px; letter-spacing: 0.1em; margin-bottom: 10px; color: var(--ink); }
.modal p { font-size: 14px; color: var(--ink-soft); margin-bottom: 20px; }
.confirm-actions { display: flex; gap: 10px; justify-content: flex-end; }
.confirm-btn {
  font-family: var(--font-hand);
  font-size: 14px;
  letter-spacing: 0.1em;
  border-radius: 14px 10px 13px 9px;
  padding: 8px 18px;
  cursor: pointer;
  border: 1.4px solid var(--ink-line);
  background: var(--paper-card);
  color: var(--ink);
  transition: transform 0.15s, border-color 0.15s;
}
.confirm-btn:hover { transform: translateY(-1px); border-color: var(--wine); color: var(--wine-deep); }
.confirm-btn.danger { background: var(--wine); border-color: var(--wine); color: oklch(98% 0.012 78); }
.confirm-btn.danger:hover { background: var(--wine-deep); }
.cancel-btn:hover { transform: translateY(-1px); }
</style>
