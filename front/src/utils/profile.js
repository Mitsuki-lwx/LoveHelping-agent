/**
 * 个人资料的**唯一取数点**（昵称 / 头像 emoji / 签名 / 纪念日）。
 *
 * ⛔ 为什么必须有这个模块（2026-10-06 实测教训）：
 *   首页「今日」卡片一开始自己在 onMounted 里调 `/auth/me`。后果有两个，都很难看：
 *     1. 进首页就多一个请求（本来导航栏已经缓存了用户）；
 *     2. **单测里会真的去连网** —— 跑出 ECONNRESET，还把整套测试拖慢到撞破 5s 超时
 *        （`router.test.js` 那条"已登录 → 可进入受保护页"就是这样红的）。
 *   所以：**只有这里发请求**，其他组件一律读 `getUser()` 缓存。
 */
import { getMe } from '../api/index.js'
import { getUser, setUser, isAuthenticated } from './auth.js'

/** 只取我们认识的字段，别把后端整包塞进 localStorage */
const FIELDS = ['username', 'role', 'nickname', 'avatarEmoji', 'bio', 'anniversaryDate',
  // 2026-10-13 合规：未成年人模式与出生年份也要缓存 —— 聊天页的未成年人提示条读缓存，
  // 不为了一个布尔值再打一次 /auth/me。
  'minorMode', 'birthYear',
  // 2026-10-14 onboarding：引导是否已看过。⛔ 这里是**白名单**，后端加字段不同步加进来就会被静默丢弃
  // （同一天已在分析层踩过一次：dataQuality 没接上，界面显示"剩余 -2,521 个账号"）。
  'onboarded',
  // 2026-10-14 回访邮件同意（ADR-92）—— 同一条白名单纪律：不加就静默丢
  'reengageConsent']

function pick(d) {
  const out = {}
  for (const k of FIELDS) if (d[k] !== undefined) out[k] = d[k]
  return out
}

/** 拉一次资料并写进缓存；失败**不抛**（首页不能因为资料接口挂了就打不开） */
export async function refreshProfile() {
  if (!isAuthenticated()) return null
  try {
    const res = await getMe()
    const d = res?.data
    if (!d || d.success === false) return null
    const merged = { ...(getUser() || {}), ...pick(d) }
    setUser(merged)
    window.dispatchEvent(new CustomEvent('lh:user-updated'))
    return merged
  } catch (_) {
    return null
  }
}

/** 读缓存（不发请求）。组件里要显示资料一律用这个。 */
export function cachedProfile() {
  return getUser() || {}
}

/** 本地合并一小块资料（保存成功后即时生效，省一次往返） */
export function mergeProfile(patch) {
  const merged = { ...(getUser() || {}), ...patch }
  setUser(merged)
  window.dispatchEvent(new CustomEvent('lh:user-updated'))
  return merged
}
