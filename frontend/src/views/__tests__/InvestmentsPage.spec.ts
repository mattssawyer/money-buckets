import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import InvestmentsPage from '../InvestmentsPage.vue'
import {
  addInvestments,
  createLinkToken,
  exchangePublicToken,
  getAccounts,
  getLinkedItems,
  type PlaidAccount,
} from '../../api/PlaidService'
import { getBalanceHistory, type BalanceHistory } from '../../api/InvestmentsService'

vi.mock('@clerk/vue', () => ({
  UserButton: { template: '<div />' },
}))

vi.mock('../../api/PlaidService', () => ({
  addInvestments: vi.fn(),
  createLinkToken: vi.fn(),
  exchangePublicToken: vi.fn(),
  getAccounts: vi.fn(),
  getLinkedItems: vi.fn(),
  removeItem: vi.fn(),
}))

vi.mock('../../api/InvestmentsService', () => ({
  getBalanceHistory: vi.fn(),
}))

enableAutoUnmount(afterEach)

const ira: PlaidAccount = {
  account_id: 'ira',
  balances: {
    available: null,
    current: 41000,
    iso_currency_code: 'USD',
    limit: null,
  },
  mask: '4321',
  name: 'Roth IRA',
  official_name: null,
  subtype: 'roth',
  type: 'investment',
  trackable: false,
  tracks_spending: false,
  counts_in_net_worth: true,
  share_percent: 100,
}

const history: BalanceHistory = {
  net_worth: [
    { date: '2026-09-22', value: 40000 },
    { date: '2026-09-23', value: 44000 },
    { date: '2026-09-24', value: 45000 },
  ],
  accounts: [
    {
      account_id: 'ira',
      points: [
        { date: '2026-09-23', value: 40000 },
        { date: '2026-09-24', value: 41000 },
      ],
    },
  ],
  accounts_added: [{ date: '2026-09-23', account_id: 'ira', name: 'Roth IRA' }],
  accounts_dropped: [],
  left_out_of_net_worth: [],
}

let linkOptions: Parameters<Window['Plaid']['create']>[0]

function mountPage() {
  return mount(InvestmentsPage, {
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: {
        AppSidebar: true,
        // Chart.js needs a real canvas, so assert on the points each chart is handed instead.
        BalanceChart: {
          name: 'BalanceChart',
          props: ['points', 'label', 'added', 'dropped'],
          template: '<div class="chart-stub" :data-label="label">{{ points.length }} days</div>',
        },
      },
    },
  })
}

function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAll('button').find((element) => element.text() === label)
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 8, 24, 10))
  vi.mocked(getLinkedItems).mockResolvedValue([
    {
      item_id: 'fidelity',
      institution_name: 'Fidelity',
      investments: true,
      investments_available: true,
    },
  ])
  vi.mocked(getAccounts).mockResolvedValue([ira])
  vi.mocked(getBalanceHistory).mockResolvedValue(history)
  vi.mocked(createLinkToken).mockResolvedValue('link-token')
  vi.mocked(exchangePublicToken).mockResolvedValue({ item_id: 'new-item', same_institution: [] })
  vi.stubGlobal('Plaid', {
    create: vi.fn((options: typeof linkOptions) => {
      linkOptions = options
      return { open: vi.fn(), destroy: vi.fn() }
    }),
  })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('investments page', () => {
  it('shows net worth, its change over the range, and a chart for each investment account', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getBalanceHistory).toHaveBeenCalledWith('2026-06-24')
    expect(wrapper.get('.hero-figure').text()).toBe('$45,000.00')
    expect(wrapper.get('.net-worth').text()).toContain('+$5,000.00 (+12.5%)')
    expect(wrapper.get('.net-worth').text()).toContain('Sep 23, 2026 · Roth IRA added')

    const card = wrapper.get('[aria-label="Roth IRA ••4321"]')
    expect(card.text()).toContain('Roth IRA')
    expect(card.text()).toContain('$41,000.00')
    expect(card.get('.chart-stub').text()).toBe('2 days')
  })

  it('refetches every chart when you pick another range', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await button(wrapper, 'All').trigger('click')
    await flushPromises()
    expect(getBalanceHistory).toHaveBeenLastCalledWith(undefined)

    await button(wrapper, 'YTD').trigger('click')
    await flushPromises()
    expect(getBalanceHistory).toHaveBeenLastCalledWith('2026-01-01')
    expect(wrapper.get('[aria-checked="true"]').text()).toBe('YTD')
  })

  it('ignores a slower range that finishes after a newer one', async () => {
    let failOlder: (error: Error) => void = () => {}
    const wrapper = mountPage()
    await flushPromises()
    vi.mocked(getBalanceHistory)
      .mockImplementationOnce(() => new Promise((_, reject) => (failOlder = reject)))
      .mockResolvedValueOnce(history)

    await button(wrapper, '1M').trigger('click')
    await button(wrapper, 'YTD').trigger('click')
    await flushPromises()
    failOlder(new Error('Server unavailable'))
    await flushPromises()

    expect(wrapper.text()).not.toContain('We couldn’t load that range.')
    expect(wrapper.get('.net-worth').classes()).not.toContain('refreshing')
  })

  it('says history starts today when there is only one day', async () => {
    vi.mocked(getBalanceHistory).mockResolvedValue({
      ...history,
      net_worth: [{ date: '2026-09-24', value: 45000 }],
      accounts: [{ account_id: 'ira', points: [{ date: '2026-09-24', value: 41000 }] }],
      accounts_added: [],
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('.hero-figure').text()).toBe('$45,000.00')
    expect(wrapper.find('.chart-stub').exists()).toBe(false)
    expect(wrapper.get('.net-worth').text()).toContain('History starts today.')
  })

  it('notes accounts net worth leaves out', async () => {
    vi.mocked(getBalanceHistory).mockResolvedValue({ ...history, left_out_of_net_worth: ['euro'] })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Net worth leaves out 1 account that isn’t in US dollars.')
  })

  it('asks for an investments link before anything is connected', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([])
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Track your investments')
    await button(wrapper, 'Link investment account').trigger('click')
    await flushPromises()

    expect(createLinkToken).toHaveBeenCalledWith({ investments: true })
    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(exchangePublicToken).toHaveBeenCalledWith('public-token')
    expect(getLinkedItems).toHaveBeenCalledTimes(2)
  })

  it('warns when the linked institution was already connected', async () => {
    vi.mocked(exchangePublicToken).mockResolvedValue({
      item_id: 'new-item',
      same_institution: [
        {
          item_id: 'fidelity',
          institution_name: 'Fidelity',
          investments: true,
          investments_available: true,
        },
      ],
    })
    const wrapper = mountPage()
    await flushPromises()
    await button(wrapper, 'Link investment account').trigger('click')
    await flushPromises()
    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(wrapper.text()).toContain('You already had Fidelity connected')
  })

  it('adds investments to a connection that already exists', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([
      {
        item_id: 'chase',
        institution_name: 'Chase',
        investments: false,
        investments_available: true,
      },
    ])
    vi.mocked(addInvestments).mockResolvedValue({ outcome: 'added', link_token: null })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('.connections').text()).toContain('Chase')
    await button(wrapper, 'Add investments').trigger('click')
    await flushPromises()

    expect(addInvestments).toHaveBeenCalledWith('chase')
    expect(window.Plaid.create).not.toHaveBeenCalled()
    expect(getLinkedItems).toHaveBeenCalledTimes(2)
  })

  it('asks for consent in Link when Plaid needs it, then adds investments', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([
      {
        item_id: 'chase',
        institution_name: 'Chase',
        investments: false,
        investments_available: true,
      },
    ])
    vi.mocked(addInvestments)
      .mockResolvedValueOnce({ outcome: 'needs_consent', link_token: 'update-token' })
      .mockResolvedValueOnce({ outcome: 'added', link_token: null })
    const wrapper = mountPage()
    await flushPromises()

    await button(wrapper, 'Add investments').trigger('click')
    await flushPromises()
    expect(linkOptions.token).toBe('update-token')

    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(addInvestments).toHaveBeenCalledTimes(2)
    expect(exchangePublicToken).not.toHaveBeenCalled()
    expect(getLinkedItems).toHaveBeenCalledTimes(2)
  })

  it('drops a bank that doesn’t offer investments and says why', async () => {
    vi.mocked(getLinkedItems)
      .mockResolvedValueOnce([
        {
          item_id: 'regions',
          institution_name: null,
          investments: false,
          investments_available: null,
        },
      ])
      .mockResolvedValueOnce([
        {
          item_id: 'regions',
          institution_name: 'Regions Bank',
          investments: false,
          investments_available: false,
        },
      ])
    vi.mocked(addInvestments).mockResolvedValue({ outcome: 'not_offered', link_token: null })
    const wrapper = mountPage()
    await flushPromises()

    await button(wrapper, 'Add investments').trigger('click')
    await flushPromises()

    expect(window.Plaid.create).not.toHaveBeenCalled()
    expect(wrapper.find('.connections').exists()).toBe(false)
    expect(wrapper.text()).toContain(
      'That institution doesn’t offer investment accounts through Plaid.',
    )
  })

  it('hides connections whose institution doesn’t offer investments', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([
      {
        item_id: 'regions',
        institution_name: 'Regions Bank',
        investments: false,
        investments_available: false,
      },
    ])
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.find('.connections').exists()).toBe(false)
  })

  it('says so when the institution still shares no investments', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([
      {
        item_id: 'chase',
        institution_name: 'Chase',
        investments: false,
        investments_available: true,
      },
    ])
    vi.mocked(addInvestments).mockResolvedValue({
      outcome: 'needs_consent',
      link_token: 'update-token',
    })
    const wrapper = mountPage()
    await flushPromises()

    await button(wrapper, 'Add investments').trigger('click')
    await flushPromises()
    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(wrapper.text()).toContain('Chase didn’t share any investment accounts.')
  })

  it('offers a retry when loading fails', async () => {
    vi.mocked(getBalanceHistory).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t load your investments.')
    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()

    expect(wrapper.get('.hero-figure').text()).toBe('$45,000.00')
  })
})
