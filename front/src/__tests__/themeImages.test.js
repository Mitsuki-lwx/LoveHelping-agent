import { describe, it, expect } from 'vitest'
import { themeImages, missingSlots, THEME_SLOTS, FALLBACK_THEME } from '../utils/theme-images.js'
import { THEMES } from '../utils/theme.js'

/**
 * 主题配图。
 *
 * 为什么值得永久守：主题是**成对**的东西 —— 配色 + 配图。只配了色没配图，
 * 用户切到"深夜"却看到阳光灿烂的照片，主题的说服力当场就没了。
 * 而"加主题时忘了加图"是**不会报错**的（回落逻辑会让它静默用别的主题的图）。
 * 所以这里把"每套主题三张图都得在"钉成断言。
 */
describe('theme-images', () => {
  it('⛔ 每套主题的每个槽位都必须有图（加了主题忘配图 = 静默用了别人的图）', () => {
    expect(missingSlots()).toEqual([])
  })

  it('主题清单与配色主题一致（不要一边加主题另一边忘了）', () => {
    for (const t of THEMES) {
      const imgs = themeImages(t.id)
      for (const slot of THEME_SLOTS) {
        expect(imgs[slot], `${t.id}/${slot} 缺图`).toBeTruthy()
      }
    }
  })

  it('四套主题的图**互不相同**（共用同一张 = 只做了一半）', () => {
    for (const slot of THEME_SLOTS) {
      const urls = THEMES.map((t) => themeImages(t.id)[slot])
      expect(new Set(urls).size, `${slot} 在不同主题间重复`).toBe(THEMES.length)
    }
  })

  it('未知主题回落默认主题（缺图不开天窗）', () => {
    expect(themeImages('不存在的主题').hero).toBe(themeImages(FALLBACK_THEME).hero)
    expect(themeImages(undefined).today).toBeTruthy()
  })
})
