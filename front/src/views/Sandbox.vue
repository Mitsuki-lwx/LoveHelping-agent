<template>
  <div class="sandbox-page">
    <div class="sb-header">
      <button class="back-btn" @click="goHome">←</button>
      <div class="letterhead">
        <span class="letterhead-stamp anim-stamp">演</span>
        <div class="letterhead-titles">
          <h1 class="letterhead-title hand">角色模拟屋</h1>
          <span class="letterhead-sub">— 把想说的话，先和"TA"练一遍 —</span>
        </div>
      </div>
      <button class="new-chat-btn btn-hand" @click="openCreate">✎ 新模拟</button>
    </div>

    <div class="sb-body">
      <aside class="sb-side">
        <p class="sb-side-title hand">我的排练</p>
        <div v-if="!sessions.length" class="sb-side-empty">还没有模拟记录<br>点"新模拟"挑个人设开始</div>
        <div v-else class="sb-session-list">
          <div
            v-for="s in sessions" :key="s.id"
            :class="['sb-session', s.id === currentId ? 'sb-session-active' : '']"
            @click="openSession(s.id)"
          >
            <span class="sb-session-wax">{{ initialOf(s.personaName) }}</span>
            <div class="sb-session-meta">
              <div class="sb-session-name">{{ s.personaName }}</div>
              <div class="sb-session-time">{{ shortDate(s.createdAt) }}</div>
            </div>
          </div>
        </div>
      </aside>

      <main class="sb-main">
        <div v-if="!currentId && showCreate === false" class="sb-welcome">
          <div class="sb-welcome-head">
            <span class="sb-welcome-emoji">🎭</span>
            <p class="hand sb-welcome-title">这一场，你想遇见谁？</p>
            <p class="sb-welcome-sub">预置人设一键开场；也可以自定义一个"TA"的性格，重演心里那场对话。</p>
          </div>
          <div class="persona-grid">
            <div v-for="p in personas" :key="p.id" class="persona-card letter-card anim-letter-in" @click="startWithPersona(p)">
              <span class="persona-avatar">{{ (p.name || '他')[0] }}</span>
              <h3 class="hand persona-name">{{ p.name }}</h3>
              <p class="persona-arche">{{ p.archetype || '相处练习' }}</p>
              <p v-if="personaLine(p)" class="persona-line">“{{ personaLine(p) }}”</p>
              <span class="persona-go">开场 →</span>
            </div>
            <div class="persona-card persona-custom letter-card anim-letter-in" @click="openCreate">
              <span class="persona-avatar persona-avatar-custom">✎</span>
              <h3 class="hand persona-name">自定义 TA</h3>
              <p class="persona-arche">写下性格，我来扮演</p>
              <span class="persona-go">自定义 →</span>
            </div>
          </div>
        </div>

        <div v-else-if="showCreate" class="sb-custom letter-card fold anim-letter-in">
          <p class="hand sb-custom-title">写下你想排练的"TA"</p>
          <label class="sb-field-label">TA 的性格 / 说话方式（越具体越像）</label>
          <textarea v-model="customTraits" class="sb-input sb-textarea" rows="3"
            placeholder="例如：慢热但嘴硬，生气时爱用反问句，吃软不吃硬……"></textarea>
          <label class="sb-field-label">你们的关系阶段</label>
          <input v-model="relationshipStage" class="sb-input" placeholder="例如：刚在一起三个月 / 冷战了一周……" />
          <div class="sb-custom-actions">
            <button class="btn-hand" @click="showCreate = false; customTraits=''; relationshipStage=''">再想想</button>
            <button class="btn-hand primary" :disabled="!customTraits.trim()" @click="startCustom">开演</button>
          </div>
        </div>

        <div v-else-if="currentSession" class="sb-chat">
          <div class="sb-chat-top">
            <div>
              <span class="sb-chat-wax">{{ initialOf(currentSession.personaName) }}</span>
              <span class="hand sb-chat-name">{{ currentSession.personaName }}</span>
            </div>
            <div class="sb-chat-tools">
              <button class="sb-tool" @click="resetSession">⟲ 重来</button>
              <button class="sb-tool" :disabled="reviewing" @click="runReview">{{ reviewing ? '复盘中…' : '📋 复盘' }}</button>
              <button class="sb-tool" @click="toggleMemories">📒 记忆</button>
              <button class="sb-tool danger" @click="removeSession">🗑 删除</button>
            </div>
          </div>

          <div class="sb-messages" ref="msgRef">
            <div v-for="(m, idx) in messages" :key="idx" :class="['sb-msg anim-letter-in', m.role === 'user' ? 'sb-msg-user' : 'sb-msg-ai']">
              <div class="sb-msg-content">{{ m.text }}</div>
            </div>
            <div v-if="loading" class="sb-msg sb-msg-ai">
              <div class="sb-msg-content typing-dots"><span class="dot">.</span><span class="dot">.</span><span class="dot">.</span></div>
            </div>
          </div>

          <div class="sb-input-bar">
            <input v-model="inputText" class="sb-chat-input" placeholder="跟 TA 说点什么……"
              @keydown.enter="send" :disabled="loading" />
            <button class="send-btn" @click="send" :disabled="loading || !inputText.trim()">说</button>
          </div>
        </div>
      </main>
    </div>

      <!-- ③ 演练复盘卡（2026-09-08） -->
      <div v-if="reviewResult" class="sb-review letter-card anim-letter-in">
        <div class="sb-review-head">
          <span class="sb-review-title">📋 演练复盘</span>
          <button class="sb-tool" @click="reviewResult = null">✕</button>
        </div>
        <p class="sb-review-summary">{{ reviewResult.summary }}</p>
        <p v-if="reviewResult.good" class="sb-review-line good">✅ {{ reviewResult.good }}</p>
        <p v-if="reviewResult.risk" class="sb-review-line risk">⚠️ {{ reviewResult.risk }}</p>
        <p v-if="reviewResult.better" class="sb-review-line better">💬 试试这么说：{{ reviewResult.better }}</p>
        <p class="sb-review-note">复盘基于这场演练的对话——练完看一眼，下次开口更有底。</p>
      </div>
    <div v-if="memOpen && currentSession" class="sb-mem-drawer letter-card anim-letter-in">
      <div class="sb-mem-head">
        <span class="hand">TA 记住了什么</span>
        <button class="sb-tool" @click="memOpen = false">✕</button>
      </div>
      <div v-if="!memories.length" class="sb-mem-empty">还没有记忆。聊完让他记下重要的事，下次他会记得。</div>
      <ul class="sb-mem-list">
        <li v-for="m in memories" :key="m.id" class="sb-mem-item">
          <span class="sb-mem-type">{{ typeName(m.type) }}</span>
          <span class="sb-mem-text">{{ m.factText }}</span>
          <button class="sb-mem-del" @click="delMemory(m.id)" title="删除">✕</button>
        </li>
      </ul>
      <div class="sb-mem-add">
        <input v-model="memInput" class="sb-input" placeholder="教 TA 记住一件事……" @keydown.enter="addMemory" />
        <select v-model="memType" class="sb-input sb-mem-type-select">
          <option value="FACT">事实</option>
          <option value="SPEECH_STYLE">说话习惯</option>
          <option value="RELATION">关系</option>
          <option value="EVENT">经历</option>
        </select>
        <button class="btn-hand primary sb-mem-go" @click="addMemory">记下</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import {
  listSandboxPersonas, listSandboxSessions, sandboxCreate,
  sandboxReset, sandboxDelete, createSandboxChatSSE,
  listSandboxMemories, addSandboxMemory, deleteSandboxMemory, sandboxReview } from '../api/index.js'

const router = useRouter()
const personas = ref([])
const sessions = ref([])
const currentId = ref(null)
const currentSession = computed(() => sessions.value.find(s => s.id === currentId.value) || null)
const showCreate = ref(false)
const customTraits = ref('')
const relationshipStage = ref('')
const messages = ref([])
const inputText = ref('')
const loading = ref(false)
const memOpen = ref(false)
const memories = ref([])
const memInput = ref('')
const memType = ref('FACT')
const msgRef = ref(null)
let cancelSSE = null

function initialOf(name) { return (name || 'TA').trim()[0] }
function shortDate(t) { return t ? String(t).slice(5, 16).replace('T', ' ') : '' }
function typeName(t) {
  return { FACT: '事实', SPEECH_STYLE: '说话习惯', RELATION: '关系', EVENT: '经历' }[t] || t
}
/** persona.traitsJson 是 JSON 串（tone/catchphrase/relationshipStage）——安全解析取金句 */
function personaLine(p) {
  try {
    const t = JSON.parse(p.traitsJson || '{}')
    return t.catchphrase || ''
  } catch (e) { return '' }
}
function goHome() { router.push('/') }
function openCreate() { showCreate.value = true }

onMounted(async () => {
  try { personas.value = (await listSandboxPersonas()).data?.data || [] } catch (e) { console.error(e) }
  loadSessions()
})
async function loadSessions() {
  try { sessions.value = (await listSandboxSessions()).data?.data || [] } catch (e) { console.error(e) }
}
function openSession(id) {
  currentId.value = id
  showCreate.value = false
  memOpen.value = false
}
async function startWithPersona(p) {
  try {
    const res = await sandboxCreate({ personaId: p.id })
    await loadSessions()
    openSession(res.data?.data?.sandboxId)
  } catch (e) { alert('开场失败：' + (e.response?.data?.message || e.message)) }
}
async function startCustom() {
  try {
    const res = await sandboxCreate({ customTraits: customTraits.value, relationshipStage: relationshipStage.value || undefined })
    await loadSessions()
    showCreate.value = false
    customTraits.value = ''
    relationshipStage.value = ''
    openSession(res.data?.data?.sandboxId)
  } catch (e) { alert('开场失败：' + (e.response?.data?.message || e.message)) }
}
/* ============ 演练复盘（2026-09-08 产品闭环 ③） ============ */
const reviewing = ref(false)
const reviewResult = ref(null)

async function runReview() {
  if (!currentId.value || reviewing.value) return
  reviewing.value = true
  try {
    const res = await sandboxReview(currentId.value)
    const body = res.data
    if (body?.code !== 200) {
      // 业务失败（如"演练对话太短"）：Result{code,message} 走 HTTP 200，需按 code 判断
      reviewResult.value = { summary: body?.message || '复盘失败，稍后再试', good: '', risk: '', better: '' }
    } else {
      reviewResult.value = body.data || {}
    }
  } catch (e) {
    reviewResult.value = { summary: e.response?.data?.message || '复盘失败，稍后再试', good: '', risk: '', better: '' }
  } finally {
    reviewing.value = false
  }
}

async function resetSession() {
  if (!currentId.value) return
  if (!confirm('重置后这场的记忆和对话会清空，确定？')) return
  try {
    await sandboxReset(currentId.value)
    messages.value = []
    memories.value = []
  } catch (e) { console.error(e) }
}
async function removeSession() {
  if (!currentId.value) return
  if (!confirm('删除这场模拟？')) return
  try {
    await sandboxDelete(currentId.value)
    sessions.value = sessions.value.filter(s => s.id !== currentId.value)
    currentId.value = null
    messages.value = []
  } catch (e) { console.error(e) }
}
async function scrollDown() { await nextTick(); if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight }
function send() {
  const text = inputText.value.trim()
  if (!text || !currentId.value) return
  messages.value.push({ role: 'user', text })
  inputText.value = ''
  loading.value = true
  scrollDown()
  cancelSSE = createSandboxChatSSE(currentId.value, text, {
    onMessage(d) { messages.value.push({ role: 'ai', text: d }); scrollDown() },
    onError() { messages.value.push({ role: 'ai', text: '这场的信号断了，稍后再试。' }); loading.value = false },
    onComplete() { loading.value = false; scrollDown() },
    onBusy(body) { messages.value.push({ role: 'ai', text: (body && body.message) || '这场暂时进不去，稍后再试。' }); loading.value = false }
  })
}
async function toggleMemories() {
  memOpen.value = !memOpen.value
  if (memOpen.value && currentId.value) {
    try { memories.value = (await listSandboxMemories(currentId.value)).data?.data || [] } catch (e) { console.error(e) }
  }
}
async function addMemory() {
  const fact = memInput.value.trim()
  if (!fact || !currentId.value) return
  try {
    await addSandboxMemory(currentId.value, fact, memType.value)
    memInput.value = ''
    memories.value = (await listSandboxMemories(currentId.value)).data?.data || []
  } catch (e) { console.error(e) }
}
async function delMemory(memId) {
  if (!currentId.value) return
  try {
    await deleteSandboxMemory(currentId.value, memId)
    memories.value = memories.value.filter(m => m.id !== memId)
  } catch (e) { console.error(e) }
}
</script>

<style scoped>
.sandbox-page { height: 100%; display: flex; flex-direction: column; background: var(--paper); position: relative; }
.sb-header {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 14px 22px 12px; flex-shrink: 0;
  border-bottom: 1.5px solid var(--ink-line);
  background: linear-gradient(180deg, oklch(99% 0.008 78), var(--paper));
  box-shadow: 0 8px 18px -16px oklch(35% 0.04 62 / 0.5);
  position: relative; z-index: 2;
}
.letterhead { display: flex; align-items: center; gap: 12px; }
.letterhead-stamp {
  display: inline-flex; align-items: center; justify-content: center;
  width: 42px; height: 42px; border-radius: 50%;
  border: 2px dashed oklch(55% 0.12 60); color: oklch(45% 0.11 60);
  font-family: var(--font-hand); font-size: 20px; font-weight: 700;
  transform: rotate(-10deg); background: var(--paper-card); flex-shrink: 0;
}
.letterhead-titles { display: flex; flex-direction: column; line-height: 1.15; }
.letterhead-title { font-size: 22px; margin: 0; letter-spacing: 0.14em; }
.letterhead-sub { font-family: var(--font-hand); font-size: 11px; color: var(--ink-faint); letter-spacing: 0.2em; margin-top: 2px; }
.back-btn { font-family: var(--font-hand); font-size: 20px; color: var(--ink-soft); background: transparent; border: none; cursor: pointer; padding: 6px 10px; border-radius: 50%; }
.back-btn:hover { background: var(--paper-deep); transform: translateX(-2px); }
.new-chat-btn { font-size: 13.5px; }

.sb-body { flex: 1; display: flex; min-height: 0; }
.sb-side {
  width: 230px; flex-shrink: 0; border-right: 1.5px solid var(--ink-line);
  padding: 18px 12px; overflow-y: auto; background: oklch(99% 0.006 78 / 0.6);
}
.sb-side-title { font-size: 15px; letter-spacing: 0.14em; margin: 0 6px 12px; }
.sb-side-empty { font-family: var(--font-hand); font-size: 13px; color: var(--ink-faint); text-align: center; padding: 30px 6px; line-height: 2; }
.sb-session-list { display: flex; flex-direction: column; gap: 8px; }
.sb-session { display: flex; align-items: center; gap: 10px; padding: 10px; border-radius: 10px; border: 1px solid transparent; cursor: pointer; transition: all 0.16s; }
.sb-session:hover { background: var(--paper-deep); }
.sb-session-active { background: var(--paper-card); border-color: var(--ink-line); box-shadow: 0 6px 14px -10px oklch(40% 0.1 60 / 0.4); }
.sb-session-wax {
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border-radius: 50%; flex-shrink: 0;
  background: radial-gradient(circle at 34% 30%, oklch(62% 0.12 60), oklch(50% 0.11 60));
  color: oklch(99% 0.01 70); font-family: var(--font-hand); font-size: 16px;
  box-shadow: inset 0 -2px 4px oklch(35% 0.1 60 / 0.4); transform: rotate(-6deg);
}
.sb-session-meta { min-width: 0; }
.sb-session-name { font-size: 13.5px; font-weight: 700; color: var(--ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sb-session-time { font-size: 11px; color: var(--ink-faint); font-family: var(--font-hand); }

.sb-main { flex: 1; min-width: 0; display: flex; flex-direction: column; overflow-y: auto; }
.sb-welcome { max-width: 780px; margin: 0 auto; width: 100%; padding: 44px 22px; text-align: center; }
.sb-welcome-emoji { font-size: 44px; display: block; margin-bottom: 10px; }
.sb-welcome-title { font-size: 28px; letter-spacing: 0.14em; margin: 0 0 8px; }
.sb-welcome-sub { font-family: var(--font-hand); font-size: 14px; color: var(--ink-soft); margin-bottom: 30px; letter-spacing: 0.04em; }
.persona-grid { display: flex; flex-wrap: wrap; gap: 18px; justify-content: center; }
.persona-card { flex: 1 1 220px; max-width: 250px; min-width: 200px; padding: 26px 20px 18px; cursor: pointer; text-align: center; transition: transform 0.24s cubic-bezier(0.16, 1, 0.3, 1), box-shadow 0.24s, border-color 0.24s; }
.persona-card:hover { transform: translateY(-4px) rotate(-0.3deg); border-color: oklch(55% 0.12 60); box-shadow: 0 16px 34px -18px oklch(40% 0.1 60 / 0.5); }
.persona-avatar {
  display: inline-flex; align-items: center; justify-content: center;
  width: 54px; height: 54px; border-radius: 50%; margin-bottom: 10px;
  background: radial-gradient(circle at 34% 30%, oklch(62% 0.12 60), oklch(50% 0.11 60));
  color: oklch(99% 0.01 70); font-family: var(--font-hand); font-size: 24px;
  box-shadow: inset 0 -3px 6px oklch(35% 0.1 60 / 0.4), 0 8px 18px -8px oklch(40% 0.1 60 / 0.55); transform: rotate(-8deg);
}
.persona-avatar-custom { background: radial-gradient(circle at 34% 30%, oklch(58% 0.1 250), oklch(44% 0.09 250)); }
.persona-name { font-size: 19px; margin: 0 0 4px; letter-spacing: 0.1em; }
.persona-arche { font-size: 12px; color: var(--ink-faint); letter-spacing: 0.12em; margin-bottom: 8px; }
.persona-line { font-family: var(--font-hand); font-size: 13px; color: var(--ink-soft); line-height: 1.6; margin-bottom: 10px; min-height: 40px; }
.persona-go { font-family: var(--font-hand); font-size: 13px; color: oklch(48% 0.12 60); letter-spacing: 0.1em; border-bottom: 1px dashed oklch(55% 0.12 60); padding-bottom: 2px; }

.sb-custom { max-width: 520px; margin: 44px auto; width: calc(100% - 44px); padding: 34px 34px 28px; }
.sb-custom-title { font-size: 21px; letter-spacing: 0.12em; margin: 0 0 16px; text-align: center; }
.sb-field-label { display: block; font-family: var(--font-hand); font-size: 13px; color: var(--ink-soft); letter-spacing: 0.1em; margin: 12px 0 6px; }
.sb-input { width: 100%; border: none; border-bottom: 1.6px solid var(--ink-line); background: transparent; font-size: 15px; color: var(--ink); padding: 8px 2px; outline: none; transition: border-color 0.18s; }
.sb-input:focus { border-bottom-color: oklch(55% 0.12 60); }
.sb-input::placeholder { color: var(--ink-faint); font-family: var(--font-hand); }
.sb-textarea { border: 1.4px dashed var(--ink-line); border-radius: 8px; padding: 10px 12px; resize: none; line-height: 1.7; }
.sb-textarea:focus { border-color: oklch(55% 0.12 60); border-style: solid; }
.sb-custom-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 22px; }

.sb-chat { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.sb-chat-top { display: flex; align-items: center; justify-content: space-between; padding: 14px 24px 12px; border-bottom: 1px dashed var(--ink-line); flex-shrink: 0; }
.sb-chat-wax {
  display: inline-flex; align-items: center; justify-content: center;
  width: 36px; height: 36px; border-radius: 50%; margin-right: 10px;
  background: radial-gradient(circle at 34% 30%, oklch(62% 0.12 60), oklch(50% 0.11 60));
  color: oklch(99% 0.01 70); font-family: var(--font-hand); font-size: 17px;
  transform: rotate(-8deg); box-shadow: inset 0 -2px 4px oklch(35% 0.1 60 / 0.4);
}
.sb-chat-name { font-size: 17px; letter-spacing: 0.08em; vertical-align: middle; }
.sb-chat-tools { display: flex; gap: 6px; }
.sb-tool { font-family: var(--font-hand); font-size: 12.5px; letter-spacing: 0.05em; color: var(--ink-soft); background: transparent; border: 1px solid transparent; padding: 5px 10px; border-radius: 12px 10px 11px 9px; cursor: pointer; transition: all 0.15s; }
.sb-tool:hover { border-color: var(--ink-line); background: var(--paper-card); color: var(--ink); }
.sb-tool.danger:hover { border-color: var(--danger); color: var(--danger); }
.sb-messages { flex: 1; overflow-y: auto; padding: 22px clamp(16px, 4vw, 44px); display: flex; flex-direction: column; gap: 12px; }
.sb-msg { max-width: min(82%, 640px); }
.sb-msg-user { align-self: flex-end; }
.sb-msg-ai { align-self: flex-start; }
.sb-msg-content { padding: 11px 16px; font-size: 14.5px; line-height: 1.8; word-break: break-word; }
.sb-msg-ai .sb-msg-content { background: var(--paper-card); border: 1px solid var(--ink-line); border-radius: 4px 14px 14px 14px; }
.sb-msg-user .sb-msg-content { background: linear-gradient(180deg, oklch(55% 0.12 60), oklch(45% 0.11 60)); color: oklch(99% 0.01 70); border-radius: 14px 4px 14px 14px; }
.sb-input-bar { flex-shrink: 0; display: flex; gap: 10px; padding: 14px 24px 16px; border-top: 1.5px solid var(--ink-line); }
.sb-chat-input { flex: 1; border: 1px solid var(--ink-line); border-radius: 22px; background: var(--paper-card); font-size: 15px; color: var(--ink); padding: 10px 18px; outline: none; transition: border-color 0.18s, box-shadow 0.18s; }
.sb-chat-input:focus { border-color: oklch(55% 0.12 60); box-shadow: 0 0 0 3px oklch(90% 0.05 60 / 0.4); }
.sb-chat-input::placeholder { color: var(--ink-faint); font-family: var(--font-hand); }
.send-btn { border: none; border-radius: 22px; background: linear-gradient(180deg, oklch(55% 0.12 60), oklch(45% 0.11 60)); color: oklch(99% 0.01 70); font-family: var(--font-hand); font-size: 15px; letter-spacing: 0.1em; padding: 0 1.6em; cursor: pointer; transition: transform 0.16s cubic-bezier(0.34, 1.56, 0.64, 1), opacity 0.15s; }
.send-btn:hover:not(:disabled) { transform: translateY(-1px); }
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.typing-dots { display: inline-flex; gap: 3px; }
.dot { width: 6px; height: 6px; border-radius: 50%; background: oklch(50% 0.12 60); display: inline-block; animation: dot-b 1.1s ease-in-out infinite; }
.dot:nth-child(2) { animation-delay: 0.16s; }
.dot:nth-child(3) { animation-delay: 0.32s; }
@keyframes dot-b { 0%,60%,100% { transform: translateY(0); opacity: 0.4; } 30% { transform: translateY(-4px); opacity: 1; } }

.sb-mem-drawer { position: absolute; right: 18px; top: 78px; bottom: 18px; width: min(360px, 84%); display: flex; flex-direction: column; padding: 18px 20px; z-index: 5; box-shadow: 0 24px 60px -24px oklch(30% 0.05 60 / 0.5); }
.sb-mem-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.sb-mem-head .hand { font-size: 17px; letter-spacing: 0.1em; }
.sb-mem-empty { font-family: var(--font-hand); font-size: 13px; color: var(--ink-faint); text-align: center; padding: 26px 6px; line-height: 1.9; }
.sb-mem-list { flex: 1; overflow-y: auto; list-style: none; display: flex; flex-direction: column; gap: 8px; }
.sb-mem-item { display: flex; align-items: flex-start; gap: 8px; font-size: 13.5px; line-height: 1.6; background: var(--paper-card-warm); border-radius: 8px; padding: 8px 10px; border: 1px solid var(--ink-line); }
.sb-mem-type { flex-shrink: 0; font-family: var(--font-hand); font-size: 11px; color: oklch(48% 0.12 60); background: oklch(93% 0.05 60); border-radius: 8px; padding: 1px 8px; margin-top: 2px; }
.sb-mem-text { flex: 1; word-break: break-word; color: var(--ink); }
.sb-mem-del { background: transparent; border: none; color: var(--ink-faint); cursor: pointer; font-size: 12px; padding: 2px; }
.sb-mem-del:hover { color: var(--danger); }
.sb-mem-add { display: flex; gap: 8px; margin-top: 12px; align-items: center; }
.sb-mem-add .sb-input { flex: 1; font-size: 13.5px; }
.sb-mem-type-select { width: 96px; flex-shrink: 0; font-family: var(--font-hand); font-size: 13px; }
.sb-mem-go { font-size: 13px; padding: 0.5em 1em; flex-shrink: 0; }

/* ---- 演练复盘（2026-09-08 产品闭环 ③） ---- */
.sb-review { margin: 10px 0; padding: 12px 14px; border-left: 3px solid var(--wine); }
.sb-review-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.sb-review-title { font-family: var(--font-hand); font-size: 14px; color: var(--ink); }
.sb-review-summary { font-size: 13.5px; color: var(--ink); line-height: 1.6; margin: 4px 0 8px; }
.sb-review-line { font-size: 13px; line-height: 1.6; margin: 6px 0; padding: 6px 10px; border-radius: 8px; }
.sb-review-line.good { background: rgba(99,153,34,0.1); }
.sb-review-line.risk { background: rgba(163,45,45,0.08); }
.sb-review-line.better { background: var(--paper-deep); }
.sb-review-note { font-size: 11.5px; color: var(--ink-faint); margin-top: 8px; }
</style>
