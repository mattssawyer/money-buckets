import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed } from 'vue'
import PrimeVue from 'primevue/config'
import HomePage from '../HomePage.vue'
import {
  createLinkToken,
  exchangePublicToken,
  getAccounts,
  getLinkedItemIds,
  getSpendingByBucket,
  getTransactions,
  getTransactionPage,
  getRecurringTransactions,
  getRecurringCandidates,
  answerRecurringCandidate,
  correctPayee,
  undoRecurringAnswer,
  removeItem,
  syncRecurringTransactions,
  type PlaidAccount,
  type PlaidTransaction,
  type RecurringCandidate,
  type RecurringStream,
  type SpendingByBucket,
} from '../../api/PlaidService'
import { getSpendingPlan, type SpendingPlanResponse } from '../../api/SpendingPlanService'
import { periodName, periodRange } from '../../spending/period'

vi.mock('../../api/SpendingPlanService', () => ({
  getSpendingPlan: vi.fn<() => Promise<SpendingPlanResponse | null>>(),
}))
vi.mock('../../api/PlaidService', () => ({
  createLinkToken: vi.fn(),
  exchangePublicToken: vi.fn(),
  getAccounts: vi.fn(),
  getLinkedItemIds: vi.fn(),
  getSpendingByBucket: vi.fn(),
  getTransactions: vi.fn(),
  getTransactionPage: vi.fn(),
  getRecurringTransactions: vi.fn(),
  getRecurringCandidates: vi.fn<(accountId?: string) => Promise<RecurringCandidate[]>>(),
  answerRecurringCandidate:
    vi.fn<(candidate: RecurringCandidate, confirmed: boolean) => Promise<void>>(),
  undoRecurringAnswer: vi.fn<(candidate: RecurringCandidate) => Promise<void>>(),
  syncRecurringTransactions: vi.fn<() => Promise<void>>(),
  removeItem: vi.fn(),
  correctPayee: vi.fn(),
  undoPayeeCorrection: vi.fn(),
}))
const clerk = vi.hoisted(() => ({
  user: null as {
    firstName?: string | null
    primaryEmailAddress?: { emailAddress: string } | null
  } | null,
}))
vi.mock('@clerk/vue', () => ({
  UserButton: { template: '<div />' },
  useUser: () => ({ user: computed(() => clerk.user) }),
}))
enableAutoUnmount(afterEach)

const checking: PlaidAccount = {
  account_id: 'checking',
  balances: { current: 1250.5, available: 1200, iso_currency_code: 'USD', limit: null },
  mask: '1234',
  name: 'Checking',
  official_name: null,
  subtype: 'checking',
  type: 'depository',
  trackable: true,
  tracks_spending: true,
  counts_in_net_worth: true,
  share_percent: 100,
}

const savings: PlaidAccount = {
  account_id: 'savings',
  balances: { current: 8400, available: 8400, iso_currency_code: 'USD', limit: null },
  mask: '5678',
  name: 'Savings',
  official_name: null,
  subtype: 'savings',
  type: 'depository',
  trackable: true,
  tracks_spending: true,
  counts_in_net_worth: true,
  share_percent: 100,
}

const coffee: PlaidTransaction = {
  transaction_id: 'txn-1',
  account_id: 'checking',
  amount: 4.75,
  iso_currency_code: 'USD',
  date: '2026-09-17',
  name: 'COFFEE SHOP',
  merchant_name: 'Coffee Shop',
  logo_url: null,
  pending: false,
  category: 'FOOD_AND_DRINK',
  category_detailed: 'FOOD_AND_DRINK_COFFEE',
  bucket: 'GUILT_FREE',
  share_percent: 100,
  payee_key: 'coffee shop',
  payee_kind: 'BILL',
  bucket_corrected: false,
  category_corrected: false,
  recurring: null,
}

const spending: SpendingByBucket = {
  start: '2026-09-01',
  end: '2026-09-30',
  total: 1844.5,
  buckets: [
    {
      bucket: 'FIXED_COSTS',
      amount: 1532.5,
      categories: [
        {
          category: 'RENT_AND_UTILITIES',
          amount: 1450,
          transactions: [
            {
              ...coffee,
              transaction_id: 'rent',
              amount: 1450,
              merchant_name: 'Landlord',
              date: '2026-09-01',
            },
          ],
        },
        {
          category: 'FOOD_AND_DRINK',
          amount: 82.5,
          transactions: [
            {
              ...coffee,
              transaction_id: 'groceries-2',
              amount: 60,
              merchant_name: 'Whole Foods',
              date: '2026-09-14',
            },
            {
              ...coffee,
              transaction_id: 'groceries-1',
              amount: 22.5,
              merchant_name: "Trader Joe's",
              date: '2026-09-03',
            },
          ],
        },
      ],
    },
    {
      bucket: 'GUILT_FREE',
      amount: 12,
      categories: [
        { category: 'FOOD_AND_DRINK', amount: 12, transactions: [{ ...coffee, amount: 12 }] },
      ],
    },
    {
      bucket: 'SAVINGS',
      amount: 300,
      categories: [
        {
          category: 'TRANSFER_OUT',
          amount: 300,
          transactions: [
            { ...coffee, transaction_id: 'to-savings', amount: 300, merchant_name: 'Ally' },
          ],
        },
      ],
    },
  ],
}

const rent: RecurringStream = {
  stream_id: 'stream-rent',
  account_id: 'checking',
  merchant_name: 'Landlord',
  description: 'RENT',
  amount: 1450,
  iso_currency_code: 'USD',
  frequency: 'MONTHLY',
  next_date: '2026-10-01',
  last_date: '2026-09-01',
  is_inflow: false,
  category: 'RENT_AND_UTILITIES',
  category_detailed: 'RENT_AND_UTILITIES_RENT',
  share_percent: 100,
  plan_bucket: null,
  plan_line: null,
}

let linkOptions: Parameters<Window['Plaid']['create']>[0]

// Chart.js needs a real canvas, so assert on the data the chart is handed instead.
const ChartStub = {
  props: ['data'],
  template: `<ul class="chart-stub">
    <li v-for="(label, index) in data.labels" :key="label">
      {{ label }}: {{ data.datasets[0].data[index] }}
    </li>
  </ul>`,
}

function mountHome() {
  return mount(HomePage, {
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: {
        teleport: true,
        AppSidebar: true,
        Chart: ChartStub,
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
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
  localStorage.clear()
  clerk.user = { firstName: 'Ada' }
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  )
  vi.mocked(getLinkedItemIds).mockResolvedValue([])
  vi.mocked(getAccounts).mockResolvedValue([checking])
  vi.mocked(getTransactions).mockResolvedValue([coffee])
  vi.mocked(getSpendingByBucket).mockResolvedValue(spending)
  vi.mocked(getRecurringTransactions).mockResolvedValue([rent])
  vi.mocked(getRecurringCandidates).mockResolvedValue([])
  vi.mocked(getSpendingPlan).mockResolvedValue({} as SpendingPlanResponse)
  vi.mocked(createLinkToken).mockResolvedValue('link-token')
  vi.mocked(exchangePublicToken).mockResolvedValue({ item_id: 'saved-item', same_institution: [] })
  vi.stubGlobal('Plaid', {
    create: vi.fn((options: typeof linkOptions) => {
      linkOptions = options
      return { open: vi.fn(), destroy: vi.fn() }
    }),
  })
})

afterEach(() => vi.unstubAllGlobals())

describe('homepage balances', () => {
  it('waits for saved connections before showing onboarding', async () => {
    const wrapper = mountHome()
    expect(wrapper.text()).not.toContain('Start with an account.')
    expect(wrapper.find('[aria-label="Loading your accounts"]').exists()).toBe(true)

    await flushPromises()

    expect(wrapper.text()).toContain('Start with an account.')
  })

  it('asks for accounts and every card at once instead of one after another', async () => {
    vi.mocked(getLinkedItemIds).mockReturnValue(new Promise(() => {}))
    vi.mocked(getAccounts).mockReturnValue(new Promise(() => {}))
    mountHome()
    await flushPromises()

    expect(getAccounts).toHaveBeenCalledOnce()
    expect(getTransactions).toHaveBeenCalledOnce()
    expect(getSpendingByBucket).toHaveBeenCalledOnce()
    expect(getRecurringTransactions).toHaveBeenCalledOnce()
  })

  it('loads the remembered account alongside the account list', async () => {
    localStorage.setItem('abacus.selectedAccountId', 'savings')
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([checking, savings])
    mountHome()
    await flushPromises()

    expect(getTransactions).toHaveBeenCalledTimes(1)
    expect(getTransactions).toHaveBeenCalledWith(25, 'savings')
  })

  it('loads again for all accounts when the remembered one is no longer tracked', async () => {
    localStorage.setItem('abacus.selectedAccountId', 'closed-account')
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([checking, savings])
    const wrapper = mountHome()
    await flushPromises()

    expect(getTransactions).toHaveBeenLastCalledWith(25, undefined)
    expect(wrapper.get('.transaction-name').text()).toBe('Coffee Shop')
  })

  it('restores a saved connection and displays its current balance', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    expect(getAccounts).toHaveBeenCalledOnce()
    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(wrapper.get('.balance-card').text()).toContain('Balance')
    expect(wrapper.get('.balance-card').text()).toContain('$1,250.50')
    expect(wrapper.text()).not.toContain('Start with an account.')
  })

  it('offers to remove the older connection when an institution is linked again', async () => {
    vi.mocked(exchangePublicToken).mockResolvedValue({
      item_id: 'new-item',
      same_institution: [
        {
          item_id: 'saved-item',
          institution_name: 'Fidelity',
          investments: false,
          investments_available: true,
        },
      ],
    })
    const wrapper = mountHome()
    await flushPromises()
    await button(wrapper, 'Add an account').trigger('click')
    await flushPromises()
    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(wrapper.text()).toContain('You already had Fidelity connected')
    await button(wrapper, 'Remove the older connection').trigger('click')
    await flushPromises()

    expect(removeItem).toHaveBeenCalledWith('saved-item')
    expect(wrapper.text()).not.toContain('You already had Fidelity connected')
    expect(getLinkedItemIds).toHaveBeenCalledTimes(2)
  })

  it('offers all tracked accounts together and each one to pick from', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([
      checking,
      savings,
      {
        ...checking,
        account_id: 'ira',
        name: 'Roth IRA',
        type: 'investment',
        subtype: 'roth',
        trackable: false,
        tracks_spending: false,
      },
    ])
    const wrapper = mountHome()
    await flushPromises()

    const options = wrapper.findAll('.account-select option').map((option) => option.text())
    expect(options).toEqual(['All tracked accounts', 'Checking ••1234', 'Savings ••5678'])
  })

  it('says so when no account is tracked, and links to choosing them', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([
      {
        ...checking,
        account_id: 'ira',
        name: 'Roth IRA',
        type: 'investment',
        subtype: 'roth',
        trackable: false,
        tracks_spending: false,
      },
    ])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('You’re not tracking spending from any account yet.')
    expect(wrapper.get('.account-notice a').attributes('href')).toBe('/accounts')
    expect(wrapper.findAll('button').some((element) => element.text() === 'Try again')).toBe(false)
  })

  it("counts a shared account at the user's share and takes card balances away", async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([
      checking,
      { ...savings, account_id: 'joint', name: 'Joint', share_percent: 50 },
      {
        ...checking,
        account_id: 'card',
        name: 'Card',
        type: 'credit',
        subtype: 'credit card',
        balances: { ...checking.balances, current: 250.5 },
      },
    ])
    const wrapper = mountHome()
    await flushPromises()

    // 1,250.50 + half of 8,400 - 250.50
    expect(wrapper.get('.balance-amount').text()).toBe('$5,200.00')
  })

  it('replaces onboarding with the balance after Link succeeds', async () => {
    const wrapper = mountHome()
    await flushPromises()
    await button(wrapper, 'Add an account').trigger('click')
    await flushPromises()

    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(exchangePublicToken).toHaveBeenCalledWith('public-token')
    expect(wrapper.text()).not.toContain('Start with an account.')
    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
  })

  it('keeps a newly saved connection when balances fail and retries without relinking', async () => {
    const wrapper = mountHome()
    await flushPromises()
    vi.mocked(getAccounts).mockRejectedValueOnce(new Error('Plaid unavailable'))
    await button(wrapper, 'Add an account').trigger('click')
    await flushPromises()
    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(wrapper.text()).not.toContain('Start with an account.')
    expect(wrapper.text()).toContain('We couldn’t load your accounts.')
    expect(wrapper.text()).not.toContain('could not be saved')
    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(exchangePublicToken).toHaveBeenCalledOnce()
  })

  it('does not show onboarding if the saved connections request fails', async () => {
    vi.mocked(getLinkedItemIds).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t load your connected accounts.')
    expect(wrapper.text()).not.toContain('Start with an account.')
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()
    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
  })

  it('retries a failed exchange with the same public token instead of relinking', async () => {
    vi.mocked(exchangePublicToken).mockRejectedValueOnce(new Error('Plaid unavailable'))
    const wrapper = mountHome()
    await flushPromises()
    await button(wrapper, 'Add an account').trigger('click')
    await flushPromises()
    linkOptions.onSuccess('public-token', {})
    await flushPromises()

    expect(wrapper.text()).toContain('could not be saved')

    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()

    expect(exchangePublicToken).toHaveBeenLastCalledWith('public-token')
    expect(exchangePublicToken).toHaveBeenCalledTimes(2)
    expect(createLinkToken).toHaveBeenCalledOnce()
    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
  })

  it('shows an unavailable balance instead of treating a null balance as zero', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([
      { ...checking, balances: { ...checking.balances, current: null, available: null } },
    ])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('—')
    expect(wrapper.get('.balance-amount').attributes('aria-label')).toBe('Balance unavailable')
    expect(wrapper.text()).not.toContain('$0.00')
  })
})

describe('homepage greeting', () => {
  afterEach(() => vi.useRealTimers())

  it('greets the signed-in user for the current time of day', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 18, 9, 0))
    const morning = mountHome()
    await flushPromises()
    expect(morning.get('h1').text()).toBe('Good morning, Ada')

    vi.setSystemTime(new Date(2026, 8, 18, 20, 0))
    const evening = mountHome()
    await flushPromises()
    expect(evening.get('h1').text()).toBe('Good evening, Ada')
  })

  it('drops the name rather than guessing when Clerk has no first name', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 18, 9, 0))
    clerk.user = { firstName: null, primaryEmailAddress: { emailAddress: 'a.b@example.com' } }
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('Good morning')
  })

  it('greets without a name while Clerk is still loading', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 18, 14, 0))
    clerk.user = null
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('Good afternoon')
  })
})

describe('homepage recent transactions', () => {
  it('falls back when a transaction has no merchant or name', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getTransactions).mockResolvedValue([{ ...coffee, merchant_name: '', name: null }])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.transactions-card .transaction-name').text()).toBe('Transaction')
  })

  it('lists recent transactions with money leaving the account as negative', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    expect(getTransactions).toHaveBeenCalledWith(25, undefined)
    expect(wrapper.get('.transaction-name').text()).toBe('Coffee Shop')
    expect(wrapper.get('.transaction-amount').text()).toBe('-$4.75')
    expect(wrapper.get('.transaction-meta').text()).toContain('Sep 17')
  })

  it('shows a refund as a positive amount', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getTransactions).mockResolvedValue([{ ...coffee, amount: -4.75 }])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.transaction-amount').text()).toBe('+$4.75')
  })

  it('keeps balances visible when transactions fail to load', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getTransactions).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(wrapper.text()).toContain('We couldn’t load your recent transactions.')
  })

  it('opens every transaction for the selected account', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getTransactionPage).mockResolvedValue({ transactions: [coffee], total: 1 })
    const wrapper = mountHome()
    await flushPromises()

    expect(getTransactionPage).not.toHaveBeenCalled()
    await button(wrapper, 'View all').trigger('click')
    await flushPromises()

    expect(getTransactionPage).toHaveBeenCalledWith(0, 50, undefined)
  })

  it('offers no full view until there are transactions', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getTransactions).mockResolvedValue([])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.findAll('button').some((element) => element.text() === 'View all')).toBe(false)
  })
})

describe('homepage transaction editing', () => {
  it('opens the editor from a recent transaction and reloads the cards after a change', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    await wrapper.get('.transactions-card .transaction-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(
      'Applies to every charge from Coffee Shop, including future ones.',
    )

    vi.mocked(getTransactions).mockClear()
    await wrapper.get('[aria-label="Bucket"]').setValue('FIXED_COSTS')
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()

    expect(correctPayee).toHaveBeenCalledWith('coffee shop', 'FIXED_COSTS', null)
    expect(getTransactions).toHaveBeenCalledOnce()
  })
})

describe('homepage spending breakdown', () => {
  it('charts each bucket', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    const slices = wrapper.findAll('.chart-stub li').map((slice) => slice.text())
    expect(slices).toEqual(['Fixed costs: 1532.5', 'Guilt-free spending: 12', 'Savings: 300'])
    expect(wrapper.get('[aria-label="Spending period"]').element).toHaveProperty(
      'value',
      'THIS_MONTH',
    )
    const buckets = wrapper.findAll('.spending-legend > li > button').map((row) => row.text())
    expect(buckets).toEqual(['Fixed costs$1,532.50', 'Guilt-free spending$12.00', 'Savings$300.00'])
    expect(wrapper.get('.spending-total-amount').text()).toBe('$1,845')
  })

  it('breaks each bucket down by Plaid category, open at first', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    const fixedCosts = wrapper.get('.spending-legend > li')
    const toggle = fixedCosts.get('button')
    const panel = wrapper.get(`#${toggle.attributes('aria-controls')}`)
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(panel.attributes('inert')).toBeUndefined()
    const categories = fixedCosts.findAll('.spending-category-row').map((row) => row.text())
    expect(categories).toEqual(['Rent & utilities$1,450.00', 'Food & drink$82.50'])
    expect(wrapper.get('[aria-label="Savings by category"] .spending-category-row').text()).toBe(
      'Transfers out$300.00',
    )

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(panel.attributes('inert')).toBeDefined()
  })

  it('lists the transactions behind a category when it opens', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    const fixedCosts = wrapper.get('.spending-legend > li')
    const food = fixedCosts.findAll('.spending-categories > li')[1]!
    const toggle = food.get('button')
    expect(toggle.attributes('aria-expanded')).toBe('false')

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(
      wrapper.get(`#${toggle.attributes('aria-controls')}`).attributes('inert'),
    ).toBeUndefined()
    const rows = food.findAll('.spending-transaction-row').map((row) => row.text())
    expect(rows).toEqual(['Whole FoodsSep 14$60.00', "Trader Joe'sSep 3$22.50"])
    // Opening a category leaves its siblings closed.
    const rent = fixedCosts.findAll('.spending-categories > li')[0]!
    expect(rent.get('button').attributes('aria-expanded')).toBe('false')
  })

  it("shows the user's part of a transaction from a shared account", async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingByBucket).mockResolvedValue({
      ...spending,
      total: 1000,
      buckets: [
        {
          bucket: 'FIXED_COSTS',
          amount: 1000,
          categories: [
            {
              category: 'RENT_AND_UTILITIES',
              amount: 1000,
              transactions: [
                {
                  ...coffee,
                  transaction_id: 'rent',
                  account_id: 'joint',
                  amount: 2000,
                  merchant_name: 'Landlord',
                  date: '2026-09-01',
                  share_percent: 50,
                },
              ],
            },
          ],
        },
      ],
    })
    const wrapper = mountHome()
    await flushPromises()

    const rent = wrapper.get('.spending-categories > li')
    await rent.get('button').trigger('click')

    expect(rent.findAll('.spending-transaction-row').map((row) => row.text())).toEqual([
      'LandlordSep 1 · Your 50%$1,000.00',
    ])
  })

  it('labels categories Plaid adds later without a hardcoded name', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingByBucket).mockResolvedValue({
      ...spending,
      total: 40,
      buckets: [
        {
          bucket: 'GUILT_FREE',
          amount: 40,
          categories: [
            { category: 'DIGITAL_ASSETS', amount: 40, transactions: [{ ...coffee, amount: 40 }] },
          ],
        },
      ],
    })
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.spending-category-row').text()).toContain('Digital Assets')
  })

  it('checks back until new transactions are sorted', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingByBucket).mockResolvedValueOnce({
      ...spending,
      buckets: [
        ...spending.buckets,
        {
          bucket: 'UNSORTED',
          amount: 45,
          categories: [
            { category: 'TRAVEL', amount: 45, transactions: [{ ...coffee, amount: 45 }] },
          ],
        },
      ],
    })
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.spending-legend').text()).toContain('Not sorted yet')

    await vi.advanceTimersByTimeAsync(4000)
    await flushPromises()

    expect(getSpendingByBucket).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.spending-legend').text()).not.toContain('Not sorted yet')
    vi.useRealTimers()
  })

  it('leaves the chart alone when a check back finds nothing new', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const waiting: SpendingByBucket = {
      ...spending,
      buckets: [
        ...spending.buckets,
        {
          bucket: 'UNSORTED',
          amount: 45,
          categories: [
            { category: 'TRAVEL', amount: 45, transactions: [{ ...coffee, amount: 45 }] },
          ],
        },
      ],
    }
    // A fresh copy each time, like a real response.
    vi.mocked(getSpendingByBucket).mockImplementation(async () => structuredClone(waiting))
    const wrapper = mountHome()
    await flushPromises()
    const chartData = wrapper.getComponent(ChartStub).props('data')

    await vi.advanceTimersByTimeAsync(4000)
    await flushPromises()

    expect(getSpendingByBucket).toHaveBeenCalledTimes(2)
    // New data rebuilds the chart and replays its animation.
    expect(wrapper.getComponent(ChartStub).props('data')).toBe(chartData)
    vi.useRealTimers()
  })

  it('asks for a spending plan when there is none', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingPlan).mockResolvedValue(null)
    const wrapper = mountHome()
    await flushPromises()

    const prompt = wrapper.get('.spending-plan-prompt')
    expect(prompt.text()).toBe('Create your spending plan to set a target for each bucket.')
    expect(prompt.get('a').attributes('href')).toBe('/spending-plan')
  })

  it('charts buckets before there is a plan, like the transactions list', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingPlan).mockResolvedValue(null)
    const wrapper = mountHome()
    await flushPromises()

    const slices = wrapper.findAll('.chart-stub li').map((slice) => slice.text())
    expect(slices).toEqual(['Fixed costs: 1532.5', 'Guilt-free spending: 12', 'Savings: 300'])
    expect(wrapper.find('[aria-label="Fixed costs by category"]').exists()).toBe(true)
  })

  it('shows the dashboard without waiting for the plan check', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingPlan).mockReturnValue(new Promise(() => {}))
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.findAll('.chart-stub li')).toHaveLength(3)
    expect(wrapper.find('.spending-plan-prompt').exists()).toBe(false)
  })

  it('does not ask for a spending plan once there is one', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.find('.spending-plan-prompt').exists()).toBe(false)
  })

  it('keeps its shape at zero, with every bucket at $0, and offers last month', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingByBucket).mockResolvedValueOnce({ ...spending, total: 0, buckets: [] })
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.find('.chart-stub').exists()).toBe(true)
    expect(wrapper.get('.spending-total-amount').text()).toBe('$0')
    expect(wrapper.findAll('.spending-legend > li').map((row) => row.text())).toEqual([
      'Fixed costs$0.00',
      'Guilt-free spending$0.00',
      'Savings$0.00',
      'Investments$0.00',
    ])
    expect(wrapper.text()).toContain(`Nothing yet in ${periodName('THIS_MONTH')}.`)

    await button(wrapper, 'See last month').trigger('click')
    await flushPromises()

    expect(getSpendingByBucket).toHaveBeenLastCalledWith(undefined, periodRange('LAST_MONTH'))
    expect(wrapper.get('[aria-label="Spending period"]').element).toHaveProperty(
      'value',
      'LAST_MONTH',
    )
    expect(wrapper.text()).not.toContain('See last month')
    expect(localStorage.getItem('moneyBuckets.spendingPeriod')).toBe('LAST_MONTH')
  })

  it('loads the period the user picks, and starts on it next time', async () => {
    localStorage.setItem('moneyBuckets.spendingPeriod', 'LAST_30_DAYS')
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    expect(getSpendingByBucket).toHaveBeenLastCalledWith(undefined, periodRange('LAST_30_DAYS'))
    expect(wrapper.text()).toContain('in the last 30 days')

    await wrapper.get('[aria-label="Spending period"]').setValue('LAST_90_DAYS')
    await flushPromises()

    expect(getSpendingByBucket).toHaveBeenLastCalledWith(undefined, periodRange('LAST_90_DAYS'))
  })

  it('keeps the rest of the dashboard working when the breakdown fails', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getSpendingByBucket).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(wrapper.get('.transaction-name').text()).toBe('Coffee Shop')
    expect(wrapper.text()).toContain('We couldn’t load your spending breakdown.')
  })
})

describe('homepage account selector', () => {
  it('hides the selector when only one account is linked', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.find('[aria-label="Account"]').exists()).toBe(false)
  })

  it('starts on all tracked accounts and switches to one and back', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([checking, savings])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$9,650.50')
    expect(wrapper.get('[aria-label="Account"]').element).toHaveProperty('value', '')
    expect(getTransactions).toHaveBeenLastCalledWith(25, undefined)
    expect(getSpendingByBucket).toHaveBeenLastCalledWith(undefined, periodRange('THIS_MONTH'))
    expect(getRecurringTransactions).toHaveBeenLastCalledWith(undefined, 20)

    await wrapper.get('[aria-label="Account"]').setValue('savings')
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$8,400.00')
    expect(getTransactions).toHaveBeenLastCalledWith(25, 'savings')
    expect(getSpendingByBucket).toHaveBeenLastCalledWith('savings', periodRange('THIS_MONTH'))
    expect(getRecurringTransactions).toHaveBeenLastCalledWith('savings', 20)

    await wrapper.get('[aria-label="Account"]').setValue('')
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$9,650.50')
    expect(getTransactions).toHaveBeenLastCalledWith(25, undefined)
  })

  it('keeps the latest answer when switching away and back outruns an older request', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([checking, savings])
    const wrapper = mountHome()
    await flushPromises()

    let finishFirst!: (value: PlaidTransaction[]) => void
    vi.mocked(getTransactions)
      .mockReturnValueOnce(new Promise((resolve) => (finishFirst = resolve)))
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([{ ...coffee, merchant_name: 'Newest' }])
    const select = wrapper.get('[aria-label="Account"]')
    await select.setValue('savings')
    await select.setValue('')
    await select.setValue('savings')
    await flushPromises()
    finishFirst([{ ...coffee, merchant_name: 'Oldest' }])
    await flushPromises()

    expect(wrapper.get('.transaction-name').text()).toBe('Newest')
  })

  it('loads the cards again when retrying the accounts changes what is shown', async () => {
    localStorage.setItem('abacus.selectedAccountId', 'closed-account')
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts)
      .mockRejectedValueOnce(new Error('Server unavailable'))
      .mockResolvedValue([checking, savings])
    const wrapper = mountHome()
    await flushPromises()
    expect(getTransactions).toHaveBeenLastCalledWith(25, 'closed-account')

    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()

    expect(getTransactions).toHaveBeenLastCalledWith(25, undefined)
    expect(wrapper.get('.balance-amount').text()).toBe('$9,650.50')
  })

  it('restores the last selected account on reload', async () => {
    localStorage.setItem('abacus.selectedAccountId', 'savings')
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getAccounts).mockResolvedValue([checking, savings])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$8,400.00')
    expect(getTransactions).toHaveBeenCalledWith(25, 'savings')
    expect(getRecurringTransactions).toHaveBeenCalledWith('savings', 20)
  })
})

describe('homepage recurring candidates', () => {
  const netflix: RecurringCandidate = {
    stream_id: 'candidate-netflix',
    kind: 'BILL',
    merchant_key: 'netflix',
    account_id: 'checking',
    merchant_name: 'Netflix',
    description: 'Netflix',
    amount: 15.49,
    iso_currency_code: 'USD',
    frequency: 'MONTHLY',
    next_date: '2026-10-19',
    last_date: '2026-09-19',
    is_inflow: false,
    category: 'ENTERTAINMENT',
    category_detailed: 'ENTERTAINMENT_TV_AND_MOVIES',
    share_percent: 100,
    plan_bucket: null,
    plan_line: null,
    probability: 0.93,
    status: 'SUGGESTED',
  }

  it('asks about payments Jev thinks repeat', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringCandidates).mockResolvedValue([netflix])
    const wrapper = mountHome()
    await flushPromises()

    expect(getRecurringCandidates).toHaveBeenCalledWith(undefined)
    expect(wrapper.get('#candidates-heading').text()).toBe('Possibly recurring')
    expect(wrapper.get('.candidates .transaction-name').text()).toBe('Netflix')
    expect(wrapper.get('.candidates .transaction-meta').text()).toBe(
      'Monthly? · Last Sep 19 · -$15.49',
    )
  })

  it('moves a confirmed payment into the recurring list', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringCandidates).mockResolvedValue([netflix])
    vi.mocked(answerRecurringCandidate).mockResolvedValue()
    const wrapper = mountHome()
    await flushPromises()

    await wrapper.get('[aria-label="Yes, Netflix repeats"]').trigger('click')
    await flushPromises()

    expect(answerRecurringCandidate).toHaveBeenCalledWith(netflix, true)
    expect(wrapper.find('.candidates').exists()).toBe(false)
    const names = wrapper.findAll('.recurring-card .transaction-name').map((name) => name.text())
    expect(names).toEqual(['Landlord', 'Netflix'])
    // The template's line breaks collapse to single spaces on screen.
    const meta = wrapper
      .findAll('.recurring-card .transaction-meta')[1]
      ?.text()
      .replace(/\s+/g, ' ')
    expect(meta).toBe('Monthly · Next Oct 19 · Confirmed by you · Undo')
  })

  it('keeps dismissed payments out of the way until asked, and undoes them', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringCandidates).mockResolvedValue([netflix])
    vi.mocked(answerRecurringCandidate).mockResolvedValue()
    vi.mocked(undoRecurringAnswer).mockResolvedValue()
    const wrapper = mountHome()
    await flushPromises()

    await wrapper.get('[aria-label="No, Netflix doesn’t repeat"]').trigger('click')
    await flushPromises()

    expect(answerRecurringCandidate).toHaveBeenCalledWith(netflix, false)
    expect(wrapper.find('.candidates').exists()).toBe(false)
    expect(wrapper.find('#dismissed-candidates').exists()).toBe(false)

    await wrapper.get('.dismissed-toggle').trigger('click')
    expect(wrapper.get('#dismissed-candidates').text()).toContain('Netflix')

    await wrapper.get('[aria-label="Undo dismissing Netflix"]').trigger('click')
    await flushPromises()

    expect(undoRecurringAnswer).toHaveBeenCalledWith(
      expect.objectContaining({ merchant_key: 'netflix' }),
    )
    expect(wrapper.get('.candidates .transaction-name').text()).toBe('Netflix')
    expect(wrapper.find('.dismissed-toggle').exists()).toBe(false)
  })

  it('puts a payment back when its answer can’t be saved', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringCandidates).mockResolvedValue([netflix])
    vi.mocked(answerRecurringCandidate).mockRejectedValue(new Error('offline'))
    const wrapper = mountHome()
    await flushPromises()

    await wrapper.get('[aria-label="Yes, Netflix repeats"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.candidates .transaction-name').text()).toBe('Netflix')
    expect(wrapper.text()).toContain('We couldn’t save your answer. Please try again.')
  })

  it('still lists Plaid’s streams when candidates can’t be loaded', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringCandidates).mockRejectedValue(new Error('offline'))
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.recurring-card .transaction-name').text()).toBe('Landlord')
    expect(wrapper.find('.candidates').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('We couldn’t load your recurring transactions.')
  })
})

describe('homepage recurring transactions', () => {
  it('labels a stream from its category when Plaid sent no merchant name', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringTransactions).mockResolvedValue([
      { ...rent, merchant_name: '', description: '   ', category: 'RENT_AND_UTILITIES' },
    ])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.recurring-card .transaction-name').text()).toBe('Rent & utilities')
    expect(wrapper.get('.recurring-card .transaction-logo-fallback').text()).toBe('R')
  })

  it('renders every recurring stream in a scrollable list', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringTransactions).mockResolvedValue(
      Array.from({ length: 8 }, (_, index) => ({
        ...rent,
        stream_id: `stream-${index}`,
        merchant_name: `Bill ${index + 1}`,
      })),
    )
    const wrapper = mountHome()
    await flushPromises()

    const list = wrapper.get('.recurring-card .transactions-list')
    expect(list.attributes('tabindex')).toBe('0')
    expect(wrapper.findAll('.recurring-card .transaction-name')).toHaveLength(8)
    const names = wrapper.findAll('.recurring-card .transaction-name')
    expect(names[names.length - 1]?.text()).toBe('Bill 8')
  })

  it('lists upcoming recurring charges with cadence and next date', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    const wrapper = mountHome()
    await flushPromises()

    expect(getRecurringTransactions).toHaveBeenCalledWith(undefined, 20)
    expect(wrapper.get('#recurring-heading').text()).toBe('Recurring')
    expect(wrapper.get('.recurring-card .transaction-name').text()).toBe('Landlord')
    expect(wrapper.get('.recurring-card .transaction-meta').text()).toBe('Monthly · Next Oct 1')
    expect(wrapper.get('.recurring-card .transaction-amount').text()).toBe('-$1,450.00')
  })

  it('shows a paycheck as a positive amount', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringTransactions).mockResolvedValue([
      { ...rent, stream_id: 'pay', merchant_name: 'Payroll', amount: -2400, is_inflow: true },
    ])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.recurring-card .transaction-amount').text()).toBe('+$2,400.00')
    expect(wrapper.get('.recurring-card .transaction-amount').classes()).toContain(
      'transaction-amount-inflow',
    )
  })

  it('shows an empty state when Plaid has not identified streams', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringTransactions).mockResolvedValue([])
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('No recurring transactions found yet.')
  })

  it('asks Plaid again for recurring transactions when Sync is pressed', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringTransactions).mockResolvedValueOnce([]).mockResolvedValueOnce([rent])
    vi.mocked(syncRecurringTransactions).mockResolvedValue()
    const wrapper = mountHome()
    await flushPromises()

    await button(wrapper, 'Sync').trigger('click')
    await flushPromises()

    expect(syncRecurringTransactions).toHaveBeenCalledOnce()
    expect(wrapper.findAll('.recurring-card .transaction-row')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('No recurring transactions found yet.')
  })

  it('says so when a recurring sync fails', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(syncRecurringTransactions).mockRejectedValue(new Error('Server unavailable'))
    const wrapper = mountHome()
    await flushPromises()

    await button(wrapper, 'Sync').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t sync your recurring transactions.')
  })

  it('syncs again when trying again after a failed sync', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringTransactions).mockResolvedValueOnce([]).mockResolvedValue([rent])
    vi.mocked(syncRecurringTransactions)
      .mockRejectedValueOnce(new Error('Server unavailable'))
      .mockResolvedValue()
    const wrapper = mountHome()
    await flushPromises()
    await button(wrapper, 'Sync').trigger('click')
    await flushPromises()

    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()

    expect(syncRecurringTransactions).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.recurring-card .transaction-row')).toHaveLength(1)
  })

  it('keeps the rest of the dashboard working when recurring streams fail', async () => {
    vi.mocked(getLinkedItemIds).mockResolvedValue(['saved-item'])
    vi.mocked(getRecurringTransactions).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('.balance-amount').text()).toBe('$1,250.50')
    expect(wrapper.get('.transaction-name').text()).toBe('Coffee Shop')
    expect(wrapper.text()).toContain('We couldn’t load your recurring transactions.')
  })
})
