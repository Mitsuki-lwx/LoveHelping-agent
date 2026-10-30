<template>
  <div class="admin-page">
    <!-- ⛔ 失败提示：此前删除失败只 console.error，弹层一关用户看不到任何反馈 -->
    <p v-if="delError" class="admin-error" role="alert">{{ delError }}</p>
    <div class="page-header">
      <button class="back-btn" @click="$router.push('/')"><PhArrowLeft :size="15" weight="bold" class="ico" aria-hidden="true" /> 返回</button>
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
        <div class="empty-icon"><PhChartBar :size="30" weight="bold" aria-hidden="true" /></div>
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
            <button class="action-btn" title="查看" @click.stop="viewConversation(conv)"><PhEye :size="15" weight="bold" class="ico" aria-hidden="true" /></button>
            <button class="action-btn del-btn" title="删除" @click.stop="confirmDelete(conv)"><PhTrash :size="15" weight="bold" class="ico" aria-hidden="true" /></button>
          </div>
        </div>
      </div>

      <!-- ══ 数据概览（2026-10-14）══
           只读分析层：DAU 趋势 / 留存 / 漏斗 / 功能使用。
           ⛔ excludedTestUsers 必须显著展示：不说明"已排除测试账号"，数字会被误读。 -->
      <section class="analytics-section" aria-label="数据概览">
        <div class="ana-head">
          <h3 class="skill-title">数据概览</h3>
          <div class="range-switch" role="group" aria-label="统计时间窗">
            <button v-for="d in [7, 30, 90]" :key="d"
                    class="range-btn" :class="{ active: analyticsDays === d }"
                    @click="setAnalyticsDays(d)">{{ d }} 天</button>
          </div>
        </div>

        <p class="analytics-generated">统计时间窗：最近 {{ analyticsDays }} 天<template v-if="analytics.generatedAt"> · 生成于 {{ formatTime(analytics.generatedAt) }}</template></p>
        <!-- ⛔ 数据质量必须自述：排除清单是**枚举式**的，永远追不上脚本造账号的速度
             （实测本机库 193 个前缀段）。与其让看板假装数字干净，不如把"排除了多少、占比多高"摆出来。 -->
        <p class="excluded-banner" role="status">
          已排除 {{ analytics.excludedTestUsers.toLocaleString('en-US') }} 个测试账号（占
          {{ Math.round((analytics.dataQuality?.excludedRatio ?? 0) * 100) }}%），以下数字按剩余
          {{ ((analytics.dataQuality?.totalUsers ?? 0) - analytics.excludedTestUsers).toLocaleString('en-US') }} 个账号统计。
        </p>
        <p v-if="analytics.dataQuality?.suspect" class="excluded-banner ana-suspect" role="alert">
          ⚠️ 排除比例超过一半 —— 本库很可能被测试数据严重污染，<strong>这些数字仅供参考，不要据此做产品判断</strong>。
        </p>

        <p v-if="analyticsError" class="admin-error ana-error" role="alert">{{ analyticsError }}</p>
        <p v-if="analyticsError" class="retry-row">
          <button class="range-btn" @click="loadAnalytics">重试</button>
        </p>

        <div v-if="analyticsLoading" class="ana-loading">加载中...</div>
        <template v-else>
          <div class="analytics-cards">
            <div class="stat-card">
              <div class="ana-value">{{ peakDailyActive }}</div>
              <div class="ana-label">峰值日活</div>
            </div>
            <div class="stat-card">
              <div class="ana-value">{{ lastDayActive }}</div>
              <div class="ana-label">期末日活</div>
            </div>
            <div class="stat-card">
              <div class="ana-value">{{ totalNewUsers }}</div>
              <div class="ana-label">新增用户合计</div>
            </div>
          </div>

          <div v-if="analytics.series.length === 0" class="ana-empty">该时间窗内暂无数据</div>
          <template v-else>
            <div class="ana-block">
              <h4 class="ana-title">每日活跃用户趋势</h4>
              <div class="trend-chart">
                <div v-for="pt in analytics.series" :key="pt.date" class="trend-bar"
                     :style="{ height: trendHeight(pt) }" :title="`${pt.date}：活跃 ${pt.activeUsers} · 新增 ${pt.newUsers} · 消息 ${pt.messages}`" />
              </div>
              <div class="trend-axis">
                <span>{{ analytics.series[0]?.date }}</span>
                <span>{{ analytics.series[analytics.series.length - 1]?.date }}</span>
              </div>
            </div>

            <div class="ana-block">
              <h4 class="ana-title">留存（样本 {{ retention.cohortSize }} 人）</h4>
              <div class="retention-row">
                <span class="retention-item">次日留存 <b class="retention-pct">{{ retentionPct(retention.d1) }}</b></span>
                <span class="retention-item">7 日留存 <b class="retention-pct">{{ retentionPct(retention.d7) }}</b></span>
              </div>
            </div>

            <div class="ana-block">
              <h4 class="ana-title">转化漏斗</h4>
              <div class="funnel-list">
                <div v-for="(st, i) in analytics.funnel" :key="st.step" class="funnel-row">
                  <span class="funnel-step">{{ st.step }}</span>
                  <span class="funnel-bar-wrap">
                    <span class="funnel-bar" :style="{ width: funnelPct(st.count) + '%' }" />
                  </span>
                  <span class="funnel-num">{{ st.count }} 人（{{ funnelPct(st.count) }}%）</span>
                </div>
              </div>
            </div>

            <div class="ana-block">
              <h4 class="ana-title">功能使用</h4>
              <div v-if="analytics.features.length === 0" class="ana-empty">暂无功能使用数据</div>
              <div v-else class="funnel-list">
                <div v-for="f in analytics.features" :key="f.name" class="funnel-row">
                  <span class="funnel-step">{{ f.name }}</span>
                  <span class="funnel-bar-wrap">
                    <span class="funnel-bar" :style="{ width: featurePct(f.count) + '%' }" />
                  </span>
                  <span class="funnel-num">{{ f.count }} 次</span>
                </div>
              </div>
            </div>
          </template>
        </template>
      </section>

      <!-- ══ 进化技能审核（2026-10-13）══
           ⛔ 只拉**一页**（默认 50 条）：生产积压约 1.2 万条，全量拉取会打爆接口与页面。
           列表口径是 status=PENDING ⇒ 通过/驳回后该条从"待审"里消失，直接从本地列表移除即可。 -->
      <section class="skill-section">
        <div class="skill-head">
          <h3 class="skill-title">进化技能审核</h3>
          <span class="count-badge">待审 {{ skills.length }} 条 · 仅显示前 {{ SKILL_LIMIT }} 条</span>
        </div>
        <p class="skill-note">
          候选由真实对话自动沉淀，当前积压约 {{ SKILL_BACKLOG.toLocaleString('en-US') }} 条 ——
          逐条审核不现实。列表只展示最新的一批，低分候选可用下面的按钮一次清掉。
        </p>
        <div class="bulk-row">
          <button class="bulk-btn" :disabled="bulkBusy" @click="bulkReject">
            {{ bulkBusy ? '处理中…' : `批量驳回低分（质量分 ≤ ${BULK_MAX_SCORE}）` }}
          </button>
          <span v-if="bulkMsg" class="bulk-msg" role="status">{{ bulkMsg }}</span>
        </div>

        <p v-if="skillError" class="admin-error skill-error" role="alert">{{ skillError }}</p>

        <div v-if="skillsLoading" class="skill-loading">加载中...</div>
        <div v-else-if="skills.length === 0" class="skill-empty">暂无待审技能</div>
        <div v-else class="skill-list">
          <div v-for="s in skills" :key="s.id" class="skill-card">
            <div class="skill-info">
              <div class="skill-name">{{ s.skillName || '未命名技能' }}</div>
              <div class="skill-desc">{{ truncate(s.description, 200) }}</div>
              <div class="skill-meta">
                <span>质量分 {{ s.qualityScore ?? '—' }}</span>
                <span class="sep">·</span>
                <span>{{ formatTime(s.createdAt) }}</span>
              </div>
            </div>
            <div class="skill-btns">
              <button class="audit-btn approve" @click="audit(s, 'APPROVED')">通过</button>
              <button class="audit-btn reject" @click="audit(s, 'REJECTED')">驳回</button>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- Detail modal -->
    <div v-if="detailConv" class="modal-overlay" @click.self="detailConv = null">
      <div class="modal">
        <div class="modal-header">
          <h3>对话详情</h3>
          <button class="close-btn" @click="detailConv = null"><PhX :size="18" weight="bold" aria-hidden="true" /></button>
        </div>
        <div class="modal-body">
          <div v-if="detailLoading">加载中...</div>
          <div v-else-if="detailMessages.length === 0">无消息</div>
          <div v-else class="detail-msg-list">
            <div v-for="(msg, idx) in detailMessages" :key="msg.id || idx"
                 :class="['detail-msg', msg.messageType === 'USER' ? 'msg-user' : 'msg-ai']">
              <div class="msg-role">{{ msg.messageType === 'USER' ? '你' : 'AI' }}</div>
              <div class="msg-text">{{ truncate(msg.text, 500) }}</div>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <span>{{ detailMessages.length }} 条消息</span>
          <button class="delete-btn" @click="confirmDelete(detailConv)">删除此对话</button>
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
import { PhArrowLeft, PhChartBar, PhEye, PhTrash, PhX } from '@phosphor-icons/vue'
import { listAllConversations, getConversationMessages, clearConversation, listPendingSkills, countPendingSkills, auditSkill, bulkRejectSkills, getAnalyticsOverview } from '../api/index.js'
import { getUser } from '../utils/auth.js'

const router = useRouter()
const conversations = ref([])
const loading = ref(true)
const detailConv = ref(null)
/** 操作失败的可读提示。⛔ 此前删除失败只 console.error —— 用户点完"什么都没发生"，
 *  与"成功"在界面上无法区分（静默失败）。 */
const delError = ref('')
const detailMessages = ref([])
const detailLoading = ref(false)
const deleteTarget = ref(null)

// ---- 数据概览（2026-10-14）----
/** 可选时间窗；默认 30 天（与后端契约一致）。 */
const ANALYTICS_RANGES = [7, 30, 90]
const analyticsDays = ref(30)
const analyticsLoading = ref(true)
const analyticsError = ref('')
/** 缺省兜底：接口没回来/字段缺失时也保持可渲染结构，不白屏。 */
const analytics = ref({
  days: 30,
  generatedAt: '',
  excludedTestUsers: 0,
  series: [],
  retention: { cohortSize: 0, d1: 0, d7: 0 },
  funnel: [],
  features: [],
})

/** 峰值日活：series 里 activeUsers 的最大值（能从数据算出的口径，不编造"总活跃"）。 */
const peakDailyActive = computed(() =>
  analytics.value.series.reduce((m, p) => Math.max(m, Number(p.activeUsers) || 0), 0)
)
/** 期末日活：时间窗最后一天的 activeUsers（补齐的天为 0，如实显示）。 */
const lastDayActive = computed(() =>
  Number(analytics.value.series[analytics.value.series.length - 1]?.activeUsers) || 0
)
/** 新增用户合计：窗口内 newUsers 相加。 */
const totalNewUsers = computed(() =>
  analytics.value.series.reduce((s, p) => s + (Number(p.newUsers) || 0), 0)
)
const retention = computed(() => analytics.value.retention || { cohortSize: 0, d1: 0, d7: 0 })

function retentionPct(ratio) {
  return `${Math.round((Number(ratio) || 0) * 100)}%`
}

/** 趋势条高度：按当天 activeUsers 相对峰值归一化（0 值给最小可见高度，别彻底消失）。 */
function trendHeight(pt) {
  const peak = peakDailyActive.value
  const v = Number(pt.activeUsers) || 0
  const pct = peak > 0 ? Math.max((v / peak) * 100, 4) : 4
  return `${pct}%`
}

/** 漏斗第 i 步相对**首步**的百分比；首步为 0 时按 100%（避免除零，显示如实人数）。 */
function funnelPct(count) {
  const first = Number(analytics.value.funnel[0]?.count) || 0
  if (first <= 0) return 100
  return Math.round((Number(count) || 0) * 100 / first)
}

/** 功能使用条宽：相对最大值归一化（降序列表首项即最大）。 */
function featurePct(count) {
  const max = Math.max(...analytics.value.features.map(f => Number(f.count) || 0), 0)
  if (max <= 0) return 0
  return Math.max(Math.round((Number(count) || 0) * 100 / max), 4)
}

async function loadAnalytics() {
  analyticsLoading.value = true
  analyticsError.value = ''
  try {
    const res = await getAnalyticsOverview(analyticsDays.value)
    const data = res?.data?.data
    if (data) {
      analytics.value = {
        days: Number(data.days) || analyticsDays.value,
        generatedAt: data.generatedAt || '',
        excludedTestUsers: Number(data.excludedTestUsers) || 0,
        // ⛔ 这里是**白名单式挑字段**：后端加字段前端会静默丢（实测踩过 —— dataQuality 没接上，
        //    界面显示"已排除 2,521 个（占 0%）、剩余 -2,521 个"）。新增后端字段必须同步这里。
        dataQuality: data.dataQuality || null,
        series: Array.isArray(data.series) ? data.series : [],
        retention: data.retention || { cohortSize: 0, d1: 0, d7: 0 },
        funnel: Array.isArray(data.funnel) ? data.funnel : [],
        features: Array.isArray(data.features) ? data.features : [],
      }
    } else {
      analytics.value = { ...analytics.value, series: [], funnel: [], features: [], excludedTestUsers: 0 }
    }
  } catch (e) {
    console.error(e)
    analyticsError.value = e?.response?.data?.message || '数据概览加载失败，请稍后重试'
    // 失败时清空 series，走空态而不是渲染上一窗的旧数据（会误导）
    analytics.value = { ...analytics.value, series: [], funnel: [], features: [] }
  } finally {
    analyticsLoading.value = false
  }
}

/** 切换时间窗 ⇒ 立即按新 days 重新拉取。 */
function setAnalyticsDays(days) {
  if (analyticsDays.value === days) return
  analyticsDays.value = days
  loadAnalytics()
}

// ---- 进化技能审核（2026-10-13）----
/** 一次只拉这么多：积压上万条，全量拉会打爆接口与页面（后端也按 limit 截断）。 */
const SKILL_LIMIT = 50
/** 当前积压量：**从后端拉**，不许写死 —— 写死的数会与真实积压漂移，界面就在撒谎。 */
const SKILL_BACKLOG = ref(0)
/** 批量驳回的质量分阈值 */
const BULK_MAX_SCORE = 5
const skills = ref([])
const skillsLoading = ref(true)
const skillError = ref('')
const bulkBusy = ref(false)
const bulkMsg = ref('')

const totalMessages = computed(() =>
  conversations.value.reduce((s, c) => s + Number(c.message_count || 0), 0)
)
// ⛔ 按 **user_id** 去重（不是 conversation_id）：后者等于会话数，
//    同一用户开多个会话会被算成多个用户 —— 标签写着"用户数"却是会话数，属口径错误（ADR-77）。
const uniqueUsers = computed(() => new Set(
  conversations.value.map(c => c.user_id).filter(Boolean)
).size)

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
  delError.value = ''
  try {
    await clearConversation(deleteTarget.value.conversation_id)
    conversations.value = conversations.value.filter(c => c.conversation_id !== deleteTarget.value.conversation_id)
    if (detailConv.value?.conversation_id === deleteTarget.value.conversation_id) {
      detailConv.value = null
    }
  } catch (e) {
    console.error(e)
    // ⛔ 用户必须知道"没删掉"，否则会以为删了（本仓纪律：静默失败是事故）
    delError.value = e?.response?.data?.message || '删除失败，请稍后重试'
  } finally {
    deleteTarget.value = null
  }
}

/** 拉待审技能（只有一页）。失败不能白屏：给出可读提示并收敛 loading。 */
async function loadSkills() {
  skillsLoading.value = true
  skillError.value = ''
  try {
    const res = await listPendingSkills('PENDING', SKILL_LIMIT)
    const list = res?.data?.data
    skills.value = Array.isArray(list) ? list : []
    // 积压数字从后端拿（拿不到就保持 0，不猜、不写死）
    try {
      const cnt = await countPendingSkills('PENDING')
      SKILL_BACKLOG.value = Number(cnt?.data?.data) || 0
    } catch (_) { /* 计数失败不影响列表 */ }
  } catch (e) {
    skillError.value = e?.response?.data?.message || '技能列表加载失败，请稍后重试'
    skills.value = []
  } finally {
    skillsLoading.value = false
  }
}

/** 单条审核。通过/驳回后该条不再属于"待审"口径 ⇒ 从列表移除（不留在界面上骗人）。 */
async function audit(skill, status) {
  skillError.value = ''
  bulkMsg.value = ''
  try {
    const res = await auditSkill(skill.id, status)
    if (res?.data && res.data.success === false) {
      skillError.value = res.data.message || '审核失败'
      return
    }
    skills.value = skills.value.filter((s) => s.id !== skill.id)
  } catch (e) {
    skillError.value = e?.response?.data?.message || '审核失败，请稍后重试'
  }
}

/**
 * 批量驳回低分候选（**不可逆**）。
 * ⛔ 必须先二次确认：一次调用可能清掉上千条候选，误点没有撤销按钮。
 * 用 window.confirm 而不是自研弹层 —— 不为此引入依赖，且原生确认无法被误触跳过。
 */
async function bulkReject() {
  skillError.value = ''
  bulkMsg.value = ''
  const ok = window.confirm(
    `确定批量驳回质量分 ≤ ${BULK_MAX_SCORE} 的待审技能吗？此操作不可撤销。`
  )
  if (!ok) return
  bulkBusy.value = true
  try {
    const res = await bulkRejectSkills(BULK_MAX_SCORE, 2000)
    const raw = res?.data?.data ?? res?.data
    const n = typeof raw === 'number' ? raw : null
    bulkMsg.value = n === null
      ? (res?.data?.message || '批量驳回已完成')
      : `已驳回 ${n} 条`
    await loadSkills()
  } catch (e) {
    skillError.value = e?.response?.data?.message || '批量驳回失败，请稍后重试'
  } finally {
    bulkBusy.value = false
  }
}

onMounted(() => {
  const user = getUser()
  if (!user || user.role !== 'ADMIN') {
    router.push('/')
    return
  }
  load()
  loadSkills()
  loadAnalytics()
})
</script>

<style scoped>
.admin-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--desk);
}

.page-header {
  display: flex; align-items: center; gap: 12px;
  padding: 16px 24px; background: var(--desk-raised);
  border-bottom: 1px solid var(--ink-line); flex-shrink: 0;
}

.back-btn {
  background: none; border: none; color: var(--ink-soft);
  font-size: 14px; cursor: pointer; padding: 4px 8px; border-radius: var(--r-ctl);
}
.back-btn:hover { color: var(--ink); background: var(--desk-raised); }

.page-title { font-size: 16px; font-weight: 600; color: var(--ink); }
.count-badge { font-size: 12px; color: var(--ink-soft); background: var(--desk-raised); padding: 2px 10px; border-radius: var(--r-ctl); }

.role-badge {
  font-size: 11px; font-weight: 600; padding: 2px 10px; border-radius: var(--r-ctl);
  margin-left: auto;
}
.role-badge.admin { background: #e86880; color: #fff; }

.content { flex: 1; overflow-y: auto; padding: 24px; max-width: 800px; width: 100%; margin: 0 auto; }

.stats-row { display: flex; gap: 16px; margin-bottom: 24px; }
.stat-card {
  flex: 1; background: var(--desk-raised); border: 1px solid var(--ink-line);
  border-radius: var(--r-card); padding: 20px; text-align: center;
}
.stat-value { font-size: 28px; font-weight: 700; color: var(--accent-text); }
.stat-label { font-size: 12px; color: var(--ink-soft); margin-top: 4px; }

.loading-state, .empty-state {
  display: flex; align-items: center; justify-content: center;
  height: 200px; color: var(--ink-soft);
}
.empty-icon { font-size: 48px; margin-bottom: 12px; }

.conv-list { display: flex; flex-direction: column; gap: 8px; }
.conv-card {
  display: flex; align-items: center; justify-content: space-between;
  background: var(--desk-raised); border: 1px solid var(--ink-line);
  border-radius: var(--r-card); padding: 16px; cursor: pointer; transition: all 0.2s;
}
.conv-card:hover { border-color: var(--accent); }

.conv-info { flex: 1; min-width: 0; }
.conv-id { font-size: 13px; color: var(--ink); font-family: monospace; margin-bottom: 4px; }
.conv-meta { font-size: 12px; color: var(--ink-soft); }
.sep { margin: 0 4px; }
.conv-actions { display: flex; gap: 4px; flex-shrink: 0; margin-left: 12px; }
.action-btn {
  background: none; border: 1px solid transparent; cursor: pointer;
  font-size: 16px; padding: 4px 8px; border-radius: var(--r-ctl); opacity: 0.5;
}
.action-btn:hover { opacity: 1; background: var(--desk-raised); }

.modal-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center; z-index: 100;
}
.modal {
  background: var(--desk-raised); border-radius: var(--r-card); width: 90%;
  max-width: 560px; max-height: 80vh; display: flex; flex-direction: column;
  box-shadow: 0 8px 32px rgba(0,0,0,0.2);
}
.confirm-modal { max-width: 400px; padding: 32px; text-align: center; }
.confirm-modal h3 { font-size: 18px; color: var(--ink); margin-bottom: 12px; }
.confirm-modal p { font-size: 14px; color: var(--ink-soft); margin-bottom: 24px; }
.confirm-actions { display: flex; gap: 12px; justify-content: center; }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px 0; }
.modal-header h3 { font-size: 16px; color: var(--ink); }
.close-btn { background: none; border: none; color: var(--ink-soft); font-size: 18px; cursor: pointer; padding: 4px 8px; border-radius: var(--r-ctl); }
.close-btn:hover { background: var(--desk-raised); }
.modal-body { flex: 1; overflow-y: auto; padding: 16px 24px; }
.detail-msg-list { display: flex; flex-direction: column; gap: 8px; }
.detail-msg { padding: 10px 14px; border-radius: var(--r-ctl); font-size: 13px; line-height: 1.5; }
.msg-user { background: rgba(232, 104, 128, 0.08); }
.msg-ai { background: var(--desk-raised); }
.msg-role { font-size: 11px; font-weight: 600; color: var(--accent-text); margin-bottom: 4px; }
.msg-text { color: var(--ink); word-break: break-word; white-space: pre-wrap; }
.modal-footer { display: flex; align-items: center; justify-content: space-between; padding: 12px 24px 20px; border-top: 1px solid var(--ink-line); }
.cancel-btn { padding: 8px 20px; border: 1px solid var(--ink-line); border-radius: var(--r-ctl); background: transparent; color: var(--ink); cursor: pointer; font-size: 13px; }
.cancel-btn:hover { background: var(--desk-raised); }
.delete-btn { padding: 8px 20px; border: none; border-radius: var(--r-ctl); background: #e74c3c; color: #fff; cursor: pointer; font-size: 13px; }
.delete-btn:hover { background: #c0392b; }
.admin-error {
  margin: 0 0 12px; padding: 8px 12px; border-radius: var(--r-ctl);
  background: oklch(32% 0.06 27); color: oklch(82% 0.1 27);
  border: 1px solid oklch(46% 0.08 27); font-size: 13px;
}

/* ---------- 进化技能审核（2026-10-13）---------- */
.skill-section { margin-top: 28px; border-top: 1px dashed var(--ink-line); padding-top: 18px; }
.skill-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.ana-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.skill-title { font-size: 15px; color: var(--ink); }
.skill-note { font-size: 12.5px; line-height: 1.8; color: var(--ink-soft); margin-bottom: 12px; }
.bulk-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
.bulk-btn {
  padding: 8px 16px; font-size: 13px; cursor: pointer;
  border: 1px solid var(--danger); border-radius: var(--r-ctl);
  background: transparent; color: var(--danger);
}
.bulk-btn:hover:not(:disabled) { background: var(--danger); color: var(--paper-card); }
.bulk-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.bulk-msg { font-size: 13px; color: var(--ok); }
.skill-loading, .skill-empty, .ana-loading, .ana-empty {
  display: flex; align-items: center; justify-content: center;
  height: 120px; color: var(--ink-soft); font-size: 13px;
}
.skill-list { display: flex; flex-direction: column; gap: 8px; }
.skill-card {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 12px;
  background: var(--desk-raised); border: 1px solid var(--ink-line);
  border-radius: var(--r-card); padding: 14px 16px;
}
.skill-info { flex: 1; min-width: 0; }
.skill-name { font-size: 13.5px; font-weight: 600; color: var(--ink); margin-bottom: 4px; }
.skill-desc { font-size: 12.5px; line-height: 1.7; color: var(--ink-soft); margin-bottom: 6px; word-break: break-word; }
.skill-meta { font-size: 11.5px; color: var(--ink-faint); }
.skill-btns { display: flex; gap: 6px; flex-shrink: 0; }
.audit-btn {
  padding: 6px 14px; font-size: 12.5px; border-radius: var(--r-pill); cursor: pointer;
  border: 1px solid var(--ink-line); background: transparent; color: var(--ink-soft);
}
.audit-btn.approve:hover { border-color: var(--ok); color: var(--ok); }
.audit-btn.reject:hover { border-color: var(--danger); color: var(--danger); }

/* ---------- 数据概览（2026-10-14）---------- */
.analytics-section { margin-top: 28px; border-top: 1px dashed var(--ink-line); padding-top: 18px; }
.range-switch { display: flex; gap: 6px; }
.range-btn {
  padding: 4px 12px; font-size: 12.5px; cursor: pointer;
  border: 1px solid var(--ink-line); border-radius: var(--r-pill);
  background: transparent; color: var(--ink-soft);
}
.range-btn:hover { border-color: var(--accent); color: var(--ink); }
.range-btn.active { background: var(--accent); border-color: var(--accent); color: var(--paper-card); }
.analytics-generated { font-size: 12px; color: var(--ink-faint); margin-bottom: 8px; }
.ana-suspect { border-left-color: var(--accent-deep); font-weight: 600; }
.excluded-banner {
  font-size: 12.5px; color: var(--accent-text);
  background: var(--desk-raised); border: 1px dashed var(--accent);
  border-radius: var(--r-ctl); padding: 8px 12px; margin-bottom: 14px;
}
.retry-row { margin: 0 0 14px; }
.analytics-cards { display: flex; gap: 12px; margin-bottom: 18px; }
.analytics-cards .stat-card { padding: 14px 8px; }
.ana-value { font-size: 22px; font-weight: 700; color: var(--accent-text); }
.ana-label { font-size: 12px; color: var(--ink-soft); margin-top: 4px; }
.ana-block { margin-bottom: 18px; }
.ana-error { margin-bottom: 10px; }
.ana-title { font-size: 13px; font-weight: 600; color: var(--ink); margin-bottom: 8px; }

/* 趋势：纯 CSS 条形图（不引入图表库） */
.trend-chart {
  display: flex; align-items: flex-end; gap: 2px;
  height: 90px; padding: 0 2px;
  border-bottom: 1px solid var(--ink-line);
}
.trend-bar {
  flex: 1; min-width: 3px; background: var(--accent);
  border-radius: 2px 2px 0 0; opacity: 0.75;
}
.trend-bar:hover { opacity: 1; }
.trend-axis { display: flex; justify-content: space-between; font-size: 11px; color: var(--ink-faint); margin-top: 4px; }

.retention-row { display: flex; gap: 24px; }
.retention-item { font-size: 13px; color: var(--ink-soft); }
.retention-pct { font-size: 16px; color: var(--accent-text); margin-left: 4px; }

/* 横向条形（漏斗/功能使用共用） */
.funnel-list { display: flex; flex-direction: column; gap: 8px; }
.funnel-row { display: flex; align-items: center; gap: 10px; }
.funnel-step { flex: 0 0 72px; font-size: 12.5px; color: var(--ink-soft); text-align: right; }
.funnel-bar-wrap { flex: 1; height: 14px; background: var(--desk-raised); border-radius: var(--r-ctl); overflow: hidden; }
.funnel-bar { display: block; height: 100%; background: var(--accent); border-radius: var(--r-ctl); }
.funnel-num { flex: 0 0 auto; font-size: 12px; color: var(--ink); }
</style>
