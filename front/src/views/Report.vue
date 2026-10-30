<template>
  <div class="report-page">
    <div class="report-inner">
      <div class="report-top">
        <button class="report-back" @click="$router.push('/profile')">← 返回我的小窝</button>
        <span class="report-stamp hand">举</span>
      </div>

      <div class="letter-card fold report-card">
        <h1 class="hand report-title">投诉举报</h1>
        <p class="report-lead">
          遇到违规内容、隐私问题或骚扰，都可以在这里提交。请尽量写清时间、位置与具体情况，
          我们会按下面的时限核查并反馈。
        </p>

        <p class="report-sla">
          <strong>处理时限：</strong>自受理之日起 <b>{{ FEEDBACK_DAYS }} 个工作日</b>内完成核查并向你反馈结果；
          需要延长的，会先告知你原因与预计时间。
        </p>

        <form class="report-form" @submit.prevent="submit">
          <div class="field">
            <label for="report-category">举报分类</label>
            <select id="report-category" v-model="form.category" class="report-select">
              <option v-for="c in CATEGORIES" :key="c.value" :value="c.value">{{ c.label }}</option>
            </select>
          </div>

          <div class="field">
            <label for="report-content">具体情况</label>
            <textarea
              id="report-content"
              v-model="form.content"
              class="report-textarea"
              rows="6"
              maxlength="1000"
              placeholder="例如：某条回复里出现了辱骂性说法，时间大约是今天 14:20…"
            ></textarea>
            <p class="counter">{{ form.content.length }}/1000</p>
          </div>

          <div class="field">
            <label for="report-contact">联系方式（选填）</label>
            <input
              id="report-contact"
              v-model="form.contact"
              class="report-input"
              type="text"
              maxlength="128"
              placeholder="留一个邮箱或其它能联系到你的方式，方便我们回复"
            />
          </div>

          <p v-if="error" class="form-msg err" role="alert">{{ error }}</p>
          <p v-if="notice" class="form-msg ok" role="status">{{ notice }}</p>

          <button type="submit" class="btn-hand save-btn" :disabled="submitting">
            {{ submitting ? '提交中…' : '提交举报' }}
          </button>
        </form>
      </div>

      <div class="letter-card fold report-card">
        <h2 class="report-sub hand">我的举报</h2>
        <p v-if="listError" class="form-msg err" role="alert">{{ listError }}</p>
        <div v-if="listLoading" class="report-empty">加载中…</div>
        <div v-else-if="mine.length === 0" class="report-empty">还没有提交过举报。</div>
        <ul v-else class="report-list">
          <li v-for="item in mine" :key="item.id" class="report-item">
            <div class="report-item-head">
              <span class="report-cat">{{ categoryLabel(item.category) }}</span>
              <span :class="['report-status', statusClass(item.status)]">{{ statusLabel(item.status) }}</span>
            </div>
            <p class="report-item-text">{{ item.content }}</p>
            <p class="report-item-time">{{ formatTime(item.createdAt) }}</p>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 投诉举报入口（2026-10-13 合规改造新增）。
 *
 * ⛔ 监管要求的是"设置**便捷的**投诉举报入口，**公布处理流程和反馈时限**"——
 *    所以这一页必须同时做到三件事，缺一条就等于没做：
 *      1. 入口显眼（「我的小窝」里有一张专门的卡片，不是埋在设置深处）；
 *      2. 提交后**有明确反馈**（不是点完什么都不发生）；
 *      3. 页面**当场写明反馈时限**（15 个工作日，取常量，与协议一致）并能回看自己提交过的记录。
 * ⛔ 分类提交给后端的必须是枚举值（CONTENT/PRIVACY/ABUSE/OTHER），中文只用于显示 —— 别把中文发出去。
 */
import { ref, reactive, onMounted } from 'vue'
import { submitComplaint, listMyComplaints, COMPLAINT_CATEGORIES as CATEGORIES } from '../api/index.js'
import { COMPLAINT_FEEDBACK_WORKING_DAYS as FEEDBACK_DAYS } from '../utils/legal.js'

const form = reactive({ category: CATEGORIES[0].value, content: '', contact: '' })
const submitting = ref(false)
const error = ref('')
const notice = ref('')

const mine = ref([])
const listLoading = ref(true)
const listError = ref('')

const MAX_LEN = 1000

const STATUS_LABEL = { OPEN: '待处理', PROCESSING: '处理中', CLOSED: '已完结' }
const STATUS_CLASS = { OPEN: 'st-open', PROCESSING: 'st-doing', CLOSED: 'st-closed' }

function categoryLabel(v) {
  return CATEGORIES.find((c) => c.value === v)?.label || v || '—'
}
function statusLabel(v) {
  return STATUS_LABEL[v] || v || '待处理'
}
function statusClass(v) {
  return STATUS_CLASS[v] || 'st-open'
}
function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t).slice(0, 19).replace('T', ' ')
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function loadMine() {
  listLoading.value = true
  listError.value = ''
  try {
    const res = await listMyComplaints()
    const list = res?.data?.data
    mine.value = Array.isArray(list) ? list : []
  } catch (e) {
    // ⛔ 拉不到列表要说出来：静默失败会让用户以为"举报根本没提交上去"
    listError.value = e?.response?.data?.message || '举报记录加载失败，请稍后重试'
    mine.value = []
  } finally {
    listLoading.value = false
  }
}

async function submit() {
  error.value = ''
  notice.value = ''
  const content = form.content.trim()
  if (!CATEGORIES.some((c) => c.value === form.category)) { error.value = '请选择举报分类'; return }
  if (!content) { error.value = '请填写具体情况'; return }
  if (content.length > MAX_LEN) { error.value = `具体情况不能超过 ${MAX_LEN} 字`; return }

  submitting.value = true
  try {
    const res = await submitComplaint({
      category: form.category,
      content,
      contact: form.contact.trim(),
    })
    const d = res?.data
    if (d && d.success === false) {
      error.value = d.message || '提交失败，请稍后再试'
      return
    }
    notice.value = (d && d.message) || `已收到你的举报，我们会在 ${FEEDBACK_DAYS} 个工作日内反馈处理结果。`
    form.content = ''
    form.contact = ''
    await loadMine()
  } catch (e) {
    error.value = e?.response?.data?.message || '提交失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}

onMounted(loadMine)
</script>

<style scoped>
.report-page { height: 100%; overflow-y: auto; background: var(--desk); padding: 24px 20px 48px; }
.report-inner { max-width: 680px; margin: 0 auto; display: flex; flex-direction: column; gap: 18px; }
.report-top { display: flex; align-items: center; justify-content: space-between; }
.report-back {
  background: none; border: none; color: var(--ink-soft); cursor: pointer;
  font-size: 13px; padding: 6px 10px; border-radius: var(--r-ctl);
}
.report-back:hover { color: var(--ink); background: var(--desk-raised); }
.report-stamp {
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border-radius: var(--r-seal);
  border: 1.6px dashed var(--accent); color: var(--accent-deep);
  font-size: 16px; transform: rotate(-8deg);
}
.report-card { padding: 26px 26px 22px; }
.report-title { font-size: 21px; letter-spacing: 0.1em; color: var(--ink-on-paper); margin-bottom: 10px; }
.report-sub { font-size: 16px; letter-spacing: 0.06em; color: var(--ink-on-paper); margin-bottom: 12px; }
.report-lead { font-size: 13.5px; line-height: 1.9; color: var(--ink-on-paper-soft); margin-bottom: 12px; }
.report-sla {
  font-size: 13px; line-height: 1.85; color: var(--ink-on-paper);
  background: var(--accent-wash); border-left: 3px solid var(--accent);
  padding: 10px 12px; border-radius: 0 var(--r-ctl) var(--r-ctl) 0; margin-bottom: 18px;
}
.report-sla b { color: var(--accent-text); }
.report-form .field { margin-bottom: 14px; display: flex; flex-direction: column; gap: 6px; }
.report-form label { font-size: 13px; color: var(--ink-on-paper-soft); }
.report-select, .report-input, .report-textarea {
  width: 100%; font-family: var(--font-body); font-size: 14px; color: var(--ink-on-paper);
  background: var(--paper-card-warm); border: 1px solid var(--ink-line);
  border-radius: var(--r-ctl); padding: 9px 11px;
}
.report-textarea { resize: vertical; line-height: 1.8; }
.report-select:focus, .report-input:focus, .report-textarea:focus { outline: none; border-color: var(--accent); }
.counter { font-size: 11.5px; color: var(--ink-on-paper-faint); text-align: right; }
.form-msg { font-size: 13px; margin: 4px 0 10px; line-height: 1.7; }
.form-msg.err { color: var(--danger); }
.form-msg.ok { color: var(--ok); }
.report-empty { font-size: 13.5px; color: var(--ink-on-paper-faint); padding: 10px 0; }
.report-list { list-style: none; display: flex; flex-direction: column; gap: 10px; }
.report-item {
  border: 1px solid var(--ink-line); border-radius: var(--r-ctl);
  padding: 11px 13px; background: var(--paper-card-warm);
}
.report-item-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 6px; }
.report-cat {
  font-size: 12.5px; font-weight: 700; color: var(--chip-fg);
  background: var(--chip-bg); padding: 2px 9px; border-radius: var(--r-pill);
}
.report-status { font-size: 12px; padding: 2px 9px; border-radius: var(--r-pill); }
.st-open { color: var(--accent-text); background: var(--accent-wash); }
.st-doing { color: var(--chip-fg); background: var(--chip-bg); }
.st-closed { color: var(--ok); background: var(--paper-card-warm); border: 1px solid var(--ink-line); }
.report-item-text { font-size: 13.5px; line-height: 1.8; color: var(--ink-on-paper); word-break: break-word; margin-bottom: 5px; }
.report-item-time { font-size: 11.5px; color: var(--ink-on-paper-faint); }
</style>
