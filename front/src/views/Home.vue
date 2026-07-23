<template>
  <div class="home">
    <h1 class="title">LoveHelping</h1>
    <p class="subtitle">选择一个 AI 应用开始体验</p>
    <div class="card-grid">
      <div class="card" @click="$router.push('/love-chat')">
        <div class="card-icon">💕</div>
        <h2>AI 恋爱专家</h2>
        <p>恋爱心理辅导、情感问题咨询，专业的 AI 恋爱顾问为你排忧解难。</p>
      </div>
      <div class="card" @click="$router.push('/manus-chat')">
        <div class="card-icon">🤖</div>
        <h2>恋爱全能帮</h2>
        <p>全能 AI 助手，调用多种工具完成复杂任务，解决各类问题。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.home {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: linear-gradient(135deg, var(--bg-primary) 0%, var(--bg-secondary) 100%);
}

.title {
  font-size: 36px;
  font-weight: 700;
  color: var(--text-white);
  margin-bottom: 8px;
  letter-spacing: -0.5px;
}

.subtitle {
  font-size: 16px;
  color: var(--text-secondary);
  margin-bottom: 48px;
}

.card-grid {
  display: flex;
  gap: 32px;
  max-width: 720px;
  width: 100%;
}

.card {
  flex: 1;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  padding: 32px 24px;
  cursor: pointer;
  transition: all 0.3s ease;
  text-align: center;
  box-shadow: 0 2px 12px rgba(232, 104, 128, 0.08);
}

.card:hover {
  transform: translateY(-4px);
  border-color: var(--accent);
  box-shadow: 0 8px 32px rgba(232, 104, 128, 0.15);
}

.card-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.card h2 {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-white);
  margin-bottom: 12px;
  font-family: inherit;
}

.card p {
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.6;
}
</style>
