import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)

/**
 * ⛔ 全局错误兜底（2026-10-02）：此前**没有** errorHandler —— 任一组件抛错，Vue 默认行为
 * 是只打 console，整棵树停摆 ⇒ 用户看到白屏且**没有任何提示**。
 * 这里至少：① 打出来便于排查 ② 给用户一个可读的提示（而不是静默白屏）。
 * ⚠️ 不吞错：errorHandler 只是**兜底展示**，不改变"错误发生了"这个事实。
 */
app.config.errorHandler = (err, _instance, info) => {
  console.error('[app error]', info, err)
  try {
    const el = document.getElementById('app-fatal')
    if (el) {
      el.style.display = 'block'
      el.textContent = '页面出了点问题，请刷新重试。'
    }
  } catch (_) { /* 兜底自身不许再抛 */ }
}

app.use(router)
app.mount('#app')
