import { describe, expect, it } from 'vitest'
import type { PlaidTransaction, SpendingByBucket } from '../api/PlaidService'
import {
  historyRange,
  monthLabel,
  searchHistory,
  selectionHistory,
  spendingHistory,
} from './spendingHistory'

let nextId = 0

function spent(
  merchant: string,
  amount: number,
  date: string,
  detailed: string | null,
  primary = 'FOOD_AND_DRINK',
  sharePercent = 100,
): PlaidTransaction {
  return {
    transaction_id: `txn-${nextId++}`,
    account_id: 'checking',
    amount,
    iso_currency_code: 'USD',
    date,
    name: merchant.toUpperCase(),
    merchant_name: merchant,
    logo_url: null,
    pending: false,
    category: primary,
    category_detailed: detailed,
    bucket: 'FIXED_COSTS',
    share_percent: sharePercent,
    payee_key: merchant.toLowerCase(),
    payee_kind: 'BILL',
    bucket_corrected: false,
    category_corrected: false,
    recurring: null,
  }
}

function spending(transactions: PlaidTransaction[]): SpendingByBucket {
  return {
    start: '2026-07-01',
    end: '2026-09-30',
    total: 0,
    buckets: [
      {
        bucket: 'FIXED_COSTS',
        amount: 0,
        categories: [{ category: 'FOOD_AND_DRINK', amount: 0, transactions }],
      },
    ],
  }
}

const GROCERIES = 'FOOD_AND_DRINK_GROCERIES'
const RESTAURANT = 'FOOD_AND_DRINK_RESTAURANT'

const history = spendingHistory(
  spending([
    spent('Whole Foods', 300, '2026-07-04', GROCERIES),
    spent('Whole Foods', 240, '2026-08-11', GROCERIES),
    spent('Trader Joe’s', 90, '2026-09-20', GROCERIES),
    spent('Dig Inn', 60, '2026-09-02', RESTAURANT),
    spent('Uniqlo', 150, '2026-08-15', 'GENERAL_MERCHANDISE_CLOTHING', 'GENERAL_MERCHANDISE'),
  ]),
)

describe('spending history', () => {
  it('covers whole months ending with last month', () => {
    expect(historyRange(3, new Date(2026, 9, 1))).toEqual({
      start: '2026-07-01',
      end: '2026-09-30',
    })
    expect(historyRange(12, new Date(2026, 0, 15))).toEqual({
      start: '2025-01-01',
      end: '2025-12-31',
    })
  })

  it('averages each category and payee per month, largest first', () => {
    expect(history.months).toEqual(['2026-07', '2026-08', '2026-09'])
    expect(history.groups.map((group) => [group.label, group.average])).toEqual([
      ['Food & drink', 230],
      ['Shopping', 50],
    ])
    expect(history.groups[0]?.categories).toEqual([
      {
        key: 'FOOD_AND_DRINK/FOOD_AND_DRINK_GROCERIES',
        label: 'Groceries',
        average: 210,
        payees: [
          {
            key: 'FOOD_AND_DRINK/FOOD_AND_DRINK_GROCERIES|Whole Foods',
            name: 'Whole Foods',
            average: 180,
          },
          {
            key: 'FOOD_AND_DRINK/FOOD_AND_DRINK_GROCERIES|Trader Joe’s',
            name: 'Trader Joe’s',
            average: 30,
          },
        ],
      },
      {
        key: 'FOOD_AND_DRINK/FOOD_AND_DRINK_RESTAURANT',
        label: 'Restaurant',
        average: 20,
        payees: [
          { key: 'FOOD_AND_DRINK/FOOD_AND_DRINK_RESTAURANT|Dig Inn', name: 'Dig Inn', average: 20 },
        ],
      },
    ])
  })

  it('averages only over months since the first transaction', () => {
    const recent = spendingHistory(
      spending([
        spent('Whole Foods', 100, '2026-08-20', GROCERIES),
        spent('Whole Foods', 300, '2026-09-11', GROCERIES),
      ]),
    )

    expect(recent.months).toEqual(['2026-08', '2026-09'])
    expect(recent.groups[0]?.average).toBe(200)
  })

  it('counts the user’s share, nets refunds, and drops what nets to nothing', () => {
    const netted = spendingHistory(
      spending([
        spent('Costco', 600, '2026-07-10', GROCERIES, 'FOOD_AND_DRINK', 50),
        spent('Uniqlo', 90, '2026-07-12', RESTAURANT),
        spent('Uniqlo', -90, '2026-07-20', RESTAURANT),
      ]),
    )

    expect(
      netted.groups[0]?.categories.map((category) => [category.label, category.average]),
    ).toEqual([['Groceries', 100]])
  })

  it('files a corrected category, which has no detailed one, under its primary name', () => {
    const corrected = spendingHistory(
      spending([spent('Sterling Group', 1500, '2026-09-01', null, 'RENT_AND_UTILITIES')]),
    )

    expect(corrected.groups[0]?.categories[0]?.label).toBe('Rent & utilities')
  })

  it('is empty without spending', () => {
    expect(spendingHistory(spending([]))).toEqual({ months: [], groups: [], entries: [] })
  })

  it('adds up everything until something is picked', () => {
    expect(selectionHistory(history, { categories: new Set(), payees: new Set() })).toEqual({
      average: 280,
      byMonth: [300, 390, 150],
    })
  })

  it('adds up picked categories and payees without counting a payee twice', () => {
    const picked = selectionHistory(history, {
      categories: new Set(['FOOD_AND_DRINK/FOOD_AND_DRINK_GROCERIES']),
      payees: new Set([
        'FOOD_AND_DRINK/FOOD_AND_DRINK_GROCERIES|Whole Foods',
        'FOOD_AND_DRINK/FOOD_AND_DRINK_RESTAURANT|Dig Inn',
      ]),
    })

    expect(picked).toEqual({ average: 230, byMonth: [300, 240, 150] })
  })

  it('searches categories by name and otherwise by payee', () => {
    expect(searchHistory(history.groups, 'grocer')[0]?.categories).toHaveLength(1)
    expect(searchHistory(history.groups, 'grocer')[0]?.categories[0]?.payees).toHaveLength(2)

    const byPayee = searchHistory(history.groups, 'trader')
    expect(byPayee).toHaveLength(1)
    expect(byPayee[0]?.categories[0]?.payees.map((payee) => payee.name)).toEqual(['Trader Joe’s'])

    expect(searchHistory(history.groups, 'shopping')[0]?.label).toBe('Shopping')
    expect(searchHistory(history.groups, 'nothing like this')).toEqual([])
  })

  it('names a month', () => {
    expect(monthLabel('2026-07')).toBe('Jul')
  })
})
