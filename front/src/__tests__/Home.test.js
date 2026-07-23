import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import Home from '../views/Home.vue'
import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'Home', component: Home },
    { path: '/love-chat', name: 'LoveChat', component: { template: '<div>love</div>' } },
    { path: '/manus-chat', name: 'ManusChat', component: { template: '<div>manus</div>' } }
  ]
})

describe('Home.vue', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('renders title and cards', async () => {
    const wrapper = mount(Home, {
      global: { plugins: [router] }
    })
    expect(wrapper.text()).toContain('LoveHelping')
    expect(wrapper.text()).toContain('恋爱专家')
    expect(wrapper.text()).toContain('恋爱全能帮')
  })

  it('has two navigation cards', () => {
    const wrapper = mount(Home, {
      global: { plugins: [router] }
    })
    const cards = wrapper.findAll('.card')
    expect(cards).toHaveLength(2)
  })

  it('card click navigates to love-chat', async () => {
    const push = vi.spyOn(router, 'push')
    const wrapper = mount(Home, {
      global: { plugins: [router] }
    })
    const cards = wrapper.findAll('.card')
    await cards[0].trigger('click')
    expect(push).toHaveBeenCalledWith('/love-chat')
  })

  it('second card navigates to manus-chat', async () => {
    const push = vi.spyOn(router, 'push')
    const wrapper = mount(Home, {
      global: { plugins: [router] }
    })
    const cards = wrapper.findAll('.card')
    await cards[1].trigger('click')
    expect(push).toHaveBeenCalledWith('/manus-chat')
  })
})
