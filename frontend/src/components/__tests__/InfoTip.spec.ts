import { afterEach, describe, expect, it } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import InfoTip from '../InfoTip.vue'

enableAutoUnmount(afterEach)

function mountTip() {
  return mount(InfoTip, {
    props: { label: 'About your spending', text: 'What you spend in a month.' },
    attachTo: document.body,
  })
}

function bubble() {
  const found = document.querySelector<HTMLElement>('[role="tooltip"]')
  if (!found) throw new Error('Tooltip not found')
  return found
}

describe('info tip', () => {
  it('keeps its text behind a button that screen readers describe with it', () => {
    const wrapper = mountTip()

    const button = wrapper.get('button')
    expect(button.attributes('aria-label')).toBe('About your spending')
    expect(button.attributes('aria-describedby')).toBe(bubble().id)
    expect(bubble().textContent?.trim()).toBe('What you spend in a month.')
    expect(bubble().classList).not.toContain('info-tip-open')
  })

  it('opens on focus and closes on Escape', async () => {
    const wrapper = mountTip()

    await wrapper.get('button').trigger('focus')
    expect(bubble().classList).toContain('info-tip-open')

    await wrapper.get('button').trigger('keydown', { key: 'Escape' })
    expect(bubble().classList).not.toContain('info-tip-open')
  })

  it('stays inside the window near its right edge', async () => {
    const wrapper = mountTip()
    const button = wrapper.get('button').element
    button.getBoundingClientRect = () => ({ left: 990, bottom: 100 }) as DOMRect
    Object.defineProperty(bubble(), 'offsetWidth', { value: 256 })

    await wrapper.get('.info-tip').trigger('mouseenter')
    await wrapper.vm.$nextTick()

    // jsdom's window is 1024 wide: 1024 - 256 - 8.
    expect(bubble().style.left).toBe('760px')
    expect(bubble().style.top).toBe('106px')
  })
})
