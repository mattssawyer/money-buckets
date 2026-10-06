import { afterEach, describe, expect, it, vi } from 'vitest'
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

  it('opens above the icon when there isn’t room below it', async () => {
    const wrapper = mountTip()
    wrapper.get('button').element.getBoundingClientRect = () =>
      ({ left: 100, top: 740, bottom: 760 }) as DOMRect
    Object.defineProperty(bubble(), 'offsetHeight', { value: 60 })

    await wrapper.get('.info-tip').trigger('mouseenter')
    await wrapper.vm.$nextTick()

    // jsdom's window is 768 tall, so 60 below 760 won't fit: 740 - 6 - 60.
    expect(bubble().style.top).toBe('674px')
  })

  it('stays open on pointer exit whenever the button has focus', async () => {
    const wrapper = mountTip()
    const button = wrapper.get('button')
    vi.spyOn(button.element, 'matches').mockReturnValue(false)

    button.element.focus()
    await wrapper.vm.$nextTick()
    await wrapper.get('.info-tip').trigger('mouseleave')
    expect(bubble().classList).toContain('info-tip-open')

    button.element.blur()
    await wrapper.vm.$nextTick()
    expect(bubble().classList).not.toContain('info-tip-open')
  })
})
