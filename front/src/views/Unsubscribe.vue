<template>
  <div class="unsub-page">
    <div class="unsub-card letter-card fold anim-letter-in">
      <span class="unsub-stamp">信</span>
      <h1 class="unsub-title hand">{{ title }}</h1>
      <p class="unsub-body">{{ message }}</p>
      <p v-if="done" class="unsub-tip">
        想回来随时可以——在个人中心的「邮件回访」里重新打开即可。
      </p>
      <router-link v-else to="/" class="unsub-link">回首页</router-link>
    </div>
  </div>
</template>

<script setup>
/**
 * 一键退订落地页（ADR-92）。
 *
 * <p>⛔ 三个硬约束（都是被邮件客户端的现实逼出来的）：</p>
 * <ol>
 *   <li><b>公开</b>：邮件内置浏览器通常没有登录态，要求登录 = 事实上退不掉；</li>
 *   <li><b>极简</b>：不依赖登录、不依赖任何需要「已初始化应用」的东西，进来就能完成；</li>
 *   <li><b>不白屏</b>：缺参数 / 验签失败 / 网络失败，都要给一句人话。</li>
 * </ol>
 */
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { unsubscribeReengage } from '../api/index.js'

const route = useRoute()
const title = ref('正在处理退订…')
const message = ref('')
const done = ref(false)

onMounted(async () => {
  const u = route.query.u
  const t = route.query.t
  if (!u || !t) {
    title.value = '这个退订链接不完整'
    message.value = '链接可能被邮件客户端截断了。你也可以登录后在个人中心关掉「邮件回访」。'
    return
  }
  try {
    const d = (await unsubscribeReengage(u, t)).data
    if (d && d.code === 200) {
      title.value = '已退订'
      message.value = '不会再收到这类邮件了。'
      done.value = true
      return
    }
    title.value = '退订没成功'
    message.value = (d && d.message) || '链接无效或已过期。你也可以登录后在个人中心关掉「邮件回访」。'
  } catch (e) {
    title.value = '退订没成功'
    message.value = e?.response?.data?.message || '网络不太好，稍后再试，或登录后在个人中心关闭。'
  }
})
</script>

<style scoped>
.unsub-page {
  min-height: 70vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
}
.unsub-card {
  max-width: 420px;
  width: 100%;
  padding: 28px 24px;
  text-align: center;
}
.unsub-stamp {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  border: 1.5px solid var(--accent);
  color: var(--accent-text);
  font-size: 18px;
  margin-bottom: 10px;
}
.unsub-title { font-size: 20px; color: var(--ink-on-paper); margin: 6px 0 10px; }
.unsub-body { font-size: 14px; line-height: 1.7; color: var(--ink-on-paper-soft); }
.unsub-tip { margin-top: 12px; font-size: 12.5px; color: var(--ink-on-paper-faint); }
.unsub-link { display: inline-block; margin-top: 16px; font-size: 13px; color: var(--accent-text); }
</style>
