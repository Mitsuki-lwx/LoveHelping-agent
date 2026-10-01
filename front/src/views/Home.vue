<template>
  <div class="home">
    <!-- 信头横幅：品牌 + 标语 -->
    <div class="home-masthead">
      <span class="home-stamp anim-stamp">恋</span>
      <p class="brand-mark">LOVEHELPING · 恋爱解忧所</p>
      <h1 class="home-title hand">心事，有人慢慢听</h1>
      <p class="home-sub">在这里把说不出口的话写成一封信，会有回音。</p>
      <div class="ink-divider"></div>
    </div>

    <!-- 四能力入口：不对称便签墙（主卡占大位，打破等宽阵列） -->
    <div class="card-wall">
      <div class="entry-card letter-card fold anim-letter-in entry-main" @click="$router.push('/love-chat')">
        <span class="entry-wax">信</span>
        <h2 class="hand entry-name">解忧信箱</h2>
        <p class="entry-desc">恋爱问答 · 情感陪伴 · 复杂问题自动调度<br>不用分简单困难，都写进一封信</p>
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
/* ============================================================
   首页（2026-10-01 重做）：深木桌面 + 暖纸便签墙。
   布局变化：等宽 4 卡 → **不对称 2×2**（主卡跨两列），
   这是 taste-skill §9.C「三/四等宽功能卡」的禁令替代方案。
   ============================================================ */
.home {
  height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 34px;
  padding: 40px 20px 30px;
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
  border: 2.5px dashed var(--wine-glow);
  color: var(--wine-glow);
  font-family: var(--font-hand);
  font-size: 30px;
  font-weight: 700;
  transform: rotate(-10deg);
  background: oklch(30% 0.03 62 / 0.6);
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

/* ---------- 便签墙：不对称 2×2（主卡跨两列） ---------- */
.card-wall {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-auto-rows: auto;
  gap: clamp(16px, 3vw, 26px);
  max-width: 860px;
  width: 100%;
}
.entry-card {
  padding: 34px 26px 28px;
  cursor: pointer;
  text-align: center;
  transition: transform 0.26s cubic-bezier(0.16, 1, 0.3, 1), box-shadow 0.26s, border-color 0.26s;
}
/* 主卡：跨两列 + 更高，打破均分（taste-skill §9.C 的替代方案） */
.entry-main {
  grid-column: 1 / -1;
  padding: 42px 32px 34px;
}
.entry-main .entry-wax { width: 54px; height: 54px; font-size: 25px; }
.entry-main .entry-name { font-size: 26px; }
.entry-main .entry-desc { font-size: 14.5px; }
.entry-card:hover {
  transform: translateY(-5px) rotate(-0.3deg);
  border-color: var(--wine-glow);
  box-shadow: 0 22px 44px -20px oklch(12% 0.04 62 / 0.95), 0 0 0 1px oklch(58% 0.13 28 / 0.35);
}
.entry-wax {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: radial-gradient(circle at 34% 30%, var(--wine-glow), var(--wine-deep));
  color: oklch(97% 0.012 80);
  font-family: var(--font-hand);
  font-size: 21px;
  font-weight: 700;
  box-shadow: inset 0 -3px 6px oklch(30% 0.1 25 / 0.45), 0 8px 18px -8px oklch(46% 0.12 28 / 0.8);
  transform: rotate(-6deg);
  margin-bottom: 14px;
}
/* 次卡的火漆各色（低饱和，避免「彩虹按钮」感） */
.wax-stage { background: radial-gradient(circle at 34% 30%, oklch(64% 0.1 70), oklch(50% 0.09 70)); }
.wax-mem { background: radial-gradient(circle at 34% 30%, oklch(60% 0.09 160), oklch(46% 0.08 160)); }
.wax-arch { background: radial-gradient(circle at 34% 30%, oklch(52% 0.05 250), oklch(40% 0.05 250)); }
.entry-name { font-size: 21px; margin: 0 0 10px; letter-spacing: 0.14em; color: var(--ink-on-paper); }
.entry-desc {
  font-size: 13.5px;
  color: oklch(46% 0.02 62);
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

/* ---------- 窄屏（<768px）：不对称布局必须显式塌缩成单列 ---------- */
@media (max-width: 767px) {
  .home { justify-content: flex-start; padding: 28px 16px 24px; }
  .card-wall { grid-template-columns: 1fr; }
  .entry-main { grid-column: auto; }
  .entry-card { padding: 26px 20px 22px; }
}
</style>
