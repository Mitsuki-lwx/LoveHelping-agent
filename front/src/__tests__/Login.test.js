import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import Login from '../views/Login.vue'
import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [{ path: '/login', name: 'Login', component: Login }]
})

describe('Login.vue', () => {
  it('renders login form', async () => {
    const wrapper = mount(Login, {
      global: { plugins: [router] }
    })
    // ⛔ 2026-10-01：原断言 `toContain('LoveHelping')` 是**旧英文品牌**，页面已改为中文名 ⇒ 永久红。
    //    改为判**结构 + 当前产品名**：品牌元素存在即可，不再钉旧字面量。
    const title = wrapper.find('.login-title')
    expect(title.exists()).toBe(true)
    expect(title.text()).toContain('恋爱解忧所')
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.findAll('input').length).toBe(2)
  })

  it('toggles between login and register', async () => {
    const wrapper = mount(Login, {
      global: { plugins: [router] }
    })
    expect(wrapper.text()).toContain('登录')
    const link = wrapper.find('a')
    expect(link.text()).toContain('去注册')
    await link.trigger('click')
    expect(wrapper.text()).toContain('注册')
    expect(wrapper.find('a').text()).toContain('去登录')
  })

  it('shows error on empty submit', async () => {
    const wrapper = mount(Login, {
      global: { plugins: [router] }
    })
    await wrapper.find('form').trigger('submit')
    expect(wrapper.text()).toContain('用户名和密码不能为空')
  })
})
