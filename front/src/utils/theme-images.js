import { ref, computed, onMounted, onUnmounted } from 'vue'
import { getTheme } from './theme.js'

/**
 * 主题配图（每个心情主题一套图，与配色同步切换）。
 *
 * ⛔ 为什么要有这个模块：主题只换配色、配图却共用一套，等于**只做了一半** ——
 *    用户切到"深夜"却看到一张阳光灿烂的照片，主题的说服力当场就没了。
 *
 * 选图方式（不是随机）：picsum 的 seed 是随机的，随机图必然出现"大众面包车配情书"。
 *    所以是**逐张看过再挑**（见 `outputs/picsum-contact-sheet.py` 的联系表），
 *    每套按情绪选：心动=暖光/花/木桌，思念=散景/雨窗/暮色，疗愈=虚化绿意/薄雾，深夜=暗处一盏灯/夜灯/暮湖。
 *
 * 加载方式：`import.meta.glob` **eager** 打进产物（自托管，运行时零外部请求）；
 *    某套缺某个槽位时自动回落到"心动"的那张 —— 缺图**不会开天窗**。
 */
const files = import.meta.glob('../assets/themes/*/*.jpg', { eager: true, import: 'default' })

export const THEME_SLOTS = ['hero', 'today', 'letters']
export const FALLBACK_THEME = '心动'

function assetOf(theme, slot) {
  return files[`../assets/themes/${theme}/${slot}.jpg`]
    || files[`../assets/themes/${FALLBACK_THEME}/${slot}.jpg`]
    || ''
}

/** 取某套主题的三张图（缺则回落） */
export function themeImages(theme) {
  const out = {}
  for (const slot of THEME_SLOTS) out[slot] = assetOf(theme, slot)
  return out
}

/** 全部主题都有图吗（构建期/调试用，避免"以为配了其实没有"） */
export function missingSlots() {
  const missing = []
  for (const theme of Object.keys(byTheme())) {
    for (const slot of THEME_SLOTS) {
      if (!files[`../assets/themes/${theme}/${slot}.jpg`]) missing.push(`${theme}/${slot}`)
    }
  }
  return missing
}

function byTheme() {
  const themes = {}
  for (const key of Object.keys(files)) {
    const m = /themes\/([^/]+)\//.exec(key)
    if (m) themes[m[1]] = true
  }
  return themes
}

/**
 * 响应式取当前主题的配图 —— 主题在导航栏切换时，图要**当场跟着换**（不是只在加载时选一次）。
 * 监听的是既有的 `lh:theme-changed` 事件（`utils/theme.js` 在 setTheme 时广播），不新造一套通知。
 */
export function useThemeImages() {
  const theme = ref(getTheme())
  const onChanged = (e) => { theme.value = e?.detail?.theme || getTheme() }
  onMounted(() => window.addEventListener('lh:theme-changed', onChanged))
  onUnmounted(() => window.removeEventListener('lh:theme-changed', onChanged))
  return computed(() => themeImages(theme.value))
}
