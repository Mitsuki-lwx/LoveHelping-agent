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
    expect(wrapper.text()).toContain('LoveHelping')
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
