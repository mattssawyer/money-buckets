import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import RecurringDialog from '../RecurringDialog.vue'
import SpendingPlanSetup from '../SpendingPlanSetup.vue'
import {
  getRecurringCandidates,
  getRecurringTransactions,
  getSpendingByBucket,
  type PlaidTransaction,
  type RecurringCandidate,
  type RecurringStream,
  type SpendingByBucket,
} from '../../api/PlaidService'

import { saveSpendingPlan, type SpendingPlanRequest } from '../../api/SpendingPlanService'
import { defaultPlan } from '../../spendingPlan/plan'
import type { SavedPlan } from '../../spendingPlan/savedPlan'

vi.mock('../../api/PlaidService', () => ({
  getRecurringTransactions: vi.fn(),
  getRecurringCandidates: vi.fn<(accountId?: string) => Promise<RecurringCandidate[]>>(),
  getSpendingByBucket: vi.fn(),
}))

vi.mock('../../api/SpendingPlanService', () => ({
  saveSpendingPlan: vi.fn(),
}))

enableAutoUnmount(afterEach)

const paycheck: RecurringStream = {
  stream_id: 'pay',
  account_id: 'checking',
  merchant_name: 'Payroll',
  description: 'PAYROLL',
  amount: -2400,
  iso_currency_code: 'USD',
  frequency: 'BIWEEKLY',
  frequency_set: false,
  next_date: '2026-09-25',
  last_date: '2026-09-11',
  is_inflow: true,
  category: 'INCOME',
  category_detailed: 'INCOME_WAGES',
  share_percent: 100,
  plan_bucket: null,
  plan_line: null,
  kind: null,
  merchant_key: null,
  status: 'DETECTED',
}

const rent: RecurringStream = {
  stream_id: 'rent',
  account_id: 'checking',
  merchant_name: 'Landlord',
  description: 'RENT',
  amount: 1450,
  iso_currency_code: 'USD',
  frequency: 'MONTHLY',
  frequency_set: false,
  next_date: '2026-10-01',
  last_date: '2026-09-01',
  is_inflow: false,
  category: 'RENT_AND_UTILITIES',
  category_detailed: 'RENT_AND_UTILITIES_RENT',
  share_percent: 100,
  plan_bucket: 'FIXED_COSTS',
  plan_line: 'Rent/mortgage',
  kind: null,
  merchant_key: null,
  status: 'DETECTED',
}

function mountSetup(props: InstanceType<typeof SpendingPlanSetup>['$props'] = {}) {
  return mount(SpendingPlanSetup, {
    props,
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
        // It loads spending of its own; SpendingExplorer.spec.ts covers it.
        SpendingExplorer: true,
        // RecurringDialog.spec.ts covers answering in it; these tests only say it changed.
        RecurringDialog: true,
      },
    },
    attachTo: document.body,
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  document.body.innerHTML = ''
  vi.mocked(getRecurringTransactions).mockResolvedValue([paycheck, rent])
  vi.mocked(getRecurringCandidates).mockResolvedValue([])
  vi.mocked(getSpendingByBucket).mockResolvedValue({
    start: '2026-07-01',
    end: '2026-09-30',
    total: 0,
    buckets: [],
  })
  vi.mocked(saveSpendingPlan).mockImplementation(async (request: SpendingPlanRequest) => ({
    ...request,
    updated_at: '2026-09-22T19:30:00Z',
  }))
})

function saveButton(wrapper: ReturnType<typeof mountSetup>) {
  const button = wrapper.findAll('button').find((element) => element.text() === 'Save plan')
  if (!button) throw new Error('Save button not found')
  return button
}

const savedPlan: SavedPlan = {
  accountId: 'checking',
  takeHome: 5200,
  bufferPercent: 10,
  updatedAt: '2026-09-20T12:00:00Z',
  plan: {
    ...defaultPlan(),
    fixedCosts: [
      { name: 'Rent/mortgage', amount: 1500, items: [], fromPaycheck: false },
      {
        name: 'Subscriptions',
        amount: null,
        fromPaycheck: false,
        items: [{ name: 'Netflix', amount: 15.49, streamId: 'netflix' }],
      },
    ],
  },
}

// Rent Plaid labels home improvement, before Jev has put it on a line.
const sterlingRent: RecurringStream = {
  ...rent,
  stream_id: 'sterling',
  merchant_name: 'Sterling Group',
  description: 'STERLING GROUP WEB PMTS',
  amount: 1554.91,
  category: 'HOME_IMPROVEMENT',
  category_detailed: 'HOME_IMPROVEMENT_REPAIR_AND_MAINTENANCE',
  plan_bucket: null,
  plan_line: null,
}

function groceries(amount: number, date: string): PlaidTransaction {
  return {
    transaction_id: `groceries-${date}`,
    account_id: 'checking',
    amount,
    iso_currency_code: 'USD',
    date,
    name: 'WHOLE FOODS',
    merchant_name: 'Whole Foods',
    logo_url: null,
    pending: false,
    category: 'FOOD_AND_DRINK',
    category_detailed: 'FOOD_AND_DRINK_GROCERIES',
    bucket: 'FIXED_COSTS',
    plan_line: 'Groceries',
    share_percent: 100,
    payee_key: 'whole foods',
    payee_kind: 'BILL',
    bucket_corrected: false,
    category_corrected: false,
    recurring: null,
  }
}

/** Three months of groceries averaging $600 a month. */
const groceriesSpending: SpendingByBucket = {
  start: '2026-07-01',
  end: '2026-09-30',
  total: 1800,
  buckets: [
    {
      bucket: 'FIXED_COSTS',
      amount: 1800,
      categories: [
        {
          category: 'FOOD_AND_DRINK',
          amount: 1800,
          transactions: [
            groceries(650, '2026-07-04'),
            groceries(550, '2026-08-11'),
            groceries(600, '2026-09-20'),
          ],
        },
      ],
    },
  ],
}

function spendOnGroceries() {
  vi.mocked(getSpendingByBucket).mockResolvedValue(groceriesSpending)
}

describe('spending plan setup', () => {
  it('defaults a line with no recurring bill to what you usually spend on it', async () => {
    spendOnGroceries()
    const wrapper = mountSetup()
    await flushPromises()

    const amount = wrapper.get<HTMLInputElement>('input[aria-label="Groceries amount"]')
    expect(amount.element.value).toBe('')
    expect(amount.attributes('placeholder')).toBe('600')
    // 1,450 rent + 600 groceries, plus the 15% buffer.
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toContain('$2,357.50')

    await amount.setValue('500')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toContain('$2,242.50')

    await amount.setValue('')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toContain('$2,357.50')

    await saveButton(wrapper).trigger('click')
    await flushPromises()
    const saved = vi.mocked(saveSpendingPlan).mock.calls[0]?.[0]
    expect(saved?.lines.find((line) => line.name === 'Groceries')?.amount).toBe(600)
    expect(saved?.lines.find((line) => line.name === 'Clothes')?.amount).toBeNull()
  })

  it('keeps a default amount when you break the line down', async () => {
    spendOnGroceries()
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper.get('button[aria-label="Show Groceries breakdown"]').trigger('click')
    const breakdown = wrapper.get('[aria-label="Groceries breakdown"]')
    const addItem = breakdown.findAll('button').find((button) => button.text() === 'Add an item')
    await addItem?.trigger('click')

    expect(wrapper.get('[aria-label="Groceries amount"]').text()).toBe('600')
  })

  it('waits for a saved plan’s defaults before saving or breaking a line down', async () => {
    let answer: (spending: SpendingByBucket) => void = () => {}
    vi.mocked(getSpendingByBucket).mockReturnValue(new Promise((resolve) => (answer = resolve)))
    const wrapper = mountSetup({
      saved: {
        ...savedPlan,
        plan: {
          ...savedPlan.plan,
          fixedCosts: [{ name: 'Groceries', amount: null, items: [], fromPaycheck: false }],
        },
      },
    })
    await flushPromises()

    await wrapper.get('button[aria-label="Show Groceries breakdown"]').trigger('click')
    const addItem = () =>
      wrapper
        .get('[aria-label="Groceries breakdown"]')
        .findAll('button')
        .find((button) => button.text() === 'Add an item')
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()
    expect(addItem()?.attributes('disabled')).toBeDefined()

    // Clicking early neither saves nor adds a blank item.
    await saveButton(wrapper).trigger('click')
    await addItem()?.trigger('click')
    expect(saveSpendingPlan).not.toHaveBeenCalled()
    expect(wrapper.find('input[aria-label="Item amount"]').exists()).toBe(false)

    answer(groceriesSpending)
    await flushPromises()

    expect(saveButton(wrapper).attributes('disabled')).toBeUndefined()
    await addItem()?.trigger('click')
    expect(wrapper.get('[aria-label="Groceries amount"]').text()).toBe('600')

    await saveButton(wrapper).trigger('click')
    await flushPromises()
    const saved = vi.mocked(saveSpendingPlan).mock.calls[0]?.[0]
    expect(saved?.lines.find((line) => line.name === 'Groceries')?.items[0]?.amount).toBe(600)
  })

  it('lets a saved plan be saved when its defaults can’t be loaded', async () => {
    vi.mocked(getSpendingByBucket).mockRejectedValue(new Error('offline'))
    const wrapper = mountSetup({ saved: savedPlan })
    await flushPromises()

    expect(saveButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('defaults a saved plan’s blank lines too, and keeps the amounts it was saved with', async () => {
    spendOnGroceries()
    const wrapper = mountSetup({
      saved: {
        ...savedPlan,
        plan: {
          ...savedPlan.plan,
          fixedCosts: [
            ...savedPlan.plan.fixedCosts,
            { name: 'Groceries', amount: null, items: [], fromPaycheck: false },
          ],
        },
      },
    })
    await flushPromises()

    expect(getRecurringTransactions).not.toHaveBeenCalled()
    expect(wrapper.get('input[aria-label="Groceries amount"]').attributes('placeholder')).toBe(
      '600',
    )
    const rent = wrapper.get<HTMLInputElement>('input[aria-label="Rent/mortgage amount"]')
    expect(rent.element.value).toBe('1500')
  })

  it('fills in a bill on the line Jev put it on, whatever Plaid calls it', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([
      paycheck,
      { ...sterlingRent, plan_bucket: 'FIXED_COSTS', plan_line: 'Rent/mortgage' },
    ])
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').text()).toBe('1554.91')
    expect(wrapper.find('[aria-labelledby="unplaced-heading"]').exists()).toBe(false)
  })

  it('lists bills on no line and adds one to the line the user picks', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([paycheck, sterlingRent])
    const wrapper = mountSetup()
    await flushPromises()

    const list = wrapper.get('[aria-labelledby="unplaced-heading"]')
    expect(list.text()).toContain('Sterling Group')
    expect(list.text()).toContain('$1,554.91/mo')
    expect(wrapper.get('[aria-label="Add Sterling Group"]').attributes('disabled')).toBeDefined()

    const select = wrapper.get('[aria-label="Line for Sterling Group"]')
    const rentRow = select.findAll('option').find((option) => option.text() === 'Rent/mortgage')
    await select.setValue(rentRow!.attributes('value'))
    await wrapper.get('[aria-label="Add Sterling Group"]').trigger('click')

    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').text()).toBe('1554.91')
    expect(wrapper.find('[aria-labelledby="unplaced-heading"]').exists()).toBe(false)
  })

  it('asks for another line when the one picked for a bill is removed', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([paycheck, sterlingRent])
    const wrapper = mountSetup()
    await flushPromises()

    const select = wrapper.get('[aria-label="Line for Sterling Group"]')
    const utilities = select.findAll('option').find((option) => option.text() === 'Utilities')
    await select.setValue(utilities!.attributes('value'))
    await wrapper.get('[aria-label="Remove Utilities"]').trigger('click')

    expect(wrapper.get('[aria-label="Add Sterling Group"]').attributes('disabled')).toBeDefined()
    expect((select.element as HTMLSelectElement).value).toBe('')
  })

  it('lets the user skip a bill that belongs on no line', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([paycheck, sterlingRent])
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper.get('[aria-label="Skip Sterling Group"]').trigger('click')

    expect(wrapper.find('[aria-labelledby="unplaced-heading"]').exists()).toBe(false)
    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').element).toHaveProperty('value', '')
  })

  it('estimates from every tracked account and links to choosing them', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    expect(getRecurringTransactions).toHaveBeenCalledWith(undefined, 50)
    expect(wrapper.find('[aria-label="Account"]').exists()).toBe(false)
    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '5200')
    expect(wrapper.get('.autofill-note').text()).toBe(
      'Amounts are filled in from your tracked accounts’ recurring transactions and what you usually spend. Choose accounts',
    )
    expect(wrapper.get('.autofill-note a').attributes('href')).toBe('/accounts')
    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').text()).toBe('1450')
    expect(wrapper.get('[aria-label="Utilities amount"]').element).toHaveProperty('value', '')
    expect(wrapper.find('[aria-label="Landlord name"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Should be 50–60% of income')
    expect(wrapper.text()).toContain('32%')
  })

  it("counts a shared account's rent at the user's share", async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([
      paycheck,
      { ...rent, account_id: 'joint', amount: 2400, share_percent: 50 },
    ])
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').text()).toBe('1200')
  })

  it('fills in payments the user confirmed, and points to the ones they haven’t answered', async () => {
    const candidate = (overrides: Partial<RecurringCandidate>): RecurringCandidate => ({
      ...rent,
      kind: 'BILL',
      merchant_key: 'netflix',
      probability: 0.9,
      status: 'SUGGESTED',
      ...overrides,
    })
    vi.mocked(getRecurringTransactions).mockResolvedValue([rent])
    vi.mocked(getRecurringCandidates).mockResolvedValue([
      candidate({
        stream_id: 'candidate-pay',
        kind: 'PAYCHECK',
        merchant_name: 'Acme payroll',
        amount: -2400,
        frequency: 'BIWEEKLY',
        is_inflow: true,
        category: 'INCOME',
        category_detailed: 'INCOME_WAGES',
        status: 'CONFIRMED',
      }),
      candidate({
        stream_id: 'candidate-netflix',
        merchant_name: 'Netflix',
        amount: 15.49,
        category_detailed: 'ENTERTAINMENT_TV_AND_MOVIES',
        status: 'SUGGESTED',
      }),
      candidate({
        stream_id: 'candidate-hulu',
        merchant_name: 'Hulu',
        amount: 7.99,
        category_detailed: 'ENTERTAINMENT_TV_AND_MOVIES',
        status: 'DISMISSED',
      }),
    ])
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '5200')
    expect(wrapper.get('[aria-label="Subscriptions amount"]').element).toHaveProperty('value', '')
    const notes = wrapper.findAll('.autofill-note')
    expect(notes[1]?.text().replace(/\s+/g, ' ')).toBe(
      '1 payment might be recurring. Confirm them to fill them in. Review them',
    )

    await notes[1]?.get('button').trigger('click')
    const dialog = wrapper.getComponent(RecurringDialog)
    expect(dialog.props('visible')).toBe(true)
    expect(dialog.props('startOn')).toBe('possible')
  })

  it('opens the recurring payments from the plan', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper.get('.recurring-button').trigger('click')

    const dialog = wrapper.getComponent(RecurringDialog)
    expect(dialog.props('visible')).toBe(true)
    expect(dialog.props('startOn')).toBe('recurring')
    expect(dialog.props('accountId')).toBeUndefined()
  })

  it('brings the plan up to date with recurring answers, keeping your own edits', async () => {
    const gym: RecurringStream = {
      ...rent,
      stream_id: 'gym',
      merchant_name: 'Gym',
      amount: 40,
      plan_line: 'Utilities',
    }
    const netflix: RecurringCandidate = {
      ...rent,
      stream_id: 'candidate-netflix',
      merchant_name: 'Netflix',
      amount: 15.49,
      plan_line: 'Subscriptions',
      kind: 'BILL',
      merchant_key: 'netflix',
      probability: 0.9,
      status: 'SUGGESTED',
    }
    vi.mocked(getRecurringTransactions).mockResolvedValue([paycheck, rent, gym])
    vi.mocked(getRecurringCandidates).mockResolvedValue([netflix])
    const wrapper = mountSetup()
    await flushPromises()
    await wrapper.get('#take-home-income').setValue('5000')
    await wrapper.get('[aria-label="Groceries amount"]').setValue('500')
    expect(wrapper.get('[aria-label="Utilities amount"]').text()).toBe('40')

    // In the dialog, the user confirms Netflix and says the gym doesn't repeat.
    vi.mocked(getRecurringTransactions).mockResolvedValue([paycheck, rent])
    vi.mocked(getRecurringCandidates).mockResolvedValue([{ ...netflix, status: 'CONFIRMED' }])
    await wrapper.get('.recurring-button').trigger('click')
    wrapper.getComponent(RecurringDialog).vm.$emit('changed')
    await flushPromises()

    expect(wrapper.get('[aria-label="Subscriptions amount"]').text()).toBe('15.49')
    expect(wrapper.get('[aria-label="Utilities amount"]').element).toHaveProperty('value', '')
    expect(wrapper.get('[aria-label="Groceries amount"]').element).toHaveProperty('value', '500')
    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '5000')
    expect(wrapper.findAll('.autofill-note')).toHaveLength(1)
  })

  it('leaves a bill the user skipped off the plan after recurring answers', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([paycheck, rent, sterlingRent])
    const wrapper = mountSetup()
    await flushPromises()
    await wrapper.get('[aria-label="Skip Sterling Group"]').trigger('click')

    await wrapper.get('.recurring-button').trigger('click')
    wrapper.getComponent(RecurringDialog).vm.$emit('changed')
    await flushPromises()

    expect(wrapper.find('[aria-labelledby="unplaced-heading"]').exists()).toBe(false)
    // Take-home pay the user didn't touch follows the paycheck.
    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '5200')
  })

  it('adds a payment confirmed while editing a saved plan to its line', async () => {
    const wrapper = mountSetup({ saved: savedPlan })
    await flushPromises()
    await wrapper.get('.recurring-button').trigger('click')
    expect(getRecurringTransactions).toHaveBeenCalledTimes(1)

    vi.mocked(getRecurringTransactions).mockResolvedValue([
      paycheck,
      rent,
      {
        ...rent,
        stream_id: 'water',
        merchant_name: 'City Water',
        amount: 60,
        plan_line: 'Utilities',
      },
    ])
    wrapper.getComponent(RecurringDialog).vm.$emit('changed')
    await flushPromises()

    expect(wrapper.get('[aria-label="Utilities amount"]').text()).toBe('60')
    // Rent was recurring before, so the amount the plan was saved with stays.
    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').element).toHaveProperty(
      'value',
      '1500',
    )
    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '5200')
  })

  it('fills in Plaid’s recurring payments when candidates can’t be loaded', async () => {
    vi.mocked(getRecurringCandidates).mockRejectedValue(new Error('offline'))
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').text()).toBe('1450')
    expect(wrapper.findAll('.autofill-note')).toHaveLength(1)
  })

  it('lets you edit, add, and remove fixed cost rows', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper.get('[aria-label="Utilities name"]').setValue('Power')
    await wrapper.get('[aria-label="Power amount"]').setValue('370')
    expect(wrapper.text()).toContain('40%')

    await wrapper.get('button[aria-label="Remove Power"]').trigger('click')
    expect(wrapper.find('[aria-label="Power name"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('32%')

    await wrapper.get('button.add-cost').trigger('click')
    expect(wrapper.find('[aria-label="Cost name"]').exists()).toBe(true)
  })

  it('shows the recurring bills behind a line and lets you edit them', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([
      paycheck,
      rent,
      {
        ...rent,
        stream_id: 'netflix',
        merchant_name: 'Netflix',
        amount: 15.49,
        category: 'ENTERTAINMENT',
        category_detailed: 'ENTERTAINMENT_TV_AND_MOVIES',
        plan_bucket: 'FIXED_COSTS',
        plan_line: 'Subscriptions',
      },
      {
        ...rent,
        stream_id: 'spotify',
        merchant_name: 'Spotify',
        amount: 11.99,
        category: 'ENTERTAINMENT',
        category_detailed: 'ENTERTAINMENT_MUSIC_AND_AUDIO',
        plan_bucket: 'FIXED_COSTS',
        plan_line: 'Subscriptions',
      },
    ])
    const wrapper = mountSetup()
    await flushPromises()

    const toggle = wrapper.get('button[aria-label="Show Subscriptions breakdown"]')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[aria-label="Subscriptions breakdown"]').exists()).toBe(false)
    expect(wrapper.get('[aria-label="Subscriptions amount"]').text()).toBe('27.48')

    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    const breakdown = wrapper.get('[aria-label="Subscriptions breakdown"]')
    expect(breakdown.get('[aria-label="Netflix amount"]').element).toHaveProperty('value', '15.49')

    await breakdown.get('[aria-label="Netflix amount"]').setValue('22.99')
    expect(wrapper.get('[aria-label="Subscriptions amount"]').text()).toBe('34.98')

    await breakdown.get('[aria-label="Spotify name"]').setValue('Spotify Family')
    await breakdown.get('button[aria-label="Remove Spotify Family"]').trigger('click')
    expect(wrapper.get('[aria-label="Subscriptions amount"]').text()).toBe('22.99')

    await breakdown.get('button.add-item').trigger('click')
    await breakdown.get('[aria-label="Item amount"]').setValue('10')
    expect(wrapper.get('[aria-label="Subscriptions amount"]').text()).toBe('32.99')
  })

  it('keeps a typed amount when you break the line down, and when you undo it', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper.get('[aria-label="Groceries amount"]').setValue('400')
    await wrapper.get('button[aria-label="Show Groceries breakdown"]').trigger('click')
    const breakdown = wrapper.get('[aria-label="Groceries breakdown"]')
    await breakdown.get('button.add-item').trigger('click')

    expect(breakdown.get('[aria-label="Groceries amount"]').element).toHaveProperty('value', '400')
    expect(wrapper.get('.cost-line > .sheet-row [aria-label="Groceries amount"]').text()).toBe(
      '400',
    )

    await breakdown.get('[aria-label="Groceries amount"]').setValue('450')
    await breakdown.get('button[aria-label="Remove Groceries"]').trigger('click')
    expect(wrapper.get('[aria-label="Groceries amount"]').element).toHaveProperty('value', '450')
  })

  it('starts with the spreadsheet cost rows when the account has no recurring bills', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([])
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '')
    expect(wrapper.get('[aria-label="Rent/mortgage name"]').element).toHaveProperty(
      'value',
      'Rent/mortgage',
    )
    expect(wrapper.get('[aria-label="Subscriptions amount"]').element).toHaveProperty('value', '')
    expect(wrapper.get('[aria-label="Groceries amount"]').element).toHaveProperty('value', '')
    expect(wrapper.find('.total-share').exists()).toBe(false)
  })

  it('still lets you build a plan when recurring streams fail to load', async () => {
    vi.mocked(getRecurringTransactions).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '')
    expect(wrapper.find('[aria-label="Rent/mortgage name"]').exists()).toBe(true)
    await wrapper.get('#take-home-income').setValue('4000')
    await wrapper.get('[aria-label="Rent/mortgage amount"]').setValue('1600')
    expect(wrapper.text()).toContain('46%')
  })

  it('lists the spreadsheet investment and savings lines, filled from transfers', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([
      paycheck,
      rent,
      {
        ...rent,
        stream_id: 'vanguard',
        merchant_name: 'Vanguard',
        amount: 520,
        category: 'TRANSFER_OUT',
        category_detailed: 'TRANSFER_OUT_INVESTMENT_AND_RETIREMENT_FUNDS',
        plan_bucket: 'INVESTMENTS',
        plan_line: 'Other investments',
      },
      {
        ...rent,
        stream_id: 'ally',
        merchant_name: 'Ally',
        amount: 260,
        category: 'TRANSFER_OUT',
        category_detailed: 'TRANSFER_OUT_SAVINGS',
        plan_bucket: 'SAVINGS',
        plan_line: 'Emergency fund',
      },
    ])
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.text()).toContain('Should be about 10% of income')
    expect(wrapper.text()).toContain('Should be 5–10% of income')
    expect(wrapper.get('[aria-label="401(k) amount"]').element).toHaveProperty('value', '')
    expect(wrapper.get('[aria-label="Roth IRA amount"]').element).toHaveProperty('value', '')
    expect(wrapper.get('[aria-label="Other investments amount"]').text()).toBe('520')
    expect(wrapper.get('[aria-label="Investments total"]').text()).toBe('$52010%')
    expect(wrapper.get('[aria-label="Emergency fund amount"]').text()).toBe('260')
    expect(wrapper.get('[aria-label="Vacations amount"]').element).toHaveProperty('value', '')
    expect(wrapper.get('[aria-label="Savings total"]').text()).toBe('$2605%')
  })

  it('adds and removes lines in each bucket on its own', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper
      .findAll('button.add-line')
      .find((button) => button.text() === 'Add a savings goal')
      ?.trigger('click')
    await wrapper.get('[aria-label="Savings goal name"]').setValue('Wedding')
    await wrapper.get('[aria-label="Wedding amount"]').setValue('300')
    expect(wrapper.get('[aria-label="Savings total"]').text()).toContain('$300')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toContain('$1,667.50')

    await wrapper.get('button[aria-label="Remove 401(k)"]').trigger('click')
    expect(wrapper.find('[aria-label="401(k) name"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Roth IRA name"]').exists()).toBe(true)
  })

  it('shows what is left for guilt-free spending', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.text()).toContain('Should be 20–35% of income')
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$3,532.5068%')

    await wrapper.get('[aria-label="Roth IRA amount"]').setValue('520')
    await wrapper.get('[aria-label="Vacations amount"]').setValue('260')
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$2,752.5053%')
    expect(wrapper.find('.guilt-free-warning').exists()).toBe(false)
  })

  it('warns when the plan adds up to more than take-home pay', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper.get('#take-home-income').setValue('1500')
    await wrapper.get('[aria-label="Roth IRA amount"]').setValue('250')

    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('-$417.50-28%')
    expect(wrapper.get('.guilt-free-warning').text()).toBe(
      'Your plan is $417.50 more than your take-home pay.',
    )
  })

  it('marks fixed costs over their target', async () => {
    const wrapper = mountSetup()
    await flushPromises()
    const fixedCostsShare = () => wrapper.get('[aria-label="Fixed costs total"] .total-share')

    expect(fixedCostsShare().classes()).not.toContain('total-share-over')

    await wrapper.get('#take-home-income').setValue('1500')

    expect(fixedCostsShare().classes()).toContain('total-share-over')
  })

  it('adds a paycheck 401(k) back to income instead of subtracting it twice', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.get('[aria-label="401(k): from paycheck"]').element).toHaveProperty(
      'checked',
      true,
    )
    expect(wrapper.get('[aria-label="Roth IRA: from paycheck"]').element).toHaveProperty(
      'checked',
      false,
    )
    expect(wrapper.find('[aria-label="Plan income"]').exists()).toBe(false)

    await wrapper.get('[aria-label="401(k) amount"]').setValue('600')

    expect(wrapper.get('[aria-label="Investments taken from your paycheck"]').text()).toBe('+$600')
    expect(wrapper.get('[aria-label="Plan income"]').text()).toBe('$5,800')
    expect(wrapper.get('[aria-label="Investments total"]').text()).toBe('$60010%')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toBe('$1,667.5029%')
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$3,532.5061%')

    await wrapper.get('[aria-label="401(k): from paycheck"]').setValue(false)

    expect(wrapper.find('[aria-label="Plan income"]').exists()).toBe(false)
    expect(wrapper.get('[aria-label="Investments total"]').text()).toBe('$60012%')
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$2,932.5056%')
  })

  it('asks for take-home pay before showing guilt-free spending', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([])
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.find('[aria-label="Guilt-free spending total"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Enter your take-home pay to see what’s left to spend.')

    await wrapper.get('#take-home-income').setValue('4000')
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$4,000100%')
  })

  it('saves the whole plan without tying it to one account', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper.get('[aria-label="401(k) amount"]').setValue('600')
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    const request = vi.mocked(saveSpendingPlan).mock.calls[0]?.[0]
    expect(request?.account_id).toBeNull()
    expect(request?.take_home).toBe(5200)
    expect(request?.fixed_cost_buffer_percent).toBe(15)
    expect(request?.lines.find((line) => line.name === 'Rent/mortgage')?.items).toEqual([
      { name: 'Landlord', amount: 1450, stream_id: 'rent' },
    ])
    expect(request?.lines.find((line) => line.name === '401(k)')).toMatchObject({
      bucket: 'INVESTMENTS',
      amount: 600,
      from_paycheck: true,
    })

    const saved = wrapper.emitted('saved')?.[0]?.[0] as SavedPlan
    expect(saved.accountId).toBeNull()
    expect(saved.updatedAt).toBe('2026-09-22T19:30:00Z')
  })

  it('keeps your edits and says so when saving fails', async () => {
    vi.mocked(saveSpendingPlan).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper.get('[aria-label="Utilities amount"]').setValue('120')
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('We couldn’t save your plan. Try again.')
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(wrapper.get('[aria-label="Utilities amount"]').element).toHaveProperty('value', '120')
  })

  it('adds an adjustable miscellaneous buffer on top of fixed costs', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.get('#fixed-cost-buffer').element).toHaveProperty('value', '15')
    expect(wrapper.get('[aria-label="Miscellaneous buffer amount"]').text()).toBe('217.5')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toBe('$1,667.5032%')

    await wrapper.get('#fixed-cost-buffer').setValue('10')
    expect(wrapper.get('[aria-label="Miscellaneous buffer amount"]').text()).toBe('145')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toBe('$1,59531%')

    await wrapper.get('#fixed-cost-buffer').setValue('')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toBe('$1,45028%')

    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(vi.mocked(saveSpendingPlan).mock.calls[0]?.[0].fixed_cost_buffer_percent).toBe(0)
  })

  it('starts on the buffer when asked to', async () => {
    const atBuffer = mountSetup({ saved: savedPlan, focusBuffer: true })
    await flushPromises()
    expect(atBuffer.get('#fixed-cost-buffer').attributes('autofocus')).toBeDefined()

    const atTop = mountSetup({ saved: savedPlan })
    await flushPromises()
    expect(atTop.get('#fixed-cost-buffer').attributes('autofocus')).toBeUndefined()
  })

  it('caps the buffer at 100 percent', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper.get('#fixed-cost-buffer').setValue('250')

    expect(wrapper.get('#fixed-cost-buffer').element).toHaveProperty('value', '100')
    expect(wrapper.get('[aria-label="Miscellaneous buffer amount"]').text()).toBe('1450')
  })

  it('warns that a new setup replaces the saved plan', async () => {
    const wrapper = mountSetup({ replacing: true })
    await flushPromises()

    expect(wrapper.text()).toContain('Saving replaces your current plan.')
  })

  it('shows your spending beside the plan, or in its place where there’s no room for both', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.findComponent({ name: 'SpendingExplorer' }).exists()).toBe(true)
    const toggle = wrapper.get('button.spending-toggle')
    expect(toggle.text()).toBe('See your spending')

    await toggle.trigger('click')

    expect(wrapper.get('.plan-setup').classes()).toContain('showing-spending')
    expect(toggle.text()).toBe('Back to your plan')
  })

  it('edits a saved plan without recurring estimates', async () => {
    const wrapper = mountSetup({ saved: savedPlan })
    await flushPromises()

    expect(getRecurringTransactions).not.toHaveBeenCalled()
    expect(wrapper.find('[aria-label="Account"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('filled in for you')
    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '5200')
    expect(wrapper.get('#fixed-cost-buffer').element).toHaveProperty('value', '10')
    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').element).toHaveProperty(
      'value',
      '1500',
    )
    expect(wrapper.get('[aria-label="Subscriptions amount"]').text()).toBe('15.49')

    await wrapper.get('[aria-label="Rent/mortgage amount"]').setValue('1550')
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    const request = vi.mocked(saveSpendingPlan).mock.calls[0]?.[0]
    expect(request?.account_id).toBeNull()
    expect(request?.lines.find((line) => line.name === 'Rent/mortgage')?.amount).toBe(1550)
    expect(request?.lines.find((line) => line.name === 'Subscriptions')?.items).toEqual([
      { name: 'Netflix', amount: 15.49, stream_id: 'netflix' },
    ])
  })
})
