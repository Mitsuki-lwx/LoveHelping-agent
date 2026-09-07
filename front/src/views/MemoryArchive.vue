<template>
  <div class="mem-page">
    <div class="mem-header">
      <button class="back-btn" @click="goHome">←</button>
      <div class="letterhead">
        <span class="letterhead-stamp anim-stamp">忆</span>
        <div class="letterhead-titles">
          <h1 class="letterhead-title hand">记忆档案</h1>
          <span class="letterhead-sub">— AI 记得的关于你的事 —</span>
        </div>
      </div>
      <button class="refresh-btn btn-hand" @click="load">↻ 刷新</button>
    </div>

    <div class="mem-body">
      <p class="mem-hint hand">每一次聊天里，AI 会慢慢记下你的偏好、经历与结论。你可以在这里查看、修正或删除——它记错的地方，你有最终决定权。</p>

      <div v-if="loading" class="mem-state">翻开档案中……</div>

      <div v-else-if="!groups.length" class="mem-state mem-empty anim-letter-in">
        <div class="mem-empty-icon">📒</div>
        <p class="hand">档案还是空白的</p>
        <p class="mem-empty-sub">去解忧信箱多聊几句，AI 会开始了解你——偏好的相处方式、你在意的事、你正在经历的关系阶段……</p>
        <button class="btn-hand primary" @click="goChat">去写信 →</button>
      </div>

      <div v-else class="mem-groups">
        <section v-for="g in groups" :key="g.category" class="mem-group">
          <header class="mem-group-head">
            <span class="hand mem-group-name">{{ catName(g.category) }}</span>
            <span class="mem-group-count">{{ g.items.length }} 条</span>
          </header>
          <ul class="mem-list">
            <li v-for="f in g.items" :key="f.id" class="mem-card letter-card">
              <div class="mem-card-top">
                <span :class="['mem-status', f.status === 'ACTIVE' ? 'mem-status-active' : 'mem-status-cand']">
                  {{ statusName(f.status) }}
                </span>
                <span v-if="f.status === 'CANDIDATE'" class="mem-cand-hint">待你确认（点击内容修正即转正）</span>
                <span class="mem-hit">被想起 {{ f.hitCount || 0 }} 次</span>
              </div>

              <template v-if="editingId !== f.id">
                <p class="mem-content" :class="{ 'mem-content-cand': f.status === 'CANDIDATE' }"
                   @click="startEdit(f)" title="点击修正">{{ f.content }}</p>
              </template>
              <template v-else>
                <textarea v-model="editText" class="mem-edit" rows="2" maxlength="500"></textarea>
                <div class="mem-edit-actions">
                  <button class="btn-hand" @click="editingId = null">取消</button>
                  <button class="btn-hand primary" :disabled="!editText.trim()" @click="saveFact(f)">记对（转正）</button>
                </div>
              </template>

              <div class="mem-meta">
                <span>{{ fmtTime(f.createdAt) }}</span>
                <span class="mem-confidence">可信度 {{ (f.confidence || 0) }}/10</span>
                <div class="mem-actions">
                  <button class="mem-act" @click="startEdit(f)">✎ 修正</button>
                  <button class="mem-act mem-act-del" @click="delFact(f)">删除</button>
                </div>
              </div>
            </li>
          </ul>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getMyMemoryFacts, updateMemoryFact, deleteMemoryFact } from '../api/index.js'

const router = useRouter()
const facts = ref([])
const loading = ref(false)
const editingId = ref(null)
const editText = ref('')

const CAT_NAMES = { PREFERENCE: '我的偏好', FACT: '关于我的事实', EVENT: '经历与事件', RELATION: '关系状态', CONCLUSION: '我曾得出的结论' }
const STATUS_NAMES = { ACTIVE: '在档', CANDIDATE: '待确认', DEPRECATED: '已过期' }

const groups = computed(() => {
  const map = {}
  for (const f of facts.value) {
    const c = f.category || 'FACT'
    ;(map[c] = map[c] || []).push(f)
  }
  return Object.entries(map).map(([category, items]) => ({ category, items }))
})

function catName(c) { return CAT_NAMES[c] || c }
function statusName(s) { return STATUS_NAMES[s] || s }
function fmtTime(t) { return t ? String(t).slice(0, 16).replace('T', ' ') : '' }
function goHome() { router.push('/') }
function goChat() { router.push('/love-chat') }

async function load() {
  loading.value = true
  try {
    facts.value = (await getMyMemoryFacts()).data?.data || []
  } catch (e) { console.error('load facts failed', e) } finally { loading.value = false }
}
function startEdit(f) { editingId.value = f.id; editText.value = f.content }
async function saveFact(f) {
  try {
    await updateMemoryFact(f.id, editText.value.trim())
    editingId.value = null
    await load()
  } catch (e) { console.error('update failed', e) }
}
async function delFact(f) {
  if (!confirm('删除这条记忆？')) return
  try {
    await deleteMemoryFact(f.id)
    await load()
  } catch (e) { console.error('delete failed', e) }
}
onMounted(load)
</script>

<style scoped>
.mem-page { height: 100%; display: flex; flex-direction: column; background: var(--paper); overflow: hidden; }
.mem-header {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 14px 22px 12px; flex-shrink: 0;
  border-bottom: 1.5px solid var(--ink-line);
  background: linear-gradient(180deg, oklch(99% 0.008 78), var(--paper));
  box-shadow: 0 8px 18px -16px oklch(35% 0.04 62 / 0.5); position: relative; z-index: 2;
}
.letterhead { display: flex; align-items: center; gap: 12px; }
.letterhead-stamp {
  display: inline-flex; align-items: center; justify-content: center;
  width: 42px; height: 42px; border-radius: 50%;
  border: 2px dashed oklch(52% 0.09 160); color: oklch(42% 0.09 160);
  font-family: var(--font-hand); font-size: 20px; font-weight: 700;
  transform: rotate(-10deg); background: var(--paper-card); flex-shrink: 0;
}
.letterhead-titles { display: flex; flex-direction: column; line-height: 1.15; }
.letterhead-title { font-size: 22px; margin: 0; letter-spacing: 0.14em; }
.letterhead-sub { font-family: var(--font-hand); font-size: 11px; color: var(--ink-faint); letter-spacing: 0.2em; margin-top: 2px; }
.back-btn { font-family: var(--font-hand); font-size: 20px; color: var(--ink-soft); background: transparent; border: none; cursor: pointer; padding: 6px 10px; border-radius: 50%; }
.back-btn:hover { background: var(--paper-deep); transform: translateX(-2px); }
.refresh-btn { font-size: 13px; }

.mem-body { flex: 1; overflow-y: auto; padding: 22px clamp(14px, 4vw, 40px) 40px; }
.mem-hint { font-size: 14px; color: var(--ink-soft); letter-spacing: 0.04em; text-align: center; margin: 0 auto 26px; max-width: 560px; line-height: 1.9; }
.mem-state { text-align: center; padding: 40px 0; color: var(--ink-faint); font-family: var(--font-hand); font-size: 15px; }
.mem-empty { padding: 60px 20px; }
.mem-empty-icon { font-size: 52px; margin-bottom: 12px; }
.mem-empty .hand { font-size: 24px; letter-spacing: 0.14em; margin: 0 0 10px; color: var(--ink); }
.mem-empty-sub { font-family: var(--font-body); font-size: 14px; color: var(--ink-soft); line-height: 2; max-width: 420px; margin: 0 auto 20px; }

.mem-groups { max-width: 780px; margin: 0 auto; display: flex; flex-direction: column; gap: 30px; }
.mem-group-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 14px; }
.mem-group-name { font-size: 20px; letter-spacing: 0.14em; margin: 0; }
.mem-group-count { font-size: 12px; color: var(--ink-faint); font-family: var(--font-hand); }
.mem-list { list-style: none; display: flex; flex-direction: column; gap: 12px; }
.mem-card { padding: 16px 18px 12px; }
.mem-card-top { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.mem-status { font-family: var(--font-hand); font-size: 11.5px; border-radius: 10px; padding: 1px 10px; letter-spacing: 0.08em; }
.mem-status-active { background: oklch(93% 0.05 160); color: oklch(42% 0.1 160); border: 1px solid oklch(80% 0.08 160); }
.mem-status-cand { background: oklch(93% 0.05 70); color: oklch(48% 0.1 70); border: 1px solid oklch(82% 0.08 70); }
.mem-cand-hint { font-size: 11.5px; color: oklch(52% 0.1 70); font-family: var(--font-hand); }
.mem-hit { margin-left: auto; font-size: 11.5px; color: var(--ink-faint); font-family: var(--font-hand); }
.mem-content { font-size: 15px; line-height: 1.85; color: var(--ink); cursor: text; }
.mem-content-cand { color: var(--ink-soft); }
.mem-content-cand::after { content: ' ✍️'; font-size: 12px; }
.mem-edit { width: 100%; border: 1.4px dashed oklch(52% 0.09 160); border-radius: 8px; padding: 9px 12px; font-size: 14.5px; color: var(--ink); line-height: 1.7; resize: none; outline: none; background: var(--paper-card-warm); font-family: inherit; }
.mem-edit:focus { border-color: oklch(52% 0.09 160); border-style: solid; }
.mem-edit-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 10px; }
.mem-edit-actions .btn-hand { font-size: 13px; }
.mem-meta { display: flex; align-items: center; gap: 12px; margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--ink-line); font-size: 11.5px; color: var(--ink-faint); font-family: var(--font-hand); }
.mem-confidence { color: var(--ink-soft); }
.mem-actions { margin-left: auto; display: flex; gap: 4px; }
.mem-act { background: transparent; border: none; cursor: pointer; font-family: var(--font-hand); font-size: 12.5px; color: var(--ink-soft); padding: 3px 8px; border-radius: 8px; transition: all 0.14s; }
.mem-act:hover { background: var(--paper-deep); color: oklch(45% 0.1 160); }
.mem-act-del:hover { color: var(--danger); background: oklch(94% 0.05 27); }
</style>
