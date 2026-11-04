import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import SpendingExplorer from '../SpendingExplorer.vue'
import { getSpendingHistory, type SpendingHistoryResponse } from '../../api/SpendingPlanService'

vi.mock('../../api/SpendingPlanService', () => ({
  getSpendingHistory: vi.fn<() => void>(),
}))

enableAutoUnmount(afterEach)

/** Three months of spending as the server averages it. */
const spending: SpendingHistoryResponse = {
  start: '2026-07-01',
  end: '2026-09-30',
  months: ['2026-07', '2026-08', '2026-09'],
  groups: [
    {
      bucket: 'FIXED_COSTS',
      average: 230,
      categories: [
        {
          key: 'FIXED_COSTS/line/Groceries',
          kind: 'LINE',
          line: 'Groceries',
          category: null,
          category_detailed: null,
          average: 210,
          payees: [
            { key: 'FIXED_COSTS/line/Groceries|Whole Foods', name: 'Whole Foods', average: 180 },
            { key: 'FIXED_COSTS/line/Groceries|Trader Joe’s', name: 'Trader Joe’s', average: 30 },
          ],
        },
        {
          key: 'FIXED_COSTS/category/MEDICAL/MEDICAL_PHARMACIES_AND_SUPPLEMENTS',
          kind: 'CATEGORY',
          line: null,
          category: 'MEDICAL',
          category_detailed: 'MEDICAL_PHARMACIES_AND_SUPPLEMENTS',
          average: 20,
          payees: [{ key: 'medical|CVS', name: 'CVS', average: 20 }],
        },
      ],
    },
    {
      bucket: 'GUILT_FREE',
      average: 20,
      categories: [
        {
          key: 'GUILT_FREE/all',
          kind: 'ALL',
          line: null,
          category: null,
          category_detailed: null,
          average: 20,
          payees: [{ key: 'GUILT_FREE/all|Dig Inn', name: 'Dig Inn', average: 20 }],
        },
      ],
    },
  ],
}

const noSpending: SpendingHistoryResponse = { ...spending, months: [], groups: [] }

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
  vi.mocked(getSpendingHistory).mockResolvedValue(spending)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('spending explorer', () => {
  it('averages the last three whole months of spending in every tracked account', async () => {
    const wrapper = mountExplorer()
    await flushPromises()

    expect(getSpendingHistory).toHaveBeenCalledWith('2026-10', 3)
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(0)
  })

  it('lists each bucket’s plan lines, then its spending on no line by category', async () => {
    const wrapper = mountExplorer()
    await flushPromises()

    const [fixedCosts, guiltFree] = wrapper.findAll('section.group')
    expect(fixedCosts?.get('h3').text()).toBe('Fixed costs$230')
    expect(
      fixedCosts?.findAll('.part-heading, .categories > li > .pick-row').map((row) => row.text()),
    ).toEqual(['Groceries$210', 'On no line', 'Pharmacies and supplements$20'])
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

    expect(getSpendingHistory).toHaveBeenLastCalledWith('2026-10', 12)
  })

  it('shows the latest period’s spending when an earlier answer arrives late', async () => {
    let answerFirst: (spending: SpendingHistoryResponse) => void = () => {}
    vi.mocked(getSpendingHistory)
      .mockReturnValueOnce(new Promise((resolve) => (answerFirst = resolve)))
      .mockResolvedValueOnce(noSpending)
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
    vi.mocked(getSpendingHistory).mockResolvedValue(noSpending)
    const wrapper = mountExplorer()
    await flushPromises()

    expect(wrapper.text()).toContain('No spending in your tracked accounts over these months yet.')
  })

  it('offers to try again when spending can’t be loaded', async () => {
    vi.mocked(getSpendingHistory).mockRejectedValueOnce(new Error('offline'))
    const wrapper = mountExplorer()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('We couldn’t load your spending.')

    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(wrapper.get('section.group h3').text()).toBe('Fixed costs$230')
  })
})
