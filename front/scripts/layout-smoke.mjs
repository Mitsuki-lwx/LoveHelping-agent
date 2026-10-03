#!/usr/bin/env node
/**
 * 布局冒烟（ADR-76）—— 补上 **jsdom 抓不到的那一层**。
 *
 * ## 为什么必须有它
 *
 * 2026-10-02 用户实测报：「首页大标题被上面菜单栏挡住一半」。
 * 根因是 `justify-content: center` + `overflow-y: auto` 在**内容高于容器**时把溢出推到顶部，
 * 而顶部那截**滚不回去**（`scrollTop` 恒为 0）。
 *
 * ⛔ 这类 bug 的特点：**vitest/jsdom 永远抓不到** —— jsdom 没有布局引擎，
 * `getBoundingClientRect()` 全是 0，`justify-content` 不做任何事。
 * 27 条组件测试全绿而页面是坏的，就是这个原因。
 *
 * ## 判据（都是"用户会立刻看到"的事实）
 *
 * 1. **不被顶栏遮挡**：主内容的第一个可见元素的 top ≥ 导航栏 bottom；
 * 2. **能滚到底**：容器滚到底时，最后一个可见元素的 bottom ≤ 视口高（而不是永远够不着）；
 * 3. **没有横向溢出**：`scrollWidth <= clientWidth + 1`（手机上左右乱滑的常见根因）；
 * 4. **文字不是不可读的**：正文颜色与背景色**明显不同**（防"暖白字浮在暖纸上"那类回归 ——
 *    我这次重做真犯过一次，见 ADR-76）。
 *
 * ⛔ 它**不**替代视觉评审，只守这几条"用户一定会立刻发现"的破相。
 *
 * 用法：
 *   node scripts/layout-smoke.mjs --base http://127.0.0.1:9010/api --user u --pass p
 *   （需要一个已运行的实例；未给 --user 时只查不需要登录的 /login）
 * 退出码 0 = 全过；1 = 有破相。
 */
import { chromium } from 'playwright-core'

/**
 * ⛔ 必须同时支持 `--k=v` 与 `--k v`：我第一版只支持前者，
 * 而调用时用了空格形式 → 参数**静默变 undefined**、登录块被整个跳过，
 * 脚本却报"3/3 通过"（只测了登录页）。**参数解析失败不是"没用参数"，是配置错误。**
 */
function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (!a.startsWith('--')) continue
    const body = a.slice(2)
    if (body.includes('=')) {
      const [k, ...rest] = body.split('=')
      out[k] = rest.join('=')
    } else {
      out[body] = argv[i + 1]
      i++
    }
  }
  return out
}
const args = parseArgs(process.argv.slice(2))
const BASE = (args.base || 'http://127.0.0.1:9010/api').replace(/\/$/, '')
/**
 * ⛔ 浏览器来源必须可移植：本机只有 Edge、CI（ubuntu）只有 Chrome。
 * 硬编码路径会让这道门在 CI 上**直接报错**（而不是跑起来），最后被人删掉。
 * 顺序：BROWSER_PATH 显式指定 → 系统 Chrome → 系统 Edge。
 */
async function launchBrowser() {
  if (process.env.BROWSER_PATH) {
    return chromium.launch({ executablePath: process.env.BROWSER_PATH, headless: true })
  }
  const errors = []
  for (const channel of ['chrome', 'msedge']) {
    try {
      return await chromium.launch({ channel, headless: true })
    } catch (e) {
      errors.push(`${channel}: ${e.message.split('\n')[0]}`)
    }
  }
  throw new Error('找不到可用浏览器（试过 chrome / msedge）。\n   ' + errors.join('\n   ') +
    '\n   → 本机可用 BROWSER_PATH 指定，如 BROWSER_PATH="C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"')
}

const VIEWPORTS = [
  { name: 'desktop', width: 1365, height: 768 },
  { name: 'narrow', width: 900, height: 620 },
  { name: 'mobile', width: 390, height: 780 },
]

const results = []
const record = (v, route, ok, detail) => {
  results.push({ viewport: v, route, ok, detail })
  console.log(`  ${ok ? '✅' : '❌'} [${v}] ${route}  ${detail}`)
}

/** 在页面里跑的布局检查（返回可读结论，不在 Node 侧推断） */
const PROBE = `(() => {
  const nav = document.querySelector('.top-nav');
  const navBottom = nav ? nav.getBoundingClientRect().bottom : 0;
  const out = { issues: [], info: {} };

  // 找"可滚动的主容器"：高度受限且 overflow-y 为 auto/scroll 的那个
  const scrollers = [...document.querySelectorAll('*')].filter(el => {
    const cs = getComputedStyle(el);
    return (cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.clientHeight > 80;
  });
  const scroller = scrollers.sort((a, b) => b.clientHeight - a.clientHeight)[0] || document.scrollingElement;

  // 顶部主标题：h1（页面都有一处主标题）
  const h1 = document.querySelector('h1');
  if (h1) {
    out.info.h1Top = h1.getBoundingClientRect().top;
    out.info.navBottom = navBottom;
    if (h1.getBoundingClientRect().top < navBottom - 1) {
      out.issues.push('主标题被顶栏遮住：h1.top=' + Math.round(h1.getBoundingClientRect().top) + ' < nav.bottom=' + Math.round(navBottom));
    }
  }

  // 横向溢出 —— ⛔ 只报**非有意**的溢出：
  //    overflow-x 为 auto/scroll 的容器（如窄屏下的顶部导航）**本就该横向滚动**，
  //    那是设计取舍（"导航必须单行"），不是破相。
  //    我第一版不区分这两者 ⇒ 把 .nav-left 的设计行为报成了缺陷（假信号）。
  const pageOverflows = document.body.scrollWidth > window.innerWidth + 1;
  out.info.bodyScrollWidth = document.body.scrollWidth;
  out.info.innerWidth = window.innerWidth;
  if (pageOverflows) {
    out.issues.push('整页横向溢出：body.scrollWidth=' + document.body.scrollWidth + ' > 视口 ' + window.innerWidth);
  }
  if (scroller) {
    out.info.scrollWidth = scroller.scrollWidth;
    out.info.clientWidth = scroller.clientWidth;

    // 能滚到底：滚到底后最后一个可见块的 bottom 必须落在视口内
    // ⛔ 先关掉 scroll-behavior: smooth —— 平滑滚动下"设置 scrollTop 后立刻读"会拿到旧值 0，
    //    于是产品没问题却报"滚不动"（我第一版就踩了，实测 .chat-messages 正是 smooth：
    //    立刻读 0、等 600ms 才 40）。量具错会伪装成被测对象的缺陷。
    //    ⚠️ 本段在**模板字符串**里，注释中不能出现反引号（会截断模板）。
    const vh = window.innerHeight;
    const prevBehavior = scroller.style.scrollBehavior;
    scroller.style.scrollBehavior = 'auto';
    scroller.scrollTop = 999999;
    const scrolled = scroller.scrollTop;
    out.info.maxScroll = scroller.scrollHeight - scroller.clientHeight;
    out.info.reachedScroll = scrolled;
    if (scroller.scrollHeight > scroller.clientHeight + 2 && scrolled === 0) {
      out.issues.push('内容超出容器却滚不动（scrollTop 恒为 0）—— 溢出跑到了顶部、用户无法自救');
    }
    const last = [...document.querySelectorAll('.home-foot, .entry-card, .empty-letter, footer, .letter-card')]
      .map(el => el.getBoundingClientRect()).filter(r => r.height > 0)
      .sort((a, b) => b.bottom - a.bottom)[0];
    if (last && last.bottom > vh + 2 && scrolled < (scroller.scrollHeight - scroller.clientHeight) - 2) {
      out.issues.push('滚到底后仍有内容在视口下方：bottom=' + Math.round(last.bottom) + ' > vh=' + vh);
    }
    scroller.scrollTop = 0;   // 复位，后续检查不受影响
    scroller.style.scrollBehavior = prevBehavior
  }

  // 对比度：正文颜色 vs 其最近的不透明背景（只查"明显看不清"的极端情况）
  const textEl = document.querySelector('p, .message-content, .entry-desc, .home-sub');
  if (textEl) {
    // ⛔ 必须用 canvas 归一化：本设计系统全用 oklch(...)，
    //    而正则抠数字只认 rgb/rgba —— 那样对比度检查**永远算不出来（n/a）= 死检查**。
    //    恒真的判据与没有判据一样，只是更骗人。
    const cv = document.createElement('canvas'); cv.width = cv.height = 1;
    const cx = cv.getContext('2d');
    const toRgb = cssColor => {
      cx.clearRect(0, 0, 1, 1);
      cx.fillStyle = '#000';
      cx.fillStyle = cssColor;                // 浏览器负责解析 oklch/lab/...
      cx.fillRect(0, 0, 1, 1);
      const d = cx.getImageData(0, 0, 1, 1).data;
      return { r: d[0], g: d[1], b: d[2], a: d[3] / 255 };
    };
    let el = textEl, bgRgb = null, bgRaw = null;
    while (el && !bgRgb) {
      const c = getComputedStyle(el).backgroundColor;
      const parsed = toRgb(c);
      if (parsed.a > 0.5) { bgRgb = parsed; bgRaw = c; }
      el = el.parentElement;
    }
    if (bgRgb) {
      const lum = c => { const { r, g, b } = c; const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4) }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b) };
      const fgRaw = getComputedStyle(textEl).color;
      const L1 = lum(toRgb(fgRaw)), L2 = lum(bgRgb);
      const ratio = (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
      out.info.contrast = ratio.toFixed(2);
      out.info.fg = fgRaw; out.info.bg = bgRaw;
      if (ratio < 3) out.issues.push('正文对比度过低：' + ratio.toFixed(2) + '（fg=' + fgRaw + ' bg=' + bgRaw + '）');
    } else {
      out.issues.push('对比度判据失效：拿不到背景色（这是装置坏了，不是页面没问题）');
    }
  }
  return out;
})()`

async function main() {
  const browser = await launchBrowser()
  const ctx = await browser.newContext()
  const page = await ctx.newPage()
  const consoleErrors = []
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()) })

  // 登录（给了凭据才做）：注册一个新号，避免污染既有账号
  let loggedIn = false
  if (args.user && args.pass) {
    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
    await page.fill('#login-username', args.user)
    await page.fill('#login-password', args.pass)
    await page.click('.switch-link a')                    // 切到注册
    await page.fill('#login-username', args.user)
    await page.fill('#login-password', args.pass)
    await page.click('.submit-btn')
    await page.waitForTimeout(2500)
    loggedIn = page.url().replace(/\/$/, '').endsWith('/api')
    if (!loggedIn) {
      // ⛔ 登录失败**必须喊出来**：否则只测 /login 就报"全过"，那是假绿
      const err = await page.locator('.error-msg').first().textContent().catch(() => null)
      console.log(`⛔ 登录/注册未成功（停在 ${page.url()}${err ? '，页面报错：' + err : ''}）`)
      console.log('   → 本次只覆盖 /login；这不是"通过"，是**覆盖不足**')
    }
  }

  const routes = loggedIn
    ? [['/', '/'], ['/love-chat', '/love-chat'], ['/history', '/history'], ['/memory', '/memory'], ['/sandbox', '/sandbox'], ['/profile', '/profile'], ['/nope-404', '/nope-404']]
    : [['/login', '/login']]

  for (const vp of VIEWPORTS) {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    for (const [label, path] of routes) {
      await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded' })
      await page.waitForTimeout(900)
      const r = await page.evaluate(PROBE)
      record(vp.name, label, r.issues.length === 0,
        r.issues.length ? r.issues.join(' | ') : `ok（对比度 ${r.info.contrast ?? 'n/a'}，无横向溢出）`)
    }
  }

  if (consoleErrors.length) {
    console.log(`\n⚠️ 控制台有 ${consoleErrors.length} 条 error（不计入失败，但要看见）：`)
    consoleErrors.slice(0, 5).forEach(e => console.log('   ' + e.slice(0, 160)))
  }

  const failed = results.filter(r => !r.ok)
  console.log(`\n布局冒烟：${results.length - failed.length}/${results.length} 通过`)
  if (!loggedIn) {
    console.log('⛔ 未登录成功 ⇒ **未覆盖主页面**（/、/love-chat、/history、/memory、/sandbox、/profile）。')
    console.log('   这不是"通过"，是覆盖不足：补 --user 与 --pass（会自动注册新账号）。')
    return 1
  }
  await browser.close()
  return failed.length ? 1 : 0
}

process.exit(await main().catch(e => { console.error('布局冒烟自身失败：', e.message); return 1 }))