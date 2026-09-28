import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import AccountsPage from '../AccountsPage.vue'
import { getAccounts, updateAccountTracking, type PlaidAccount } from '../../api/PlaidService'

vi.mock('@clerk/vue', () => ({
  UserButton: { template: '<div />' },
}))
vi.mock('../../api/PlaidService', () => ({
  getAccounts: vi.fn<() => Promise<PlaidAccount[]>>(),
  updateAccountTracking:
    vi.fn<(accountId: string, tracks: boolean, share: number) => Promise<PlaidAccount>>(),
}))
enableAutoUnmount(afterEach)

function account(accountId: string, overrides: Partial<PlaidAccount> = {}): PlaidAccount {
  return {
    account_id: accountId,
    balances: { available: null, current: 1000, iso_currency_code: 'USD', limit: null },
    mask: '1234',
    name: accountId,
    official_name: null,
    subtype: 'checking',
    type: 'depository',
    trackable: true,
    tracks_spending: true,
    share_percent: 100,
    ...overrides,
  }
}

const checking = account('Checking')
const joint = account('Joint', { mask: '9876', balances: { ...checking.balances, current: 3000 } })
const card = account('Card', {
  type: 'credit',
  subtype: 'credit card',
  balances: { ...checking.balances, current: 300.25 },
})
const ira = account('IRA', {
  type: 'investment',
  subtype: 'ira',
  trackable: false,
  tracks_spending: false,
})

function mountPage() {
  return mount(AccountsPage, {
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: {
        AppSidebar: true,
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
      },
    },
  })
}

function row(wrapper: VueWrapper, name: string) {
  const found = wrapper
    .findAll('.account-row')
    .find((element) => element.get('.account-name').text().startsWith(name))
  if (!found) throw new Error(`Account not found: ${name}`)
  return found
}

function toggle(accountRow: ReturnType<typeof row>, label: string) {
  const found = accountRow.findAll('label.switch').find((element) => element.text() === label)
  if (!found) throw new Error(`Switch not found: ${label}`)
  return found.get('input')
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getAccounts).mockResolvedValue([checking, joint, card, ira])
  vi.mocked(updateAccountTracking).mockImplementation(async (accountId, tracks, share) => ({
    ...[checking, joint, card, ira].find((each) => each.account_id === accountId)!,
    tracks_spending: tracks,
    share_percent: share,
  }))
})

describe('accounts page', () => {
  it('groups accounts by kind with what each holds or owes', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.findAll('h2').map((heading) => heading.text())).toEqual([
      'Bank accounts',
      'Credit cards',
      'Investments',
    ])
    expect(row(wrapper, 'Card').get('.account-balance').text()).toBe('$300.25 owed')
    expect(row(wrapper, 'Joint').get('.account-balance').text()).toBe('$3,000.00')
  })

  it('offers tracking only for accounts spending can come from', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(row(wrapper, 'Card').find('.account-controls').exists()).toBe(true)
    expect(row(wrapper, 'IRA').find('.account-controls').exists()).toBe(false)
  })

  it('stops tracking an account', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await toggle(row(wrapper, 'Checking'), 'Track spending').setValue(false)
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenCalledWith('Checking', false, 100)
    expect(row(wrapper, 'Checking').findAll('label.switch')).toHaveLength(1)
  })

  it('marks an account as shared at an even split, then saves a new share', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await toggle(row(wrapper, 'Joint'), 'Shared').setValue(true)
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenLastCalledWith('Joint', true, 50)
    const share = row(wrapper, 'Joint').get('.share-field input')
    expect(share.element).toHaveProperty('value', '50')

    await share.setValue('60')
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenLastCalledWith('Joint', true, 60)
  })

  it('ignores a share outside 1 to 99 percent', async () => {
    vi.mocked(getAccounts).mockResolvedValue([{ ...joint, share_percent: 50 }])
    const wrapper = mountPage()
    await flushPromises()

    const share = row(wrapper, 'Joint').get('.share-field input')
    await share.setValue('0')
    await flushPromises()

    expect(updateAccountTracking).not.toHaveBeenCalled()
    expect(share.element).toHaveProperty('value', '50')
  })

  it('puts a change back and says so when it can’t be saved', async () => {
    vi.mocked(updateAccountTracking).mockRejectedValue(new Error('Server unavailable'))
    const wrapper = mountPage()
    await flushPromises()

    await toggle(row(wrapper, 'Joint'), 'Shared').setValue(true)
    await flushPromises()

    expect(toggle(row(wrapper, 'Joint'), 'Shared').element).toHaveProperty('checked', false)
    expect(row(wrapper, 'Joint').find('.share-field').exists()).toBe(false)
    expect(row(wrapper, 'Joint').text()).toContain('We couldn’t save that change. Try again.')
  })

  it('points to Home when nothing is connected', async () => {
    vi.mocked(getAccounts).mockResolvedValue([])
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('No accounts yet')
    expect(wrapper.get('.prompt-link').attributes('href')).toBe('/')
  })

  it('offers to try again when accounts fail to load', async () => {
    vi.mocked(getAccounts).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t load your accounts.')
    const retry = wrapper.findAll('button').find((element) => element.text() === 'Try again')!
    await retry.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.account-row')).toHaveLength(4)
  })
})
