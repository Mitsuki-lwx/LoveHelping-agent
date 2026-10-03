<template>
  <div class="nf-page">
    <div class="nf-card letter-card fold">
      <span class="nf-stamp">?</span>
      <h1 class="hand nf-title">这封信找不到收件人</h1>
      <p class="nf-body">地址可能写错了，或者这封信已经被收回。</p>
      <div class="nf-actions">
        <button class="btn-hand primary" @click="$router.push('/')">回到首页</button>
        <button class="btn-hand" @click="$router.back()">返回上一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 404 兜底页（2026-10-02 补）。
 *
 * ⛔ 此前没有通配路由：用户输错地址（或点到失效外链）时，router 什么都不匹配
 * ⇒ 渲染**空白页**，没有任何提示、也没有出路。空白页比"页面不存在"更糟：
 * 用户会以为是产品坏了。
 */
</script>

<style scoped>
.nf-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.nf-card {
  width: min(420px, 94%);
  padding: 38px 34px 30px;
  text-align: center;
  animation: letter-in 0.42s cubic-bezier(0.16, 1, 0.3, 1) both;
}
.nf-stamp {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  border: 2px dashed var(--wine);
  color: var(--wine-deep);
  font-family: var(--font-hand);
  font-size: 24px;
  font-weight: 700;
  transform: rotate(-8deg);
  margin-bottom: 14px;
}
.nf-title { font-size: 22px; margin: 0 0 10px; letter-spacing: 0.1em; color: var(--ink-on-paper); }
.nf-body { font-size: 14px; color: oklch(46% 0.02 62); margin-bottom: 20px; line-height: 1.9; }
.nf-actions { display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; }
</style>
