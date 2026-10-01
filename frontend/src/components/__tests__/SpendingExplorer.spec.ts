import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import SpendingExplorer from '../SpendingExplorer.vue'
import {
  getSpendingByBucket,
  type PlaidTransaction,
  type SpendingByBucket,
} from '../../api/PlaidService'

vi.mock('../../api/PlaidService', () => ({
  getSpendingByBucket: vi.fn(),
}))

enableAutoUnmount(afterEach)

function spent(
  id: string,
  merchant: string,
  amount: number,
  date: string,
  line: string | null,
  detailed = 'FOOD_AND_DRINK_GROCERIES',
): PlaidTransaction {
  return {
    transaction_id: id,
    account_id: 'checking',
    amount,
    iso_currency_code: 'USD',
    date,
    name: merchant.toUpperCase(),
    merchant_name: merchant,
    logo_url: null,
    pending: false,
    category: 'FOOD_AND_DRINK',
    category_detailed: detailed,
    bucket: 'FIXED_COSTS',
    plan_line: line,
    share_percent: 100,
    payee_key: merchant.toLowerCase(),
    payee_kind: 'BILL',
    bucket_corrected: false,
    category_corrected: false,
    recurring: null,
  }
}

const spending: SpendingByBucket = {
  start: '2026-07-01',
  end: '2026-09-30',
  total: 750,
  buckets: [
    {
      bucket: 'FIXED_COSTS',
      amount: 690,
      categories: [
        {
          category: 'FOOD_AND_DRINK',
          amount: 690,
          transactions: [
            spent('wf-1', 'Whole Foods', 300, '2026-07-04', 'Groceries'),
            spent('wf-2', 'Whole Foods', 240, '2026-08-11', 'Groceries'),
            spent('tj-1', 'Trader Joe’s', 90, '2026-09-20', 'Groceries'),
            spent('cvs-1', 'CVS', 60, '2026-09-21', null, 'MEDICAL_PHARMACIES_AND_SUPPLEMENTS'),
          ],
        },
      ],
    },
    {
      bucket: 'GUILT_FREE',
      amount: 60,
      categories: [
        {
          category: 'FOOD_AND_DRINK',
          amount: 60,
          transactions: [
            spent('dig-1', 'Dig Inn', 60, '2026-09-02', null, 'FOOD_AND_DRINK_RESTAURANT'),
          ],
        },
      ],
    },
  ],
}

function mountExplorer() {
  return mount(SpendingExplorer, {
    global: { plugins: [[PrimeVue, { unstyled: true }]] },
    attachTo: document.body,
  })
}

function checkbox(wrapper: ReturnType<typeof mountExplorer>, name: string) {
  const label = wrapper.findAll('label.pick').find((element) => element.text() === name)
  if (!label) throw new Error(`No checkbox for ${name}`)
  return label.get('input')
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 9, 1))
  document.body.innerHTML = ''
  vi.mocked(getSpendingByBucket).mockResolvedValue(spending)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('spending explorer', () => {
  it('averages the last three whole months of spending in every tracked account', async () => {
    const wrapper = mountExplorer()
    await flushPromises()

    expect(getSpendingByBucket).toHaveBeenCalledWith(undefined, {
      start: '2026-07-01',
      end: '2026-09-30',
    })
    const summary = wrapper.get('.summary')
    expect(summary.get('.summary-title').text()).toBe('All spending')
    expect(summary.get('.summary-average').text()).toBe('$250 a month on average')
    expect(summary.findAll('li').map((month) => month.text())).toEqual([
      'Jul$300',
      'Aug$240',
      'Sep$210',
    ])
  })

  it('lists each bucket’s plan lines, then its spending on no line by category', async () => {
    const wrapper = mountExplorer()
    await flushPromises()

    const [fixedCosts, guiltFree] = wrapper.findAll('section.group')
    expect(fixedCosts?.get('h3').text()).toBe('Fixed costs$230')
    expect(
      fixedCosts?.findAll('.part-heading, .categories > li > .pick-row').map((row) => row.text()),
    ).toEqual(['Groceries$210', 'On no line', 'Medical pharmacies and supplements$20'])
    // Guilt-free spending is what's left after the plan's lines, so it's one total.
    expect(guiltFree?.get('h3').text()).toBe('Guilt-free spending$20')
    expect(
      guiltFree?.findAll('.part-heading, .categories > li > .pick-row').map((row) => row.text()),
    ).toEqual(['All guilt-free spending$20'])
  })

  it('adds up the categories you pick', async () => {
    const wrapper = mountExplorer()
    await flushPromises()

    await checkbox(wrapper, 'Groceries').setValue(true)

    expect(wrapper.get('.summary-title').text()).toBe('Groceries')
    expect(wrapper.get('.summary-average').text()).toBe('$210 a month on average')

    await checkbox(wrapper, 'All guilt-free spending').setValue(true)
    expect(wrapper.get('.summary-average').text()).toBe('$230 a month on average')

    await wrapper.get('.summary button').trigger('click')
    expect(wrapper.get('.summary-title').text()).toBe('All spending')
    expect(checkbox(wrapper, 'Groceries').element.checked).toBe(false)
  })

  it('opens a category to show and pick its payees', async () => {
    const wrapper = mountExplorer()
    await flushPromises()

    await wrapper.get('button[aria-label="Show Groceries payees"]').trigger('click')
    expect(wrapper.get('[aria-label="Groceries payees"]').text()).toBe(
      'Whole Foods$180Trader Joe’s$30',
    )

    await checkbox(wrapper, 'Whole Foods').setValue(true)

    expect(wrapper.get('.summary-title').text()).toBe('Whole Foods')
    expect(wrapper.get('.summary-average').text()).toBe('$180 a month on average')
    expect(wrapper.findAll('.summary li').map((month) => month.text())).toEqual([
      'Jul$300',
      'Aug$240',
      'Sep$0',
    ])
  })

  it('finds payees by name', async () => {
    const wrapper = mountExplorer()
    await flushPromises()

    await wrapper.get('input[type="search"]').setValue('trader')

    expect(wrapper.findAll('.pick-name').map((name) => name.text())).toEqual([
      'Groceries',
      'Trader Joe’s',
    ])

    await wrapper.get('input[type="search"]').setValue('zzz')
    expect(wrapper.text()).toContain('Nothing matches “zzz”.')
  })

  it('looks further back when asked, starting the picks over', async () => {
    const wrapper = mountExplorer()
    await flushPromises()
    await checkbox(wrapper, 'Groceries').setValue(true)

    await wrapper.get('select').setValue('12')
    await flushPromises()

    expect(getSpendingByBucket).toHaveBeenLastCalledWith(undefined, {
      start: '2025-10-01',
      end: '2026-09-30',
    })
    expect(wrapper.get('.summary-title').text()).toBe('All spending')
  })

  it('says so when there’s no spending yet', async () => {
    vi.mocked(getSpendingByBucket).mockResolvedValue({ ...spending, total: 0, buckets: [] })
    const wrapper = mountExplorer()
    await flushPromises()

    expect(wrapper.text()).toContain('No spending in your tracked accounts over these months yet.')
  })

  it('offers to try again when spending can’t be loaded', async () => {
    vi.mocked(getSpendingByBucket).mockRejectedValueOnce(new Error('offline'))
    const wrapper = mountExplorer()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('We couldn’t load your spending.')

    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.summary-average').text()).toBe('$250 a month on average')
  })
})
