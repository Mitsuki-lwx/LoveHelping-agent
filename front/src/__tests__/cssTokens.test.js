import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * CSS token 完整性守卫。
 *
 * ⛔ 为什么必须永久守（2026-10-07 实测踩到两次）：
 *    `var(--不存在的名字)` **不会报错**，浏览器只是让那条声明**静默失效**。
 *    实际发生过的两种：
 *      1. 我把 `--wine*` 重命名成 `--accent*` 后，从 git 取回的旧样式里还写着 `var(--wine)` ⇒
 *         保存按钮的渐变整条失效 ⇒ **按钮没有底色、白字浮白纸（对比度 1.04）**，
 *         而编译器、单测、类型检查**全都不会说话**。
 *      2. 仓库里还有一处 `var(--teal-deep, #0f6e56)` —— 有 fallback 所以"看着没事"，
 *         但那是**冷色**，早就与单一暖调调色板（ADR-80/81）冲突了。
 *    这类缺陷的共同点：**没有报错、只有"少了一点东西"**。所以用一条静态检查钉住。
 *
 * 判据：源码里出现的每个 `var(--x)`，`style.css` 里必须定义过 `--x`。
 */
// ⛔ 用 fileURLToPath（跨平台正确）；手写 pathname + 正则会把盘符路径拼坏（实测拼成 D:\src）
const SRC = join(dirname(fileURLToPath(import.meta.url)), '..')

function collect(dir, out = []) {
  for (const name of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, name.name)
    if (name.isDirectory()) collect(p, out)
    else if (/\.(vue|css)$/.test(name.name)) out.push(p)
  }
  return out
}

const css = readFileSync(join(SRC, 'style.css'), 'utf8')
const defined = new Set([...css.matchAll(/^\s*(--[a-z0-9-]+)\s*:/gm)].map((m) => m[1]))

const files = collect(SRC).filter((f) => !f.endsWith('style.css') && !f.includes('__tests__'))

describe('CSS token 完整性', () => {
  it('每个 var(--x) 都必须在 style.css 里定义过（未定义的会**静默失效**）', () => {
    const bad = {}
    for (const f of files) {
      const src = readFileSync(f, 'utf8')
      for (const m of src.matchAll(/var\((--[a-z0-9-]+)/g)) {
        if (!defined.has(m[1])) {
          const rel = f.slice(SRC.length).replace(/\\/g, '/')
          ;(bad[rel] ||= new Set()).add(m[1])
        }
      }
    }
    const pretty = Object.fromEntries(Object.entries(bad).map(([k, v]) => [k, [...v]]))
    expect(pretty, '以下 token 没有定义 —— 对应样式会静默失效').toEqual({})
  })

  it('解析出来的 token 数量合理（判据自身别退化成空集）', () => {
    // ⛔ 若 style.css 的结构变了导致正则匹配不到，上一条会"全过"——那是**死判据**。
    expect(defined.size).toBeGreaterThan(30)
    expect(files.length).toBeGreaterThan(10)
  })
})
