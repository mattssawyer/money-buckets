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
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(0)
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

  it('opens a category to show its payees', async () => {
    const wrapper = mountExplorer()
    await flushPromises()

    await wrapper.get('button[aria-label="Show Groceries payees"]').trigger('click')
    expect(wrapper.get('[aria-label="Groceries payees"]').text()).toBe(
      'Whole Foods$180Trader Joe’s$30',
    )
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

  it('looks further back when asked', async () => {
    const wrapper = mountExplorer()
    await flushPromises()

    await wrapper.get('select').setValue('12')
    await flushPromises()

    expect(getSpendingByBucket).toHaveBeenLastCalledWith(undefined, {
      start: '2025-10-01',
      end: '2026-09-30',
    })
  })

  it('shows the latest period’s spending when an earlier answer arrives late', async () => {
    let answerFirst: (spending: SpendingByBucket) => void = () => {}
    vi.mocked(getSpendingByBucket)
      .mockReturnValueOnce(new Promise((resolve) => (answerFirst = resolve)))
      .mockResolvedValueOnce({ ...spending, total: 0, buckets: [] })
    const wrapper = mountExplorer()

    await wrapper.get('select').setValue('12')
    await flushPromises()
    expect(wrapper.text()).toContain('No spending in your tracked accounts over these months yet.')

    answerFirst(spending)
    await flushPromises()

    expect(wrapper.text()).toContain('No spending in your tracked accounts over these months yet.')
    expect(wrapper.find('section.group').exists()).toBe(false)
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

    expect(wrapper.get('section.group h3').text()).toBe('Fixed costs$230')
  })
})
