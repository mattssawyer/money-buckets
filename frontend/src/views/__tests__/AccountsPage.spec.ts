import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import AccountsPage from '../AccountsPage.vue'
import {
  getAccounts,
  updateAccountTracking,
  type AccountTracking,
  type PlaidAccount,
} from '../../api/PlaidService'

vi.mock('@clerk/vue', () => ({
  UserButton: { template: '<div />' },
}))
vi.mock('../../api/PlaidService', () => ({
  getAccounts: vi.fn<() => Promise<PlaidAccount[]>>(),
  updateAccountTracking:
    vi.fn<(accountId: string, tracking: AccountTracking) => Promise<PlaidAccount>>(),
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
    counts_in_net_worth: true,
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
    .findAll('tbody tr')
    .find((element) => element.get('.account-name').text().startsWith(name))
  if (!found) throw new Error(`Account not found: ${name}`)
  return found
}

function cell(accountRow: ReturnType<typeof row>, label: string) {
  return accountRow.get(`td[data-label="${label}"]`)
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getAccounts).mockResolvedValue([checking, joint, card, ira])
  vi.mocked(updateAccountTracking).mockImplementation(async (accountId, tracking) => ({
    ...[checking, joint, card, ira].find((each) => each.account_id === accountId)!,
    ...tracking,
  }))
})

describe('accounts page', () => {
  it('groups accounts by kind with what each holds or owes', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.findAll('caption').map((caption) => caption.text())).toEqual([
      'Bank accounts',
      'Credit cards',
      'Investments',
    ])
    expect(cell(row(wrapper, 'Card'), 'Balance').text()).toBe('$300.25 owed')
    expect(cell(row(wrapper, 'Joint'), 'Balance').text()).toBe('$3,000.00')
    expect(row(wrapper, 'IRA').get('.account-kind').text()).toBe('IRA')
  })

  it('offers spending tracking only where spending can come from, and net worth everywhere', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(cell(row(wrapper, 'Card'), 'Spending').find('input').exists()).toBe(true)
    expect(cell(row(wrapper, 'IRA'), 'Spending').find('input').exists()).toBe(false)
    expect(cell(row(wrapper, 'IRA'), 'Net worth').find('input').exists()).toBe(true)
  })

  it('stops tracking spending from an account', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await cell(row(wrapper, 'Checking'), 'Spending').get('input').setValue(false)
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenCalledWith('Checking', {
      tracks_spending: false,
      counts_in_net_worth: true,
      share_percent: 100,
    })
    expect(cell(row(wrapper, 'Checking'), 'Spending').get('input').element).toHaveProperty(
      'checked',
      false,
    )
  })

  it('leaves an account out of net worth', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await cell(row(wrapper, 'IRA'), 'Net worth').get('input').setValue(false)
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenCalledWith('IRA', {
      tracks_spending: false,
      counts_in_net_worth: false,
      share_percent: 100,
    })
  })

  it('shows a share only for shared accounts', async () => {
    vi.mocked(getAccounts).mockResolvedValue([checking, { ...joint, share_percent: 60 }])
    const wrapper = mountPage()
    await flushPromises()

    expect(cell(row(wrapper, 'Checking'), 'Shared').find('.share-field').exists()).toBe(false)
    const jointShared = cell(row(wrapper, 'Joint'), 'Shared')
    expect(jointShared.get('.switch').element).toHaveProperty('checked', true)
    expect(jointShared.get('.share-field input').element).toHaveProperty('value', '60')
  })

  it('marks an account as shared at an even split, then saves a new share', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await cell(row(wrapper, 'Joint'), 'Shared').get('.switch').setValue(true)
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenLastCalledWith('Joint', {
      tracks_spending: true,
      counts_in_net_worth: true,
      share_percent: 50,
    })
    const share = cell(row(wrapper, 'Joint'), 'Shared').get('.share-field input')
    expect(share.element).toHaveProperty('value', '50')

    await share.setValue('60')
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenLastCalledWith('Joint', {
      tracks_spending: true,
      counts_in_net_worth: true,
      share_percent: 60,
    })
  })

  it('counts the whole account again when it stops being shared', async () => {
    vi.mocked(getAccounts).mockResolvedValue([{ ...joint, share_percent: 50 }])
    const wrapper = mountPage()
    await flushPromises()

    await cell(row(wrapper, 'Joint'), 'Shared').get('.switch').setValue(false)
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenCalledWith('Joint', {
      tracks_spending: true,
      counts_in_net_worth: true,
      share_percent: 100,
    })
    expect(cell(row(wrapper, 'Joint'), 'Shared').find('.share-field').exists()).toBe(false)
  })

  it('ignores a share outside 1 to 99 percent', async () => {
    vi.mocked(getAccounts).mockResolvedValue([{ ...joint, share_percent: 50 }])
    const wrapper = mountPage()
    await flushPromises()

    const share = cell(row(wrapper, 'Joint'), 'Shared').get('.share-field input')
    await share.setValue('100')
    await flushPromises()

    expect(updateAccountTracking).not.toHaveBeenCalled()
    expect(share.element).toHaveProperty('value', '50')
  })

  it('puts a change back and says so when it can\u2019t be saved', async () => {
    vi.mocked(updateAccountTracking).mockRejectedValue(new Error('Server unavailable'))
    const wrapper = mountPage()
    await flushPromises()

    await cell(row(wrapper, 'Joint'), 'Net worth').get('input').setValue(false)
    await flushPromises()

    expect(cell(row(wrapper, 'Joint'), 'Net worth').get('input').element).toHaveProperty(
      'checked',
      true,
    )
    expect(wrapper.text()).toContain('We couldn’t save the change to Joint ••9876. Try again.')
  })

  it('saves one change at a time for an account and ends on the latest choice', async () => {
    const responses: Array<(saved: PlaidAccount) => void> = []
    vi.mocked(updateAccountTracking).mockImplementation(
      (_accountId, tracking) =>
        new Promise((resolve) => responses.push(() => resolve({ ...joint, ...tracking }))),
    )
    const wrapper = mountPage()
    await flushPromises()

    await cell(row(wrapper, 'Joint'), 'Net worth').get('input').setValue(false)
    await cell(row(wrapper, 'Joint'), 'Spending').get('input').setValue(false)
    await flushPromises()

    // The second change waits for the first save instead of racing it.
    expect(updateAccountTracking).toHaveBeenCalledTimes(1)
    expect(cell(row(wrapper, 'Joint'), 'Spending').get('input').element).toHaveProperty(
      'checked',
      false,
    )

    responses[0]!(joint)
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenCalledTimes(2)
    expect(updateAccountTracking).toHaveBeenLastCalledWith('Joint', {
      tracks_spending: false,
      counts_in_net_worth: false,
      share_percent: 100,
    })
    // The first response came back before the second change was sent, and didn't undo it.
    expect(cell(row(wrapper, 'Joint'), 'Spending').get('input').element).toHaveProperty(
      'checked',
      false,
    )

    responses[1]!(joint)
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenCalledTimes(2)
    expect(cell(row(wrapper, 'Joint'), 'Spending').get('input').element).toHaveProperty(
      'checked',
      false,
    )
    expect(cell(row(wrapper, 'Joint'), 'Net worth').get('input').element).toHaveProperty(
      'checked',
      false,
    )
  })

  it("keeps an account's error while another account saves", async () => {
    vi.mocked(updateAccountTracking).mockImplementation(async (accountId, tracking) => {
      if (accountId === 'Joint') throw new Error('Server unavailable')
      return { ...checking, ...tracking }
    })
    const wrapper = mountPage()
    await flushPromises()

    await cell(row(wrapper, 'Joint'), 'Net worth').get('input').setValue(false)
    await flushPromises()
    await cell(row(wrapper, 'Checking'), 'Net worth').get('input').setValue(false)
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t save the change to Joint ••9876. Try again.')
    expect(wrapper.text()).not.toContain('save the change to Checking')
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

    expect(wrapper.findAll('tbody tr')).toHaveLength(4)
  })
})
