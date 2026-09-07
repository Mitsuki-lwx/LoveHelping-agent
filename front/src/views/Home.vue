<template>
  <div class="home">
    <!-- 信头横幅：品牌 + 标语 -->
    <div class="home-masthead">
      <span class="home-stamp anim-stamp">恋</span>
      <p class="brand-mark">LOVEHELPING · 恋爱解忧所</p>
      <h1 class="home-title hand">心事，有人慢慢听</h1>
      <p class="home-sub">在这里把说不出口的话写成一封信 —— 会有回音。</p>
      <div class="ink-divider"></div>
    </div>

    <!-- 两封"信"作为功能入口 -->
    <div class="card-grid">
      <div class="entry-card letter-card fold anim-letter-in" @click="$router.push('/love-chat')">
        <span class="entry-wax">信</span>
        <h2 class="hand entry-name">恋爱解忧信</h2>
        <p class="entry-desc">恋爱心理 · 情感咨询 · 温暖陪伴<br>给每段纠结一段被认真听过的时光</p>
        <span class="entry-open">写一封信去</span>
      </div>
      <div class="entry-card letter-card anim-letter-in" style="animation-delay: 0.08s" @click="$router.push('/manus-chat')">
        <span class="entry-wax wax-tools">帮</span>
        <h2 class="hand entry-name">恋爱全能帮</h2>
        <p class="entry-desc">调用工具处理复杂任务<br>分析 · 计划 · 行动，一次解决</p>
        <span class="entry-open">开始求助</span>
      </div>
    </div>

    <p class="home-foot">每个深夜的纠结，都值得一封信的位置。</p>
  </div>
</template>

<style scoped>
.home {
  height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 34px;
  padding: 40px 20px 30px;
  background:
    radial-gradient(oklch(70% 0.02 78 / 0.18) 0.6px, transparent 0.8px),
    radial-gradient(ellipse at 50% -10%, oklch(96% 0.025 78), transparent 55%),
    var(--paper);
  background-size: 21px 21px, auto, auto;
  text-align: center;
}

/* ---------- 信头 ---------- */
.home-masthead { display: flex; flex-direction: column; align-items: center; gap: 12px; }
.home-stamp {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  border: 2.5px dashed var(--wine);
  color: var(--wine-deep);
  font-family: var(--font-hand);
  font-size: 30px;
  font-weight: 700;
  transform: rotate(-10deg);
  background: oklch(99% 0.008 78 / 0.8);
}
.brand-mark {
  font-size: 11px;
  letter-spacing: 0.42em;
  color: var(--ink-faint);
  margin-top: 2px;
}
.home-title {
  font-size: clamp(34px, 6vw, 52px);
  margin: 0;
  letter-spacing: 0.1em;
  line-height: 1.2;
}
.home-sub {
  font-family: var(--font-hand);
  font-size: 15px;
  color: var(--ink-soft);
  letter-spacing: 0.08em;
}
.ink-divider { width: min(420px, 80%); }

/* ---------- 入口双信 ---------- */
.card-grid {
  display: flex;
  gap: clamp(18px, 4vw, 36px);
  max-width: 760px;
  width: 100%;
  flex-wrap: wrap;
  justify-content: center;
}
.entry-card {
  flex: 1 1 280px;
  min-width: 260px;
  padding: 38px 28px 30px;
  cursor: pointer;
  text-align: center;
  transition: transform 0.26s cubic-bezier(0.16, 1, 0.3, 1), box-shadow 0.26s, border-color 0.26s;
}
.entry-card:hover {
  transform: translateY(-5px) rotate(-0.3deg);
  border-color: var(--wine);
  box-shadow: 0 18px 40px -18px oklch(40% 0.1 25 / 0.5);
}
.entry-wax {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: radial-gradient(circle at 34% 30%, var(--wine), var(--wine-deep));
  color: oklch(98% 0.012 78);
  font-family: var(--font-hand);
  font-size: 21px;
  font-weight: 700;
  box-shadow: inset 0 -3px 6px oklch(30% 0.1 25 / 0.45), 0 6px 14px -6px oklch(40% 0.1 25 / 0.6);
  transform: rotate(-6deg);
  margin-bottom: 14px;
}
.wax-tools { background: radial-gradient(circle at 34% 30%, oklch(56% 0.1 250), oklch(42% 0.09 250)); }
.entry-name { font-size: 22px; margin: 0 0 10px; letter-spacing: 0.14em; }
.entry-desc {
  font-size: 13.5px;
  color: var(--ink-soft);
  line-height: 1.9;
  margin-bottom: 16px;
}
.entry-open {
  font-family: var(--font-hand);
  font-size: 14px;
  color: var(--wine-deep);
  letter-spacing: 0.14em;
  border-bottom: 1px dashed var(--wine);
  padding-bottom: 2px;
  transition: letter-spacing 0.2s;
}
.entry-card:hover .entry-open { letter-spacing: 0.22em; }

.home-foot {
  font-family: var(--font-hand);
  font-size: 12.5px;
  color: var(--ink-faint);
  letter-spacing: 0.16em;
}
</style>
