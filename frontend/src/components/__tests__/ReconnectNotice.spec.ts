import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import ReconnectNotice from '../ReconnectNotice.vue'
import {
  createReconnectLinkToken,
  finishReconnect,
  getLinkedItems,
  type PlaidItem,
} from '../../api/PlaidService'
import { openPlaidLink } from '../../plaid/plaidLink'

vi.mock('../../api/PlaidService', () => ({
  createReconnectLinkToken: vi.fn<(itemId: string) => Promise<string>>(),
  finishReconnect: vi.fn<(itemId: string) => Promise<void>>(),
  getLinkedItems: vi.fn<() => Promise<PlaidItem[]>>(),
}))
vi.mock('../../plaid/plaidLink', () => ({
  openPlaidLink: vi.fn<(token: string) => Promise<string | null>>(),
  closePlaidLink: vi.fn<() => void>(),
}))

function item(overrides: Partial<PlaidItem>): PlaidItem {
  return {
    item_id: 'chase',
    institution_name: 'Chase',
    investments: false,
    investments_available: false,
    reconnect: null,
    ...overrides,
  }
}

async function mountNotice() {
  const wrapper = mount(ReconnectNotice, {
    global: { plugins: [[PrimeVue, { unstyled: true }]] },
  })
  await flushPromises()
  return wrapper
}

function reconnectButton(wrapper: VueWrapper) {
  const result = wrapper.findAll('button').find((element) => element.text() === 'Reconnect')
  if (!result) throw new Error('Reconnect button not found')
  return result
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(createReconnectLinkToken).mockResolvedValue('update-token')
})

describe('reconnect notice', () => {
  it('shows nothing while every bank is connected', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([item({})])

    const wrapper = await mountNotice()

    expect(wrapper.text()).toBe('')
  })

  it('says which banks need a new login and which will disconnect soon', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([
      item({ reconnect: 'LOGIN_REQUIRED' }),
      item({ item_id: 'amex', institution_name: 'American Express', reconnect: 'EXPIRING' }),
    ])

    const wrapper = await mountNotice()

    expect(wrapper.text()).toContain('Chase needs you to sign in again.')
    expect(wrapper.text()).toContain('American Express will disconnect soon.')
  })

  it('reconnects through Link update mode and tells the page', async () => {
    vi.mocked(getLinkedItems)
      .mockResolvedValueOnce([item({ reconnect: 'LOGIN_REQUIRED' })])
      .mockResolvedValueOnce([item({})])
    vi.mocked(openPlaidLink).mockResolvedValue('public-token')
    const wrapper = await mountNotice()

    await reconnectButton(wrapper).trigger('click')
    await flushPromises()

    expect(openPlaidLink).toHaveBeenCalledWith('update-token')
    expect(finishReconnect).toHaveBeenCalledWith('chase')
    expect(wrapper.emitted('reconnected')).toHaveLength(1)
    expect(wrapper.text()).toBe('')
  })

  it('leaves the notice up when the user closes Link', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([item({ reconnect: 'LOGIN_REQUIRED' })])
    vi.mocked(openPlaidLink).mockResolvedValue(null)
    const wrapper = await mountNotice()

    await reconnectButton(wrapper).trigger('click')
    await flushPromises()

    expect(finishReconnect).not.toHaveBeenCalled()
    expect(wrapper.emitted('reconnected')).toBeUndefined()
    expect(wrapper.text()).toContain('Chase needs you to sign in again.')
    expect(wrapper.text()).not.toContain('We couldn’t reconnect')
  })

  it('says so when reconnecting fails', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([item({ reconnect: 'LOGIN_REQUIRED' })])
    vi.mocked(openPlaidLink).mockRejectedValue(new Error('Plaid Link failed'))
    const wrapper = await mountNotice()

    await reconnectButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t reconnect Chase. Try again.')
    expect(wrapper.emitted('reconnected')).toBeUndefined()
  })
})
