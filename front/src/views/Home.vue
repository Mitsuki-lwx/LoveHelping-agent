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

    <!-- 四能力入口（按后端能力组织，2026-09-07） -->
    <div class="card-grid">
      <div class="entry-card letter-card fold anim-letter-in" @click="$router.push('/love-chat')">
        <span class="entry-wax">信</span>
        <h2 class="hand entry-name">解忧信箱</h2>
        <p class="entry-desc">恋爱问答 · 情感陪伴 · 复杂问题自动调度<br>不用分简单困难——都写进一封信</p>
        <span class="entry-open">写一封信去</span>
      </div>
      <div class="entry-card letter-card anim-letter-in" style="animation-delay: 0.06s" @click="$router.push('/sandbox')">
        <span class="entry-wax wax-stage">演</span>
        <h2 class="hand entry-name">角色模拟屋</h2>
        <p class="entry-desc">自定义情境与 TA 对话排练<br>预置人设或自写性格，把想说的话先练一遍</p>
        <span class="entry-open">去排练</span>
      </div>
      <div class="entry-card letter-card anim-letter-in" style="animation-delay: 0.12s" @click="$router.push('/memory')">
        <span class="entry-wax wax-mem">忆</span>
        <h2 class="hand entry-name">记忆档案</h2>
        <p class="entry-desc">AI 记住的关于你的事<br>偏好在案 · 经历留档 · 随时核对修正</p>
        <span class="entry-open">翻翻档案</span>
      </div>
      <div class="entry-card letter-card anim-letter-in" style="animation-delay: 0.18s" @click="$router.push('/history')">
        <span class="entry-wax wax-arch">档</span>
        <h2 class="hand entry-name">旧信存档</h2>
        <p class="entry-desc">往来的所有对话留在这里<br>哪封信想再看，随时翻开</p>
        <span class="entry-open">翻开旧信</span>
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
.wax-stage { background: radial-gradient(circle at 34% 30%, oklch(62% 0.12 70), oklch(50% 0.11 70)); }
.wax-mem { background: radial-gradient(circle at 34% 30%, oklch(56% 0.1 160), oklch(44% 0.09 160)); }
.wax-arch { background: radial-gradient(circle at 34% 30%, oklch(48% 0.05 250), oklch(36% 0.05 250)); }
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
