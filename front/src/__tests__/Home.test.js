import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import Home from '../views/Home.vue'
import { createRouter, createWebHistory } from 'vue-router'

/**
 * ⛔ 2026-10-01 重写（原 4 条断言的是**旧 UI**：英文品牌 'LoveHelping'、`.card` 两张、love-chat/manus-chat）。
 * 首页已改版为 4 张「信」主题入口卡、品牌改为中文名的拼接形式 ⇒ 旧断言**永久红**。
 * ⚠️ **5 个永久红测试比没有测试更糟**：它们把人训练成忽略整个套件（"狼来了"）。
 *
 * 重写原则：断言**当前真实契约**，且只留下有意义的部分 ——
 *   · 品牌存在（判**结构 + 中文产品名**，不再钉英文大小写那种排版细节）；
 *   · 入口卡的**导航映射**（点哪张去哪）—— 这是真正有用的契约，改了路由就该红。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'Home', component: Home },
    { path: '/love-chat', name: 'LoveChat', component: { template: '<div>love</div>' } },
    { path: '/sandbox', name: 'Sandbox', component: { template: '<div>sandbox</div>' } },
    { path: '/memory', name: 'Memory', component: { template: '<div>memory</div>' } },
    { path: '/history', name: 'History', component: { template: '<div>history</div>' } }
  ]
})

/** 当前首页的入口卡 → 目标路由（改路由就该同步改这里，这是**契约**不是排版）。 */
const ENTRY_ROUTES = ['/love-chat', '/sandbox', '/memory', '/history']

describe('Home.vue', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('渲染品牌与四个入口卡', () => {
    const wrapper = mount(Home, { global: { plugins: [router] } })
    const brand = wrapper.find('.brand-mark')
    expect(brand.exists()).toBe(true)
    expect(brand.text()).toContain('恋爱解忧所')
    expect(wrapper.findAll('.entry-card')).toHaveLength(ENTRY_ROUTES.length)
  })

  it('每张入口卡点击后导航到对应路由（导航映射契约）', async () => {
    const wrapper = mount(Home, { global: { plugins: [router] } })
    const cards = wrapper.findAll('.entry-card')
    expect(cards).toHaveLength(ENTRY_ROUTES.length)
    for (let i = 0; i < cards.length; i++) {
      const push = vi.spyOn(router, 'push')
      await cards[i].trigger('click')
      expect(push, `第 ${i + 1} 张卡`).toHaveBeenCalledWith(ENTRY_ROUTES[i])
      push.mockRestore()
    }
  })
})
