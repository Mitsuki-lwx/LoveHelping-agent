/**
 * 心情主题（4 套）—— 用户实测反馈"风格有点丧"后的产物。
 *
 * 设计：颜色**全部**在 `style.css` 的 `[data-theme="…"]` 块里，本文件只管"选哪套 + 记住"。
 *      ⛔ 不要把颜色写到这里 —— 两处写颜色 = 两套事实源，必然漂移。
 *
 * 为什么主题名 = 心情名：产品是恋爱/情绪陪伴，主题不该叫"粉色/蓝色"，
 *      而该叫**用户此刻的心情**。所以「今日心情打卡」选完心情可以直接复用同一套名字切主题。
 *
 * 默认「心动」= `:root`（暖光奶油 + 玫瑰粉）。即使 JS 没跑、localStorage 被清，
 * 页面也是明亮的那套，不会退回旧暗色。
 */
export const THEMES = [
  { id: '心动', label: '心动', hint: '暖光奶油 · 玫瑰粉' },
  { id: '思念', label: '思念', hint: '莓紫黄昏 · 淡紫' },
  { id: '疗愈', label: '疗愈', hint: '奶绿清爽 · 薄荷' },
  { id: '深夜', label: '深夜', hint: '暗色 · 适合夜里' },
]

export const DEFAULT_THEME = '心动'
const KEY = 'lwx_theme'
const IDS = THEMES.map((t) => t.id)

export function isTheme(id) {
  return IDS.includes(id)
}

export function getTheme() {
  try {
    const v = localStorage.getItem(KEY)
    return isTheme(v) ? v : DEFAULT_THEME
  } catch (_) {
    return DEFAULT_THEME
  }
}

/** 只改 DOM，不落盘（启动时用，避免"读一次写一次"的无谓写盘） */
export function applyTheme(id) {
  const t = isTheme(id) ? id : DEFAULT_THEME
  document.documentElement.setAttribute('data-theme', t)
  return t
}

export function setTheme(id) {
  const t = applyTheme(id)
  try {
    localStorage.setItem(KEY, t)
  } catch (_) {
    /* 隐私模式下写不了盘：主题当次仍生效，只是不记住 */
  }
  // 广播给关心主题的组件（如「今日心情打卡」要同步选中态）
  window.dispatchEvent(new CustomEvent('lh:theme-changed', { detail: { theme: t } }))
  return t
}
