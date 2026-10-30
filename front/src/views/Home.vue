<template>
  <div class="home">
    <!-- ══ 信头：左文案 / 右相片（真实照片、**彩色**、白边相纸、蜡封压角）══ -->
    <header class="masthead">
      <div class="masthead-copy">
        <p class="brand-mark">LOVEHELPING · 恋爱解忧所</p>
        <h1 class="home-title hand">
          心事，<em>有人慢慢听</em>
        </h1>
        <p class="home-sub">把说不出口的话写成一封信，会有回音。</p>
        <div class="masthead-actions">
          <button class="btn-hand primary" @click="$router.push('/love-chat')">写一封信去</button>
          <button class="btn-hand" @click="$router.push('/history')">翻翻旧信</button>
        </div>
      </div>

      <!--
        相片（自托管，见 front/src/assets/；来源 picsum 的 Unsplash 图库，Unsplash 许可，可自由使用）。
        ⛔ 上一版这里是**随机 seed 的灰度图** —— 用户实测反馈"还是黑白、主题也不符合、不知道有什么意义"。
           现在改成：**逐张看过再挑**（联系表脚本 outputs/picsum-contact-sheet.py）+ 彩色 + 只留真正贴情绪的。
        ⛔ 另有 3 处位置**故意留白**（不放凑数图），需要真图时替换，清单见下方响应与 README 注记。
      -->
      <figure class="photo-print">
        <img :src="themeImages.hero" alt="暖光里的一个人" loading="lazy" decoding="async" />
        <span class="print-seal wax-seal" aria-hidden="true">恋</span>
      </figure>
    </header>

    <!-- ══ 今日：**一张信纸**（不是四张卡堆叠）══ -->
    <TodaySection />

    <!-- ══ 四封信：错落 bento（大小不一，不是等宽等高的一排）══ -->
    <div class="card-wall">
      <div class="entry-card letter-card lift anim-letter-in entry-main" @click="$router.push('/love-chat')">
        <div class="photo entry-photo">
          <img :src="themeImages.letters" alt="" loading="lazy" decoding="async" @error="$event.target.style.display = 'none'" />
          <span class="photo-fallback">信</span>
        </div>
        <span class="entry-wax wax-seal">信</span>
        <h2 class="hand entry-name">解忧信箱</h2>
        <p class="entry-desc">恋爱问答 · 情感陪伴<br>复杂问题自动调度，都写进一封信</p>
        <span class="entry-open">写一封信去</span>
      </div>

      <div class="entry-card letter-card lift anim-letter-in entry-tall" style="animation-delay: 0.06s" @click="$router.push('/sandbox')">
        <span class="entry-wax wax-seal wax-stage">演</span>
        <h2 class="hand entry-name">角色模拟屋</h2>
        <p class="entry-desc">自定义情境与 TA 对话排练<br>预置人设或自写性格<br>把想说的话先练一遍</p>
        <span class="entry-open">去排练</span>
      </div>

      <div class="entry-card letter-card lift anim-letter-in entry-mem" style="animation-delay: 0.12s" @click="$router.push('/memory')">
        <span class="entry-wax wax-seal wax-mem">忆</span>
        <h2 class="hand entry-name">记忆档案</h2>
        <p class="entry-desc">AI 记住的关于你的事<br>偏好在案 · 经历留档</p>
        <span class="entry-open">翻翻档案</span>
      </div>

      <!-- 横条：与上面三张形成"一大两小一横"的节奏，避免一排等宽 -->
      <div class="entry-card letter-card lift anim-letter-in entry-arch" style="animation-delay: 0.18s" @click="$router.push('/history')">
        <span class="entry-wax wax-seal wax-arch">档</span>
        <h2 class="hand entry-name">旧信存档</h2>
        <p class="entry-desc">往来的所有对话都留在这里，哪封信想再看，随时翻开。</p>
        <span class="entry-open">翻开旧信</span>
      </div>
    </div>

    <p class="home-foot">每个深夜的纠结，都值得一封信的位置。</p>
  </div>
</template>

<script setup>
import TodaySection from '../components/TodaySection.vue'
import { useThemeImages } from '../utils/theme-images.js'

// 每个心情主题**一套配图**（切主题时图跟着换；缺图自动回落，见 utils/theme-images.js）
const themeImages = useThemeImages()
</script>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 48px 24px 34px;
  /* ⛔ 必须是 height（不是 min-height）：min-height 会让 .home **跟着内容一起长高**，
     于是它自己不溢出（scrollHeight == clientHeight）⇒ 永远滚不动，
     而真正裁切的是父级 .main-content{overflow:hidden}（实测：滚轮后 scrollTop 仍是 0）。
     height:100% 把它钉在视口高，内容超出才由它自己滚 —— 与其它页同一约定。 */
  height: 100%;
  /* ⛔ 2026-10-07 用户实测："主页怎么不能滚动啊"。
     根因：`.main-content { overflow: hidden }`（每个页面**自己管滚动**：.content / .mem-body /
     .sb-main 都有 overflow-y: auto），**只有首页漏了** ⇒ 内容超出后被裁掉且**滚轮滚不动**。
     ⚠️ 这里用 margin-top/bottom:auto 做"内容少时居中"，而**不用** justify-content:center ——
        后者在内容超出时会把顶部推出可滚区域（ADR-76 踩过同一个坑）。 */
  overflow-y: auto;
}
.home > :first-child { margin-top: auto; }
.home > :last-child { margin-bottom: auto; }

/* ── 信头 ───────────────────────────────────────────── */
.masthead {
  width: 100%;
  max-width: 1020px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.82fr);
  align-items: center;
  gap: clamp(28px, 5vw, 64px);
}
.masthead-copy { min-width: 0; }
.brand-mark {
  font-size: 11.5px;
  letter-spacing: 0.26em;
  color: var(--ink-soft);
  margin: 0 0 16px;
  font-family: var(--font-hand);
}
.home-title {
  font-size: clamp(38px, 5.4vw, 62px);
  line-height: 1.16;
  letter-spacing: 0.02em;
  margin: 0 0 16px;
  color: var(--ink);
}
/* 同族斜体做强调（skill §4.1：不要往中文标题里塞异族衬线） */
.home-title em {
  font-style: italic;
  color: var(--accent-text);
  padding-bottom: 0.06em;
}
.home-sub {
  font-size: 15px;
  line-height: 1.9;
  color: var(--ink-soft);
  margin: 0 0 26px;
  max-width: 34ch;
}
.masthead-actions { display: flex; gap: 12px; flex-wrap: wrap; }

/* ── 相片：白边相纸 + 微旋转 ───────────────────────────── */
.photo-print {
  position: relative;
  margin: 0;
  padding: 12px 12px 46px;
  background: oklch(99.5% 0.004 85);
  border-radius: var(--r-chip);
  box-shadow: var(--sh-3);
  transform: rotate(-2.4deg);
  transition: transform 0.5s cubic-bezier(0.16, 1, 0.3, 1);
}
.photo-print:hover { transform: rotate(-1deg) translateY(-4px); }
.photo-print img {
  display: block;
  width: 100%;
  aspect-ratio: 4 / 5;
  object-fit: cover;
  border-radius: 2px;
  /* 彩色，不压灰度；只做极轻的暖调统一 */
  filter: saturate(1.05) contrast(1.02);
  background: var(--paper-card-warm);
}
/* 蜡封压在相片右下角 —— 让"信"的语义落到相纸上 */
.print-seal {
  position: absolute;
  right: -16px;
  bottom: 8px;
  width: 58px;
  height: 58px;
  font-size: 24px;
  /* 压在相纸上 → 阴影再重一点，才像"盖上去的" */
  box-shadow:
    inset 0 -3px 7px oklch(26% 0.1 25 / 0.5),
    inset 0 2px 5px oklch(82% 0.08 30 / 0.3),
    0 10px 20px -8px oklch(40% 0.12 28 / 0.75);
}

/* ── 四封信：错落 bento ───────────────────────────────── */
.card-wall {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: clamp(14px, 2vw, 20px);
  max-width: 1020px;
  width: 100%;
  margin-top: clamp(26px, 4vw, 42px);
}
.entry-card {
  padding: 26px 24px 22px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.entry-main { grid-column: 1 / 8; grid-row: 1 / 3; padding: 22px 24px 24px; }
.entry-tall { grid-column: 8 / 13; grid-row: 1 / 2; }
.entry-mem { grid-column: 8 / 13; grid-row: 2 / 3; }
.entry-arch {
  grid-column: 1 / 13;
  grid-row: 3 / 4;
  flex-direction: row;
  align-items: center;
  gap: 18px;
  padding: 20px 26px;
}
.entry-arch .entry-wax { margin-bottom: 0; }
.entry-arch .entry-name { font-size: 20px; margin: 0 0 3px; }
.entry-arch .entry-desc { margin: 0; max-width: 62ch; }
.entry-arch .entry-open { margin-left: auto; margin-top: 0; }

.entry-photo {
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: var(--r-paper);
  margin-bottom: 18px;
  box-shadow: 0 8px 20px -14px oklch(20% 0.03 40 / 0.8);
}

/* 尺寸与色阶；蜡封的"实体感"来自共用的 .wax-seal（style.css，一处定义） */
.entry-wax { width: 44px; height: 44px; font-size: 20px; margin-bottom: 14px; }
.wax-stage { background-color: oklch(52% 0.125 28); background-image: radial-gradient(circle at 34% 30%, oklch(60% 0.13 28), oklch(45% 0.12 28)); }
.wax-mem { background-color: oklch(46% 0.115 28); background-image: radial-gradient(circle at 34% 30%, oklch(54% 0.125 28), oklch(40% 0.11 28)); }
.wax-arch { background-color: oklch(40% 0.10 28); background-image: radial-gradient(circle at 34% 30%, oklch(47% 0.11 28), oklch(34% 0.09 28)); }

.entry-name { font-size: 23px; margin: 0 0 8px; color: var(--ink-on-paper); letter-spacing: 0.04em; }
.entry-desc { font-size: 13.5px; line-height: 1.85; color: var(--ink-on-paper-soft); margin: 0 0 16px; }
.entry-open {
  margin-top: auto;
  font-family: var(--font-hand);
  font-size: 13px;
  letter-spacing: 0.16em;
  color: var(--accent-text);
  transition: letter-spacing 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.entry-card:hover .entry-open { letter-spacing: 0.26em; }

.home-foot {
  font-family: var(--font-hand);
  font-size: 12.5px;
  color: var(--ink-faint);
  letter-spacing: 0.16em;
  text-align: center;
  margin: clamp(24px, 3.4vw, 38px) 0 0;
}

/* ── 移动端：单列 ─────────────────────────────────────── */
@media (max-width: 860px) {
  .masthead { grid-template-columns: 1fr; }
  .photo-print { max-width: 380px; transform: rotate(-1.6deg); }
  .card-wall { grid-template-columns: 1fr; }
  .entry-main, .entry-tall, .entry-mem, .entry-arch { grid-column: 1 / -1; grid-row: auto; }
  .entry-arch { flex-direction: column; align-items: flex-start; }
  .entry-arch .entry-open { margin-left: 0; margin-top: 12px; }
  .entry-card { padding: 22px 20px 20px; }
  .home-title { font-size: clamp(32px, 9vw, 44px); }
}
</style>
