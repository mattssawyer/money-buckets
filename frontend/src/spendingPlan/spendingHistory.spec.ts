import { describe, expect, it } from 'vitest'
import type { Bucket, PlaidTransaction, SpendingByBucket } from '../api/PlaidService'
import { historyRange, lineAverages, searchHistory, spendingHistory } from './spendingHistory'

let nextId = 0

interface Spent {
  line?: string
  detailed?: string | null
  primary?: string
  sharePercent?: number
}

function spent(
  merchant: string,
  amount: number,
  date: string,
  where: Spent = {},
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
    category: where.primary ?? 'FOOD_AND_DRINK',
    category_detailed: where.detailed === undefined ? 'FOOD_AND_DRINK_GROCERIES' : where.detailed,
    bucket: 'FIXED_COSTS',
    plan_line: where.line ?? null,
    share_percent: where.sharePercent ?? 100,
    payee_key: merchant.toLowerCase(),
    payee_kind: 'BILL',
    bucket_corrected: false,
    category_corrected: false,
    recurring: null,
  }
}

function spending(byBucket: Partial<Record<Bucket, PlaidTransaction[]>>): SpendingByBucket {
  return {
    start: '2026-07-01',
    end: '2026-09-30',
    total: 0,
    // The server sends buckets in its own order; the history puts them in the plan's.
    buckets: (Object.keys(byBucket) as Bucket[]).map((bucket) => ({
      bucket,
      amount: 0,
      categories: [{ category: 'FOOD_AND_DRINK', amount: 0, transactions: byBucket[bucket] ?? [] }],
    })),
  }
}

const GROCERIES = { line: 'Groceries' }
const RESTAURANT = { detailed: 'FOOD_AND_DRINK_RESTAURANT' }
const TRANSIT = { primary: 'TRANSPORTATION', detailed: 'TRANSPORTATION_PUBLIC_TRANSIT' }

const history = spendingHistory(
  spending({
    GUILT_FREE: [
      spent('Dig Inn', 60, '2026-09-02', RESTAURANT),
      spent('Whole Foods', 30, '2026-09-03'),
    ],
    FIXED_COSTS: [
      spent('MTA', 390, '2026-08-01', TRANSIT),
      spent('Whole Foods', 300, '2026-07-04', GROCERIES),
      spent('Whole Foods', 240, '2026-08-11', GROCERIES),
      spent('Trader Joe’s', 90, '2026-09-20', GROCERIES),
      spent('Uniqlo', 150, '2026-08-15', { line: 'Clothes' }),
    ],
  }),
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

  it('lists fixed costs by line, then by category for spending on no line; guilt-free as one total', () => {
    expect(history.months).toEqual(['2026-07', '2026-08', '2026-09'])
    expect(history.groups.map((group) => [group.label, group.average])).toEqual([
      ['Fixed costs', 390],
      ['Guilt-free spending', 30],
    ])
    expect(
      history.groups.map((group) =>
        group.categories.map((category) => [category.label, category.onLine, category.average]),
      ),
    ).toEqual([
      [
        ['Groceries', true, 210],
        ['Clothes', true, 50],
        ['Public transit', false, 130],
      ],
      [['All guilt-free spending', false, 30]],
    ])
  })

  it('averages a line’s payees per month, largest first', () => {
    expect(history.groups[0]?.categories[0]).toEqual({
      key: 'FIXED_COSTS/line/Groceries',
      label: 'Groceries',
      onLine: true,
      average: 210,
      payees: [
        { key: 'FIXED_COSTS/line/Groceries|Whole Foods', name: 'Whole Foods', average: 180 },
        { key: 'FIXED_COSTS/line/Groceries|Trader Joe’s', name: 'Trader Joe’s', average: 30 },
      ],
    })
  })

  it('averages only over months since the first transaction', () => {
    const recent = spendingHistory(
      spending({
        FIXED_COSTS: [
          spent('Whole Foods', 100, '2026-08-20', GROCERIES),
          spent('Whole Foods', 300, '2026-09-11', GROCERIES),
        ],
      }),
    )

    expect(recent.months).toEqual(['2026-08', '2026-09'])
    expect(recent.groups[0]?.average).toBe(200)
  })

  it('counts the user’s share, nets refunds, and drops what nets to nothing', () => {
    const netted = spendingHistory(
      spending({
        FIXED_COSTS: [
          spent('Costco', 600, '2026-07-10', { ...GROCERIES, sharePercent: 50 }),
          spent('Uniqlo', 90, '2026-07-12', { line: 'Clothes' }),
          spent('Uniqlo', -90, '2026-07-20', { line: 'Clothes' }),
        ],
      }),
    )

    expect(
      netted.groups[0]?.categories.map((category) => [category.label, category.average]),
    ).toEqual([['Groceries', 100]])
  })

  it('files a corrected category, which has no detailed one, under its primary name', () => {
    const corrected = spendingHistory(
      spending({
        FIXED_COSTS: [
          spent('Sterling Group', 1500, '2026-09-01', {
            primary: 'RENT_AND_UTILITIES',
            detailed: null,
          }),
        ],
      }),
    )

    expect(corrected.groups[0]?.categories[0]?.label).toBe('Rent & utilities')
  })

  it('is empty without spending', () => {
    expect(spendingHistory(spending({}))).toEqual({ months: [], groups: [] })
  })

  it('gives each plan line its monthly average in whole dollars, and nothing off a line', () => {
    expect([...lineAverages(history)]).toEqual([
      ['Groceries', 210],
      ['Clothes', 50],
    ])

    const uneven = spendingHistory(
      spending({ FIXED_COSTS: [spent('Whole Foods', 100, '2026-07-04', GROCERIES)] }),
    )
    expect(lineAverages(uneven).get('Groceries')).toBe(33)
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
