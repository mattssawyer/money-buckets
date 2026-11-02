import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import RecurringDialog from '../RecurringDialog.vue'
import SpendingPlanSetup, { EVALUATE_AFTER_MS } from '../SpendingPlanSetup.vue'
import type { RecurringCandidate, RecurringStream } from '../../api/PlaidService'

import {
  evaluateSpendingPlan,
  getLineDefaults,
  getPlanSuggestions,
  saveSpendingPlan,
  type PlanEvaluationResponse,
  type SpendingPlanRequest,
} from '../../api/SpendingPlanService'
import { defaultPlan } from '../../spendingPlan/plan'
import { fromEvaluation, toSaveRequest, type SavedPlan } from '../../spendingPlan/savedPlan'
import { fakeEvaluation } from './fakePlanEvaluation'
import { fakeSuggestions } from './fakePlanSuggestions'

// The recurring dialog is stubbed; nothing here talks to Plaid's endpoints.
vi.mock('../../api/PlaidService', () => ({}))

vi.mock('../../api/SpendingPlanService', () => ({
  saveSpendingPlan: vi.fn(),
  evaluateSpendingPlan: vi.fn<(plan: SpendingPlanRequest) => Promise<PlanEvaluationResponse>>(),
  getPlanSuggestions: vi.fn(),
  getLineDefaults: vi.fn(),
}))

// What the server works its suggestions out from.
const recurringStreams = vi.fn<() => Promise<RecurringStream[]>>()
const recurringCandidates = vi.fn<() => Promise<RecurringCandidate[]>>()

enableAutoUnmount(afterEach)
afterEach(() => {
  vi.useRealTimers()
})

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
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  vi.resetAllMocks()
  localStorage.clear()
  document.body.innerHTML = ''
  recurringStreams.mockResolvedValue([paycheck, rent])
  recurringCandidates.mockResolvedValue([])
  vi.mocked(getPlanSuggestions).mockImplementation(async () =>
    fakeSuggestions(await recurringStreams(), await recurringCandidates()),
  )
  vi.mocked(getLineDefaults).mockResolvedValue(new Map())
  vi.mocked(saveSpendingPlan).mockImplementation(async (request: SpendingPlanRequest) => ({
    ...request,
    updated_at: '2026-09-22T19:30:00Z',
    evaluation: fakeEvaluation(request),
  }))
  vi.mocked(evaluateSpendingPlan).mockImplementation(async (request) => fakeEvaluation(request))
})

/** Lets the editor's pause after typing pass, and the server's answer arrive. */
async function settle() {
  await vi.advanceTimersByTimeAsync(EVALUATE_AFTER_MS)
  await flushPromises()
}

function saveButton(wrapper: ReturnType<typeof mountSetup>) {
  const button = wrapper.findAll('button').find((element) => element.text() === 'Save plan')
  if (!button) throw new Error('Save button not found')
  return button
}

const savedPlanDraft = {
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
}

const savedPlan: SavedPlan = {
  accountId: 'checking',
  takeHome: 5200,
  grossPay: null,
  bufferPercent: 10,
  updatedAt: '2026-09-20T12:00:00Z',
  plan: savedPlanDraft,
  evaluation: fromEvaluation(
    fakeEvaluation(toSaveRequest('checking', 5200, null, 10, savedPlanDraft)),
  ),
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

/** Three months of groceries averaging $600 a month. */
const groceriesDefaults = new Map([['Groceries', 600]])

function spendOnGroceries() {
  vi.mocked(getLineDefaults).mockResolvedValue(groceriesDefaults)
}

describe('spending plan setup', () => {
  it('defaults a line with no recurring bill to what you usually spend on it', async () => {
    spendOnGroceries()
    const wrapper = mountSetup()
    await settle()

    const amount = wrapper.get<HTMLInputElement>('input[aria-label="Groceries amount"]')
    expect(amount.element.value).toBe('')
    expect(amount.attributes('placeholder')).toBe('600')
    // 1,450 rent + 600 groceries, plus the 15% buffer.
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toContain('$2,357.50')

    await amount.setValue('500')
    await settle()
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toContain('$2,242.50')

    await amount.setValue('')
    await settle()
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toContain('$2,357.50')

    await saveButton(wrapper).trigger('click')
    await settle()
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
    let answer: (defaults: Map<string, number>) => void = () => {}
    vi.mocked(getLineDefaults).mockReturnValue(new Promise((resolve) => (answer = resolve)))
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

    answer(groceriesDefaults)
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
    vi.mocked(getLineDefaults).mockRejectedValue(new Error('offline'))
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

    expect(getPlanSuggestions).not.toHaveBeenCalled()
    expect(wrapper.get('input[aria-label="Groceries amount"]').attributes('placeholder')).toBe(
      '600',
    )
    const rent = wrapper.get<HTMLInputElement>('input[aria-label="Rent/mortgage amount"]')
    expect(rent.element.value).toBe('1500')
  })

  it('fills in a bill on the line Jev put it on, whatever Plaid calls it', async () => {
    recurringStreams.mockResolvedValue([
      paycheck,
      { ...sterlingRent, plan_bucket: 'FIXED_COSTS', plan_line: 'Rent/mortgage' },
    ])
    const wrapper = mountSetup()
    await flushPromises()

    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').text()).toBe('1554.91')
    expect(wrapper.find('[aria-labelledby="unplaced-heading"]').exists()).toBe(false)
  })

  it('keeps bills on no line folded behind a note until the user asks to place them', async () => {
    recurringStreams.mockResolvedValue([paycheck, sterlingRent])
    const wrapper = mountSetup()
    await flushPromises()

    const list = wrapper.get('[aria-labelledby="unplaced-heading"]')
    expect(list.isVisible()).toBe(false)
    const toggle = wrapper.findAll('.note-action').find((button) => button.text() === 'Place')!
    expect(toggle.element.closest('p')!.textContent).toContain(
      '1 recurring payment isn’t on a line',
    )

    await toggle.trigger('click')

    expect(list.isVisible()).toBe(true)
    expect(toggle.attributes('aria-expanded')).toBe('true')
  })

  it('lists bills on no line and puts one on the line the user picks', async () => {
    recurringStreams.mockResolvedValue([paycheck, sterlingRent])
    const wrapper = mountSetup()
    await flushPromises()

    const list = wrapper.get('[aria-labelledby="unplaced-heading"]')
    expect(list.text()).toContain('Sterling Group')
    expect(list.text()).toContain('$1,554.91/mo')

    const select = wrapper.get('[aria-label="Line for Sterling Group"]')
    const rentRow = select.findAll('option').find((option) => option.text() === 'Rent/mortgage')
    await select.setValue(rentRow!.attributes('value'))

    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').text()).toBe('1554.91')
    expect(wrapper.find('[aria-labelledby="unplaced-heading"]').exists()).toBe(false)
  })

  it('lets the user skip a bill that belongs on no line', async () => {
    recurringStreams.mockResolvedValue([paycheck, sterlingRent])
    const wrapper = mountSetup()
    await flushPromises()

    await wrapper.get('[aria-label="Skip Sterling Group"]').trigger('click')

    expect(wrapper.find('[aria-labelledby="unplaced-heading"]').exists()).toBe(false)
    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').element).toHaveProperty('value', '')
  })

  it('estimates from every tracked account', async () => {
    const wrapper = mountSetup()
    await settle()

    expect(getPlanSuggestions).toHaveBeenCalledWith(true)
    expect(wrapper.find('[aria-label="Account"]').exists()).toBe(false)
    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '5200')
    expect(wrapper.get('[aria-label="Rent/mortgage amount"]').text()).toBe('1450')
    expect(wrapper.get('[aria-label="Utilities amount"]').element).toHaveProperty('value', '')
    expect(wrapper.find('[aria-label="Landlord name"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Should be 50–60% of income')
    expect(wrapper.text()).toContain('32%')
  })

  it("counts a shared account's rent at the user's share", async () => {
    recurringStreams.mockResolvedValue([
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
    recurringStreams.mockResolvedValue([rent])
    recurringCandidates.mockResolvedValue([
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
    const note = wrapper.findAll('.plan-note').find((row) => row.text().includes('recurring'))!
    expect(note.text().replace(/\s+/g, ' ')).toBe('1 payment might be recurring Review')

    await note.get('button').trigger('click')
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
    recurringStreams.mockResolvedValue([paycheck, rent, gym])
    recurringCandidates.mockResolvedValue([netflix])
    const wrapper = mountSetup()
    await flushPromises()
    await wrapper.get('#take-home-income').setValue('5000')
    await wrapper.get('[aria-label="Groceries amount"]').setValue('500')
    expect(wrapper.get('[aria-label="Utilities amount"]').text()).toBe('40')

    // In the dialog, the user confirms Netflix and says the gym doesn't repeat.
    recurringStreams.mockResolvedValue([paycheck, rent])
    recurringCandidates.mockResolvedValue([{ ...netflix, status: 'CONFIRMED' }])
    await wrapper.get('.recurring-button').trigger('click')
    wrapper.getComponent(RecurringDialog).vm.$emit('changed')
    await flushPromises()

    expect(wrapper.get('[aria-label="Subscriptions amount"]').text()).toBe('15.49')
    expect(wrapper.get('[aria-label="Utilities amount"]').element).toHaveProperty('value', '')
    expect(wrapper.get('[aria-label="Groceries amount"]').element).toHaveProperty('value', '500')
    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '5000')
    expect(wrapper.findAll('.plan-note')).toHaveLength(0)
  })

  it('leaves a bill the user skipped off the plan after recurring answers', async () => {
    recurringStreams.mockResolvedValue([paycheck, rent, sterlingRent])
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

  it('lists a newly recurring bill to place once, even if answers overlap', async () => {
    const wrapper = mountSetup()
    await flushPromises()

    recurringStreams.mockResolvedValue([paycheck, rent, sterlingRent])
    await wrapper.get('.recurring-button').trigger('click')
    const dialog = wrapper.getComponent(RecurringDialog)
    dialog.vm.$emit('changed')
    dialog.vm.$emit('changed')
    await flushPromises()

    const list = wrapper.get('[aria-labelledby="unplaced-heading"]')
    expect(list.findAll('.unplaced-row')).toHaveLength(1)
  })

  it('adds a payment confirmed while editing a saved plan to its line', async () => {
    const wrapper = mountSetup({ saved: savedPlan })
    await flushPromises()
    await wrapper.get('.recurring-button').trigger('click')
    expect(getPlanSuggestions).toHaveBeenCalledWith(false)
    expect(getPlanSuggestions).toHaveBeenCalledTimes(1)

    recurringStreams.mockResolvedValue([
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

  it('lets you edit, add, and remove fixed cost rows', async () => {
    const wrapper = mountSetup()
    await settle()

    await wrapper.get('[aria-label="Utilities name"]').setValue('Power')
    await settle()
    await wrapper.get('[aria-label="Power amount"]').setValue('370')
    await settle()
    expect(wrapper.text()).toContain('40%')

    await wrapper.get('button[aria-label="Remove Power"]').trigger('click')
    await settle()
    expect(wrapper.find('[aria-label="Power name"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('32%')

    await wrapper.get('button.add-cost').trigger('click')
    await settle()
    expect(wrapper.find('[aria-label="Cost name"]').exists()).toBe(true)
  })

  it('shows the recurring bills behind a line and lets you edit them', async () => {
    recurringStreams.mockResolvedValue([
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
    recurringStreams.mockResolvedValue([])
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
    recurringStreams.mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountSetup()
    await settle()

    expect(wrapper.get('#take-home-income').element).toHaveProperty('value', '')
    expect(wrapper.find('[aria-label="Rent/mortgage name"]').exists()).toBe(true)
    await wrapper.get('#take-home-income').setValue('4000')
    await settle()
    await wrapper.get('[aria-label="Rent/mortgage amount"]').setValue('1600')
    await settle()
    expect(wrapper.text()).toContain('46%')
  })

  it('lists the spreadsheet investment and savings lines, filled from transfers', async () => {
    recurringStreams.mockResolvedValue([
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
    await settle()

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
    await settle()

    await wrapper
      .findAll('button.add-line')
      .find((button) => button.text() === 'Add a savings goal')
      ?.trigger('click')
    await settle()
    await wrapper.get('[aria-label="Savings goal name"]').setValue('Wedding')
    await settle()
    await wrapper.get('[aria-label="Wedding amount"]').setValue('300')
    await settle()
    expect(wrapper.get('[aria-label="Savings total"]').text()).toContain('$300')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toContain('$1,667.50')

    await wrapper.get('button[aria-label="Remove 401(k)"]').trigger('click')
    await settle()
    expect(wrapper.find('[aria-label="401(k) name"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Roth IRA name"]').exists()).toBe(true)
  })

  it('shows what is left for guilt-free spending', async () => {
    const wrapper = mountSetup()
    await settle()

    expect(wrapper.text()).toContain('Should be 20–35% of income')
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$3,532.5068%')

    await wrapper.get('[aria-label="Roth IRA amount"]').setValue('520')
    await settle()
    await wrapper.get('[aria-label="Vacations amount"]').setValue('260')
    await settle()
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$2,752.5053%')
    expect(wrapper.find('.guilt-free-warning').exists()).toBe(false)
  })

  it('warns when the plan adds up to more than take-home pay', async () => {
    const wrapper = mountSetup()
    await settle()

    await wrapper.get('#take-home-income').setValue('1500')
    await settle()
    await wrapper.get('[aria-label="Roth IRA amount"]').setValue('250')
    await settle()

    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('-$417.50-28%')
    expect(wrapper.get('.guilt-free-warning').text()).toBe(
      'Your plan is $417.50 more than your take-home pay.',
    )
  })

  it('marks fixed costs over their target', async () => {
    const wrapper = mountSetup()
    await settle()
    const fixedCostsShare = () => wrapper.get('[aria-label="Fixed costs total"] .total-share')

    expect(fixedCostsShare().classes()).not.toContain('total-share-over')

    await wrapper.get('#take-home-income').setValue('1500')
    await settle()

    expect(fixedCostsShare().classes()).toContain('total-share-over')
  })

  it('adds a paycheck 401(k) back to income instead of subtracting it twice', async () => {
    const wrapper = mountSetup()
    await settle()

    expect(wrapper.get('[aria-label="401(k): from paycheck"]').element).toHaveProperty(
      'checked',
      true,
    )
    expect(wrapper.get('[aria-label="Roth IRA: from paycheck"]').element).toHaveProperty(
      'checked',
      false,
    )

    await wrapper.get('[aria-label="401(k) amount"]').setValue('600')
    await settle()

    // Percentages are of take-home pay plus the 401(k): 5,800.
    expect(wrapper.get('[aria-label="Investments total"]').text()).toBe('$60010%')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toBe('$1,667.5029%')
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$3,532.5061%')

    await wrapper.get('[aria-label="401(k): from paycheck"]').setValue(false)
    await settle()

    expect(wrapper.get('[aria-label="Investments total"]').text()).toBe('$60012%')
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$2,932.5056%')
  })

  it('sets a paycheck 401(k) as a percent of gross pay', async () => {
    const wrapper = mountSetup()
    await settle()
    const income = wrapper.get('[aria-labelledby="plan-section-heading-income"]')
    expect(income.findAll('label').map((label) => label.text())).toEqual([
      'Gross pay',
      'Take-home pay',
    ])
    expect(income.findAll('.sheet-row')).toHaveLength(2)
    await wrapper.get('[aria-label="401(k) amount"]').setValue('600')
    await settle()

    const percent = wrapper.get('[aria-label="401(k): set in dollars or percent"]')
    await wrapper.get('#gross-pay-income').setValue('8000')
    await settle()
    await percent.get('[aria-label="Percent of gross pay"]').trigger('click')
    await settle()
    // The amount carries across: 600 of 8,000.
    expect(
      wrapper.get<HTMLInputElement>('[aria-label="401(k) percent of gross pay"]').element.value,
    ).toBe('7.5')

    await wrapper.get('[aria-label="401(k) percent of gross pay"]').setValue('6')
    await settle()

    expect(wrapper.get('[aria-label="401(k) amount"]').text()).toBe('480')
    // 480 of take-home pay plus the 401(k), 5,680.
    expect(wrapper.get('[aria-label="Investments total"]').text()).toBe('$4808%')

    await saveButton(wrapper).trigger('click')
    await settle()
    const request = vi.mocked(saveSpendingPlan).mock.calls[0]?.[0]
    expect(request?.gross_pay).toBe(8000)
    expect(request?.lines.find((line) => line.name === '401(k)')).toMatchObject({
      amount: 480,
      percent_of_gross: 6,
    })
    expect(request?.lines.find((line) => line.name === 'Roth IRA')?.percent_of_gross).toBeNull()
  })

  it('needs gross pay before a line can be a percent of it', async () => {
    const wrapper = mountSetup()
    await flushPromises()
    await wrapper.get('[aria-label="401(k) amount"]').setValue('600')
    const percent = wrapper.get('[aria-label="401(k): set in dollars or percent"]')
    const toPercent = percent.get('[aria-label="Percent of gross pay"]')

    expect(toPercent.attributes('disabled')).toBeDefined()
    expect(percent.attributes('title')).toBe('Enter your gross pay to use a percent')
    await toPercent.trigger('click')
    expect(wrapper.find('[aria-label="401(k) percent of gross pay"]').exists()).toBe(false)

    await wrapper.get('#gross-pay-income').setValue('8000')
    expect(toPercent.attributes('disabled')).toBeUndefined()
    await toPercent.trigger('click')

    // Clearing gross pay afterwards locks the percent until it's filled in again.
    await wrapper.get('#gross-pay-income').setValue('')
    const field = wrapper.get('[aria-label="401(k) percent of gross pay"]')
    expect(field.attributes('disabled')).toBeDefined()
    expect(percent.get('[aria-label="Dollars"]').attributes('disabled')).toBeUndefined()
  })

  it('keeps a percent line’s amount when gross pay is cleared, and saves it in dollars', async () => {
    const wrapper = mountSetup()
    await flushPromises()
    await wrapper.get('[aria-label="401(k) amount"]').setValue('600')
    await wrapper.get('#gross-pay-income').setValue('8000')
    const percent = wrapper.get('[aria-label="401(k): set in dollars or percent"]')
    await percent.get('[aria-label="Percent of gross pay"]').trigger('click')
    await wrapper.get('[aria-label="401(k) percent of gross pay"]').setValue('6')

    await wrapper.get('#gross-pay-income').setValue('')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(
      vi.mocked(saveSpendingPlan).mock.calls[0]?.[0].lines.find((line) => line.name === '401(k)'),
    ).toMatchObject({ amount: 480, percent_of_gross: null })

    await percent.get('[aria-label="Dollars"]').trigger('click')
    expect(wrapper.get<HTMLInputElement>('[aria-label="401(k) amount"]').element.value).toBe('480')
  })

  it('retains entered percentage precision', async () => {
    const wrapper = mountSetup()
    await flushPromises()
    await wrapper.get('#gross-pay-income').setValue('8000')
    const percent = wrapper.get('[aria-label="401(k): set in dollars or percent"]')
    await percent.get('[aria-label="Percent of gross pay"]').trigger('click')

    await wrapper.get('[aria-label="401(k) percent of gross pay"]').setValue('6.125')

    // 6.125% of 8,000, without rounding the percentage.
    expect(wrapper.get('[aria-label="401(k) amount"]').text()).toBe('490')
  })

  it.each(['Dollars', 'From paycheck'])(
    'keeps the last calculated amount after clearing gross pay and leaving percent via %s',
    async (exit) => {
      const wrapper = mountSetup()
      await flushPromises()
      await wrapper.get('#gross-pay-income').setValue('8000')
      await wrapper.get('[aria-label="Percent of gross pay"]').trigger('click')
      await wrapper.get('[aria-label="401(k) percent of gross pay"]').setValue('6.125')
      await wrapper.get('#gross-pay-income').setValue('9000')
      await wrapper.get('#gross-pay-income').setValue('')
      if (exit === 'Dollars') await wrapper.get('[aria-label="Dollars"]').trigger('click')
      else await wrapper.get('[aria-label="401(k): from paycheck"]').setValue(false)
      expect(wrapper.get<HTMLInputElement>('[aria-label="401(k) amount"]').element.value).toBe(
        '551.25',
      )
    },
  )

  it('preserves cents through a repeating equivalent percentage and saving', async () => {
    const wrapper = mountSetup()
    await flushPromises()
    await wrapper.get('[aria-label="401(k) amount"]').setValue('123.45')
    await wrapper.get('#gross-pay-income').setValue('7000')
    await wrapper.get('[aria-label="Percent of gross pay"]').trigger('click')
    expect(wrapper.get('[aria-label="401(k) amount"]').text()).toBe('123.45')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    const line = vi
      .mocked(saveSpendingPlan)
      .mock.calls[0]?.[0].lines.find((line) => line.name === '401(k)')
    expect(line?.amount).toBe(123.45)
    expect(line?.percent_of_gross).toBeCloseTo(1.7635714285714286, 14)
    await wrapper.get('[aria-label="Dollars"]').trigger('click')
    expect(wrapper.get<HTMLInputElement>('[aria-label="401(k) amount"]').element.value).toBe(
      '123.45',
    )
  })

  it.each(['0.001', '8001'])(
    'keeps dollars when %s cannot round-trip through an eligible percentage',
    async (amount) => {
      const wrapper = mountSetup()
      await flushPromises()
      await wrapper.get('[aria-label="401(k) amount"]').setValue(amount)
      await wrapper.get('#gross-pay-income').setValue('8000')
      await wrapper.get('[aria-label="Percent of gross pay"]').trigger('click')
      expect(wrapper.get<HTMLInputElement>('[aria-label="401(k) amount"]').element.value).toBe(
        amount,
      )
      expect(wrapper.find('[aria-label="401(k) percent of gross pay"]').exists()).toBe(false)
    },
  )

  it('keeps a paycheck line’s amount when it switches between dollars and percent', async () => {
    const wrapper = mountSetup()
    await flushPromises()
    await wrapper.get('[aria-label="401(k) amount"]').setValue('600')
    const percent = wrapper.get('[aria-label="401(k): set in dollars or percent"]')
    await wrapper.get('#gross-pay-income').setValue('8000')
    await percent.get('[aria-label="Percent of gross pay"]').trigger('click')
    await wrapper.get('[aria-label="401(k) percent of gross pay"]').setValue('6')

    await percent.get('[aria-label="Dollars"]').trigger('click')
    expect(wrapper.get<HTMLInputElement>('[aria-label="401(k) amount"]').element.value).toBe('480')

    await percent.get('[aria-label="Percent of gross pay"]').trigger('click')
    expect(
      wrapper.get<HTMLInputElement>('[aria-label="401(k) percent of gross pay"]').element.value,
    ).toBe('6')

    // A line that isn't taken from the paycheck can't be a percent of it.
    await wrapper.get('[aria-label="401(k): from paycheck"]').setValue(false)
    expect(wrapper.find('[aria-label="401(k): set in dollars or percent"]').exists()).toBe(false)
    expect(wrapper.get<HTMLInputElement>('[aria-label="401(k) amount"]').element.value).toBe('480')
    expect(wrapper.find('[aria-label="Roth IRA: set in dollars or percent"]').exists()).toBe(false)
  })

  it('opens a saved percent line as a percent', async () => {
    const wrapper = mountSetup({
      saved: {
        ...savedPlan,
        grossPay: 9000,
        plan: {
          ...savedPlan.plan,
          investments: [
            { name: '401(k)', amount: 450, items: [], fromPaycheck: true, percentOfGross: 5 },
          ],
        },
      },
    })
    await flushPromises()

    expect(wrapper.get<HTMLInputElement>('#gross-pay-income').element.value).toBe('9000')
    expect(
      wrapper.get<HTMLInputElement>('[aria-label="401(k) percent of gross pay"]').element.value,
    ).toBe('5')
    expect(wrapper.get('[aria-label="401(k) amount"]').text()).toBe('450')
  })

  it('asks for take-home pay before showing guilt-free spending', async () => {
    recurringStreams.mockResolvedValue([])
    const wrapper = mountSetup()
    await settle()

    expect(wrapper.find('[aria-label="Guilt-free spending total"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Enter your take-home pay to see what’s left to spend.')

    await wrapper.get('#take-home-income').setValue('4000')
    await settle()
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
    await settle()

    expect(wrapper.get('#fixed-cost-buffer').element).toHaveProperty('value', '15')
    expect(wrapper.get('[aria-label="Miscellaneous buffer amount"]').text()).toBe('217.5')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toBe('$1,667.5032%')

    await wrapper.get('#fixed-cost-buffer').setValue('10')
    await settle()
    expect(wrapper.get('[aria-label="Miscellaneous buffer amount"]').text()).toBe('145')
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toBe('$1,59531%')

    await wrapper.get('#fixed-cost-buffer').setValue('')
    await settle()
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toBe('$1,45028%')

    await saveButton(wrapper).trigger('click')
    await settle()
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
    await settle()

    await wrapper.get('#fixed-cost-buffer').setValue('250')
    await settle()

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

    expect(getPlanSuggestions).not.toHaveBeenCalled()
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

describe('spending plan totals from the server', () => {
  it('waits until typing stops, then asks once for the latest plan', async () => {
    const wrapper = mountSetup()
    await settle()
    vi.mocked(evaluateSpendingPlan).mockClear()

    await wrapper.get('#take-home-income').setValue('4')
    await wrapper.get('#take-home-income').setValue('40')
    await wrapper.get('#take-home-income').setValue('400')
    await vi.advanceTimersByTimeAsync(300)
    expect(evaluateSpendingPlan).not.toHaveBeenCalled()

    await wrapper.get('#take-home-income').setValue('4000')
    await settle()

    expect(
      vi.mocked(evaluateSpendingPlan).mock.calls.map(([request]) => request.take_home),
    ).toEqual([4000])
  })

  it('shows a saved plan with the totals it was loaded with', () => {
    const wrapper = mountSetup({ saved: savedPlan })

    // 1,500 rent + 15.49 Netflix, plus the 10% buffer, before anything is asked again.
    expect(wrapper.get('[aria-label="Fixed costs total"]').text()).toContain('$1,667.04')
  })

  it("sends what's typed and shows the server's answer", async () => {
    const wrapper = mountSetup()
    await settle()
    vi.mocked(evaluateSpendingPlan).mockImplementation(async (request) => ({
      ...fakeEvaluation(request),
      guilt_free: {
        amount: 1234.5,
        share: 31,
        target: { min: 20, max: 35 },
        status: 'within',
        flagged: false,
      },
    }))

    await wrapper.get('#take-home-income').setValue('4000')
    await settle()

    expect(vi.mocked(evaluateSpendingPlan).mock.lastCall?.[0].take_home).toBe(4000)
    expect(vi.mocked(evaluateSpendingPlan).mock.lastCall?.[0].fixed_cost_buffer_percent).toBe(15)
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$1,234.5031%')
  })

  it('keeps the last totals on screen while the server works out new ones', async () => {
    const wrapper = mountSetup()
    await settle()
    const before = wrapper.get('[aria-label="Guilt-free spending total"]').text()
    vi.mocked(evaluateSpendingPlan).mockReturnValue(new Promise(() => {}))

    await wrapper.get('#take-home-income').setValue('4000')
    await settle()

    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe(before)
  })

  it('says when the totals could not be updated, and tries again', async () => {
    const wrapper = mountSetup()
    await settle()
    vi.mocked(evaluateSpendingPlan).mockRejectedValueOnce(new Error('offline'))

    await wrapper.get('#take-home-income').setValue('4000')
    await settle()

    expect(wrapper.get('.evaluation-error').text()).toContain('We couldn’t update your totals.')
    const retry = wrapper.get('.evaluation-error').findAll('button')
    await retry.find((button) => button.text() === 'Try again')!.trigger('click')
    await settle()

    expect(wrapper.find('.evaluation-error').exists()).toBe(false)
    // 4,000 less 1,450 rent with the 15% buffer.
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$2,332.5058%')
  })

  it('asks again with the latest plan once an answer that was already out comes back', async () => {
    const wrapper = mountSetup()
    await settle()
    vi.mocked(evaluateSpendingPlan).mockClear()
    let answerFirst: () => void = () => {}
    vi.mocked(evaluateSpendingPlan).mockImplementationOnce(
      (request) =>
        new Promise((resolve) => {
          answerFirst = () => resolve(fakeEvaluation(request))
        }),
    )

    await wrapper.get('#take-home-income').setValue('4000')
    await settle()
    await wrapper.get('#take-home-income').setValue('5000')
    await settle()
    answerFirst()
    await settle()

    // One request at a time: the second goes out with the latest take-home once the first answers.
    expect(
      vi.mocked(evaluateSpendingPlan).mock.calls.map(([request]) => request.take_home),
    ).toEqual([4000, 5000])
    // 5,000 less 1,450 rent with the 15% buffer.
    expect(wrapper.get('[aria-label="Guilt-free spending total"]').text()).toBe('$3,332.5067%')
  })
})
