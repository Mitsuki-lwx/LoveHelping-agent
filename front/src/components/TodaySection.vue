<template>
  <!--
    「今日」= **一张信纸**，不是四张卡堆叠。
    ⛔ 上一版是 4 个独立卡片竖着排 —— 用户反馈"有点空 / 排版还可以更高级"。
       改成一张信纸 + 日期头 + 两条墨线分区：墨线在这里**组织真实内容**（skill §9.F 允许），
       不是装饰性网格线。这样它读起来像"今天的日记页"，而不是四个功能块。
  -->
  <section class="today letter-card anim-letter-in">
    <div class="today-head">
      <span class="today-stamp stamp" aria-hidden="true">今</span>
      <div class="today-headtext">
        <h2 class="hand today-title">今日</h2>
        <p class="today-date">{{ todayLabel }} · {{ weekday }}</p>
      </div>
    </div>

    <!--
      贴在信纸上的照片条（自托管 front/src/assets/today-desk.jpg）。
      ⛔ 这是"别出心裁"的那一处：不是又一张卡片配图，而是**像用胶带贴在信纸上的一条相片**
         （两个 CSS 胶带角 + 微旋转），照片是**彩色**的，只做极轻暖调统一。
      ⛔ 图片挂了不影响版面：外层有暖纸底色，img 的 onerror 只隐藏自己。
    -->
    <figure class="taped-photo">
      <span class="tape tape-l" aria-hidden="true"></span>
      <span class="tape tape-r" aria-hidden="true"></span>
      <img :src="themeImages.today" alt="木桌上的一杯咖啡" loading="lazy" decoding="async"
           @error="$event.target.style.display = 'none'" />
    </figure>

    <!-- 一 · 心情打卡：选心情 → 界面跟着换主题（用户提的"不同心情代表不同 UI 风格"） -->
    <div class="today-zone">
      <p class="zone-label">今天心情怎么样</p>
      <div class="mood-row">
        <button
          v-for="m in MOODS"
          :key="m.id"
          :class="['mood-chip', { on: mood === m.id }]"
          @click="pickMood(m)"
        >
          <component :is="MOOD_ICON[m.id]" :size="17" weight="bold" aria-hidden="true" />
          <span>{{ m.label }}</span>
        </button>
      </div>
      <p v-if="moodReply" class="mood-reply">{{ moodReply }}</p>
    </div>

    <hr class="ink-rule" />

    <!-- 二 · 每日一问 + 情话签（同一张纸上左右两栏） -->
    <div class="today-pair">
      <div class="q-card">
        <p class="zone-label">
          <PhQuestion :size="15" weight="bold" class="zone-ico" aria-hidden="true" />
          每日一问
        </p>
        <p class="q-text">{{ question.q }}</p>
        <div class="q-opts">
          <button
            v-for="(o, i) in question.opts"
            :key="i"
            :class="['q-opt', { on: answerIdx === i }]"
            @click="answer(i)"
          >
            {{ o.label }}
          </button>
        </div>
        <template v-if="answerIdx !== null">
          <p class="q-read">{{ question.opts[answerIdx].read }}</p>
          <button class="btn-hand primary q-go" @click="discuss">
            去聊聊这件事 <PhArrowRight :size="15" weight="bold" class="ico" aria-hidden="true" />
          </button>
        </template>
      </div>

      <div class="slip-card">
        <p class="zone-label">
          <PhQuotes :size="15" weight="bold" class="zone-ico" aria-hidden="true" />
          情话签
        </p>
        <p v-if="!slip" class="slip-hint">抽一张，看看今天想跟你说什么。</p>
        <p v-else class="slip-text hand">{{ slip }}</p>
        <div class="slip-acts">
          <button class="btn-hand" @click="drawSlip">{{ slip ? '再抽一张' : '抽一张' }}</button>
          <button v-if="slip" class="slip-fold" @click="foldSlip">
            折进信里 <PhArrowRight :size="15" weight="bold" class="ico" aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>

    <hr class="ink-rule" />

    <!-- 三 · 在一起第 N 天（跨设备保留 ⇒ 走后端资料字段） -->
    <div class="anniv-card">
      <p class="zone-label">
        <PhCalendarHeart :size="15" weight="bold" class="zone-ico" aria-hidden="true" />
        在一起第几天
      </p>
      <template v-if="days !== null && !editing">
        <p class="anniv-days">
          <b>{{ days }}</b>
          <span>天</span>
        </p>
        <p class="anniv-sub">从 {{ anniversary }} 算起（含当天）</p>
        <button class="anniv-edit" @click="startEdit">改一下</button>
      </template>
      <template v-else>
        <p class="anniv-sub">{{ days === null ? '填一个日子，我帮你记着。' : '改成哪一天？' }}</p>
        <div class="anniv-form">
          <input v-model="annivInput" type="date" class="anniv-input" aria-label="纪念日" />
          <button class="btn-hand primary" :disabled="saving" @click="saveAnniv">
            {{ saving ? '记下中…' : '记下' }}
          </button>
          <button v-if="editing" class="anniv-edit" @click="editing = false">取消</button>
        </div>
        <p v-if="annivError" class="anniv-err" role="alert">{{ annivError }}</p>
      </template>
    </div>
  </section>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  PhSmiley, PhHeart, PhMoonStars, PhLeaf, PhCloudRain, PhCloudMoon,
  PhQuestion, PhQuotes, PhCalendarHeart, PhArrowRight,
} from '@phosphor-icons/vue'
import { MOODS, QUESTIONS, SLIPS, pickByDate, loadDaily, saveDaily, daysTogether, todayKey } from '../utils/daily.js'
import { setTheme } from '../utils/theme.js'
import { useThemeImages } from '../utils/theme-images.js'

// 信纸上贴的那张照片也随主题换（同一套图里的 today 槽位）
const themeImages = useThemeImages()
import { updateProfileApi } from '../api/index.js'
import { cachedProfile, mergeProfile, refreshProfile } from '../utils/profile.js'

const router = useRouter()

/** 心情 → 图标（一个家族，weight 统一） */
const MOOD_ICON = {
  happy: PhSmiley,
  flutter: PhHeart,
  miss: PhMoonStars,
  calm: PhLeaf,
  wronged: PhCloudRain,
  low: PhCloudMoon,
}

// ---------- ① 心情打卡 ----------
const mood = ref(loadDaily('mood'))
const moodReply = computed(() => MOODS.find((m) => m.id === mood.value)?.reply || '')

function pickMood(m) {
  mood.value = saveDaily('mood', m.id)
  // 用户要的"不同心情代表不同 UI 风格"：选完心情顺手换主题（仍可在导航栏单独改）
  setTheme(m.theme)
}

// ---------- ② 每日一问 ----------
const question = computed(() => pickByDate(QUESTIONS))
const answerIdx = ref(loadDaily('answer'))
const todayLabel = computed(() => {
  const d = new Date()
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日`
})
const weekday = computed(() => '星期' + '日一二三四五六'[new Date().getDay()])

function answer(i) {
  answerIdx.value = saveDaily('answer', i)
}

function discuss() {
  const q = question.value.q
  const a = question.value.opts[answerIdx.value]?.label
  router.push({ path: '/love-chat', query: { q: `今天的一问：「${q}」我选的是「${a}」，想聊聊。` } })
}

// ---------- ③ 情话签 ----------
const slip = ref(loadDaily('slip'))

function drawSlip() {
  // 首次抽 = 今天的定数；再抽才换（用户主动要换，不是刷新随机）
  const s = slip.value
    ? SLIPS[(SLIPS.indexOf(slip.value) + 1 + Math.floor(Math.random() * (SLIPS.length - 1))) % SLIPS.length]
    : pickByDate(SLIPS)
  slip.value = saveDaily('slip', s)
}

function foldSlip() {
  router.push({ path: '/love-chat', query: { q: `今天抽到一句：「${slip.value}」` } })
}

// ---------- ④ 纪念日（后端字段） ----------
const anniversary = ref(null)
const annivInput = ref('')
const editing = ref(false)
const saving = ref(false)
const annivError = ref('')
const days = computed(() => daysTogether(anniversary.value))

// ⛔ 这里**不发请求**：读缓存（唯一取数点在 utils/profile.js，由 App 启动时刷一次）。
//    之前在这里直接 getMe() —— 首页多一个请求，且单测里会真连网（ECONNRESET + 拖慢整套测试）。
onMounted(() => {
  const d = cachedProfile().anniversaryDate
  if (d) anniversary.value = String(d).slice(0, 10)
  if (!d) refreshProfile().then((p) => { if (p?.anniversaryDate) anniversary.value = String(p.anniversaryDate).slice(0, 10) })
})

function startEdit() {
  annivInput.value = anniversary.value || ''
  annivError.value = ''
  editing.value = true
}

async function saveAnniv() {
  const v = (annivInput.value || '').trim()
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) {
    annivError.value = '请选一个日期（年-月-日）。'
    return
  }
  saving.value = true
  annivError.value = ''
  try {
    const res = await updateProfileApi({ anniversaryDate: v })
    if (res?.data?.success === false) {
      annivError.value = res.data.message || '没记上，再试一次。'
      return
    }
    anniversary.value = v
    editing.value = false
    // 写进本地缓存并广播（导航栏/其他读资料的组件都靠这个事件刷新）
    mergeProfile({ anniversaryDate: v })
  } catch (e) {
    annivError.value = e?.response?.data?.message || '没记上，稍后再试。'
  } finally {
    saving.value = false
  }
}

defineExpose({ todayKey })
</script>

<style scoped>
.today {
  width: 100%;
  max-width: 1020px;
  margin-top: clamp(26px, 4vw, 42px);
  padding: clamp(22px, 3vw, 32px) clamp(20px, 3vw, 34px) clamp(20px, 3vw, 28px);
}

/* 日期头：像信纸右上角的日期 */
.today-head { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; }
.today-stamp { font-size: 15px; }
.today-headtext { display: flex; flex-direction: column; gap: 2px; }
.today-title { font-size: 21px; margin: 0; letter-spacing: 0.16em; color: var(--ink-on-paper); }
.today-date { margin: 0; font-size: 12px; letter-spacing: 0.12em; color: var(--ink-on-paper-soft); font-family: var(--font-hand); }

/* 贴在信纸上的照片条：微旋转 + 两个胶带角 */
.taped-photo {
  position: relative;
  margin: 0 0 20px;
  border-radius: var(--r-chip);
  overflow: hidden;
  transform: rotate(-0.7deg);
  box-shadow: 0 10px 24px -16px oklch(30% 0.05 40 / 0.7);
  background: var(--paper-card-warm);
}
.taped-photo img {
  /* 要的是"一条相片"，不是又一张大图：压到约 4.5:1，别把心情区挤到首屏外 */
  display: block; width: 100%; aspect-ratio: 9 / 2; object-fit: cover;
  filter: saturate(1.04) contrast(1.02);
}
.tape {
  position: absolute; top: -6px; width: 64px; height: 20px; z-index: 2;
  background: oklch(96% 0.02 80 / 0.62);
  border-left: 1px solid oklch(88% 0.02 80 / 0.5);
  border-right: 1px solid oklch(88% 0.02 80 / 0.5);
  box-shadow: 0 1px 3px oklch(40% 0.03 60 / 0.18);
}
.tape-l { left: 26px; transform: rotate(-4deg); }
.tape-r { right: 26px; transform: rotate(3deg); }

/* 墨线：**组织真实内容**的分区线（不是装饰网格） */
.ink-rule { border: none; border-top: 1px dashed oklch(78% 0.02 60); margin: 20px 0; }

.today-zone { display: flex; flex-direction: column; }
.zone-label {
  display: inline-flex; align-items: center; gap: 7px;
  margin: 0 0 10px; font-size: 13px; letter-spacing: 0.1em;
  color: var(--ink-on-paper-soft); font-family: var(--font-hand);
}
.zone-ico { color: var(--accent-text); }

/* 心情 chips */
.mood-row { display: flex; flex-wrap: wrap; gap: 9px; }
.mood-chip {
  display: inline-flex; align-items: center; gap: 7px;
  padding: 7px 14px; cursor: pointer;
  background: var(--paper-card-warm); border: 1.4px solid oklch(74% 0.024 62);
  border-radius: var(--r-pill); color: var(--ink-on-paper);
  font-size: 13px; font-family: var(--font-hand); letter-spacing: 0.06em;
  transition: transform 0.16s cubic-bezier(0.34, 1.56, 0.64, 1), border-color 0.16s, background 0.16s;
}
.mood-chip:hover { border-color: var(--accent); transform: translateY(-2px); }
.mood-chip.on { background: var(--chip-bg); border-color: var(--accent); color: var(--chip-fg); font-weight: 700; }
.mood-reply { margin: 13px 0 0; font-size: 13.5px; line-height: 1.8; color: var(--ink-on-paper-soft); }

/* 一问 + 一签：同一张纸上左右两栏，中间一条竖墨线 */
.today-pair { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr); gap: clamp(20px, 3vw, 34px); }
.slip-card { border-left: 1px dashed oklch(78% 0.02 60); padding-left: clamp(18px, 2.6vw, 28px); display: flex; flex-direction: column; }

.q-text { margin: 0 0 14px; font-size: 15.5px; line-height: 1.75; color: var(--ink-on-paper); }
.q-opts { display: flex; flex-wrap: wrap; gap: 9px; }
.q-opt {
  padding: 7px 15px; cursor: pointer; font-size: 13.5px;
  background: var(--paper-card-warm); border: 1.4px solid oklch(74% 0.024 62);
  border-radius: var(--r-pill); color: var(--ink-on-paper); transition: all 0.16s;
}
.q-opt:hover { border-color: var(--accent); }
.q-opt.on { background: var(--chip-bg); border-color: var(--accent); color: var(--chip-fg); font-weight: 700; }
.q-read {
  margin: 14px 0 0; padding-left: 12px; border-left: 3px solid var(--accent);
  font-size: 13.5px; line-height: 1.85; color: var(--ink-on-paper-soft);
}
.q-go { margin-top: 14px; align-self: flex-start; font-size: 14px; padding: 0.5em 1.3em; }

.slip-hint { margin: 4px 0 0; font-size: 13.5px; color: var(--ink-on-paper-soft); }
.slip-text { margin: 4px 0 0; font-size: 17px; line-height: 1.9; color: var(--ink-on-paper); letter-spacing: 0.03em; }
.slip-acts { display: flex; align-items: center; gap: 12px; margin-top: auto; padding-top: 16px; flex-wrap: wrap; }
.slip-fold {
  background: none; border: none; cursor: pointer; padding: 0;
  font-size: 13px; font-family: var(--font-hand); letter-spacing: 0.06em;
  color: var(--accent-text); display: inline-flex; align-items: center; gap: 5px;
}
.slip-fold:hover { text-decoration: underline; }

/* 纪念日：数字用大号楷体，像印章上的日期 */
.anniv-card { display: flex; flex-direction: column; }
.anniv-days { margin: 0 0 2px; display: flex; align-items: baseline; gap: 8px; }
.anniv-days b {
  font-size: clamp(38px, 5vw, 52px); line-height: 1;
  font-family: var(--font-hand); font-weight: 700;
  color: var(--accent-text); letter-spacing: 0.02em;
}
.anniv-days span { font-size: 14px; color: var(--ink-on-paper-soft); letter-spacing: 0.1em; }
.anniv-sub { margin: 0; font-size: 13px; color: var(--ink-on-paper-soft); }
.anniv-form { display: flex; align-items: center; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
.anniv-input {
  font-size: 14px; padding: 7px 12px; color: var(--ink-on-paper);
  background: var(--paper-card-warm); border: 1.4px solid oklch(74% 0.024 62);
  border-radius: var(--r-ctl); outline: none;
}
.anniv-input:focus { border-color: var(--accent); }
.anniv-edit {
  margin-top: 10px; align-self: flex-start; background: none; border: none; cursor: pointer;
  padding: 0; font-size: 12.5px; color: var(--ink-on-paper-soft);
  font-family: var(--font-hand); letter-spacing: 0.08em;
}
.anniv-edit:hover { color: var(--accent-text); text-decoration: underline; }
.anniv-err { margin: 10px 0 0; font-size: 13px; color: var(--danger); }

@media (max-width: 820px) {
  .today-pair { grid-template-columns: 1fr; }
  .slip-card { border-left: none; padding-left: 0; border-top: 1px dashed oklch(78% 0.02 60); padding-top: 18px; }
}
</style>
