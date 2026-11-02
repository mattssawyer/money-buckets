import { describe, expect, it } from 'vitest'
import type { SpendingHistoryResponse } from '../api/SpendingPlanService'
import { currentMonth, labelHistory, searchHistory } from './spendingHistory'

/** As the server sends it: lines and categories already grouped and averaged. */
const response: SpendingHistoryResponse = {
  start: '2026-07-01',
  end: '2026-09-30',
  months: ['2026-07', '2026-08', '2026-09'],
  groups: [
    {
      bucket: 'FIXED_COSTS',
      average: 1890,
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
          key: 'FIXED_COSTS/category/TRANSPORTATION/TRANSPORTATION_PUBLIC_TRANSIT',
          kind: 'CATEGORY',
          line: null,
          category: 'TRANSPORTATION',
          category_detailed: 'TRANSPORTATION_PUBLIC_TRANSIT',
          average: 130,
          payees: [{ key: 'transit|MTA', name: 'MTA', average: 130 }],
        },
        {
          key: 'FIXED_COSTS/category/RENT_AND_UTILITIES/',
          kind: 'CATEGORY',
          line: null,
          category: 'RENT_AND_UTILITIES',
          category_detailed: null,
          average: 1500,
          payees: [{ key: 'rent|Sterling Group', name: 'Sterling Group', average: 1500 }],
        },
      ],
    },
    {
      bucket: 'GUILT_FREE',
      average: 30,
      categories: [
        {
          key: 'GUILT_FREE/all',
          kind: 'ALL',
          line: null,
          category: null,
          category_detailed: null,
          average: 30,
          payees: [
            { key: 'GUILT_FREE/all|Dig Inn', name: 'Dig Inn', average: 20 },
            { key: 'GUILT_FREE/all|Whole Foods', name: 'Whole Foods', average: 10 },
          ],
        },
      ],
    },
  ],
}

const history = labelHistory(response)

describe('spending history', () => {
  it('is the month the user is in, in their own time zone', () => {
    expect(currentMonth(new Date(2026, 9, 1))).toBe('2026-10')
    expect(currentMonth(new Date(2026, 0, 31, 23, 30))).toBe('2026-01')
  })

  it('labels buckets, lines, categories and the buckets listed as one total', () => {
    expect(history.months).toEqual(['2026-07', '2026-08', '2026-09'])
    expect(history.groups.map((group) => [group.label, group.average])).toEqual([
      ['Fixed costs', 1890],
      ['Guilt-free spending', 30],
    ])
    expect(
      history.groups.map((group) =>
        group.categories.map((category) => [category.label, category.onLine, category.average]),
      ),
    ).toEqual([
      [
        ['Groceries', true, 210],
        ['Public transit', false, 130],
        // A corrected category has no detailed one, so it's named for its primary.
        ['Rent & utilities', false, 1500],
      ],
      [['All guilt-free spending', false, 30]],
    ])
  })

  it('searches lines and categories by name and otherwise by payee', () => {
    const groceries = searchHistory(history.groups, 'grocer')
    expect(groceries.map((group) => group.label)).toEqual(['Fixed costs'])
    expect(groceries[0]?.categories).toHaveLength(1)
    expect(groceries[0]?.categories[0]?.payees).toHaveLength(2)

    const byPayee = searchHistory(history.groups, 'trader')
    expect(byPayee).toHaveLength(1)
    expect(byPayee[0]?.categories[0]?.payees.map((payee) => payee.name)).toEqual(['Trader Joe’s'])

    expect(searchHistory(history.groups, 'guilt')[0]?.categories[0]?.payees).toHaveLength(2)
    expect(searchHistory(history.groups, 'nothing like this')).toEqual([])
  })
})
