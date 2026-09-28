import { describe, expect, it } from 'vitest'
import type { PlaidTransaction, SpendingByBucket } from '../api/PlaidService'
import { OTHER_CATEGORY_COLOR, spendingByCategory } from './byCategory'

function transaction(id: string, amount: number, date: string): PlaidTransaction {
  return {
    transaction_id: id,
    account_id: 'checking',
    amount,
    iso_currency_code: 'USD',
    date,
    name: id,
    merchant_name: null,
    logo_url: null,
    pending: false,
    category: null,
    bucket: null,
    share_percent: 100,
  }
}

function summary(buckets: SpendingByBucket['buckets']): SpendingByBucket {
  return { start: '2026-09-01', end: '2026-09-30', total: 0, buckets }
}

describe('spendingByCategory', () => {
  it('merges a category across buckets, newest transaction first', () => {
    const result = spendingByCategory(
      summary([
        {
          bucket: 'FIXED_COSTS',
          amount: 60.1,
          categories: [
            {
              category: 'FOOD_AND_DRINK',
              amount: 60.1,
              transactions: [transaction('groceries', 60.1, '2026-09-03')],
            },
          ],
        },
        {
          bucket: 'UNSORTED',
          amount: 12.2,
          categories: [
            {
              category: 'FOOD_AND_DRINK',
              amount: 12.2,
              transactions: [transaction('coffee', 12.2, '2026-09-14')],
            },
          ],
        },
      ]),
    )

    expect(result).toHaveLength(1)
    expect(result[0]!.amount).toBe(72.3)
    expect(result[0]!.transactions.map((entry) => entry.transaction_id)).toEqual([
      'coffee',
      'groceries',
    ])
  })

  it('colors the largest categories first and folds the rest into a neutral', () => {
    const categories = Array.from({ length: 9 }, (_, index) => ({
      category: `CATEGORY_${index}`,
      amount: index + 1,
      transactions: [],
    }))
    const result = spendingByCategory(summary([{ bucket: 'UNSORTED', amount: 45, categories }]))

    expect(result.map((entry) => entry.category)[0]).toBe('CATEGORY_8')
    expect(result[0]!.color).toBe('#2a78d6')
    expect(new Set(result.slice(0, 8).map((entry) => entry.color)).size).toBe(8)
    expect(result[8]!.color).toBe(OTHER_CATEGORY_COLOR)
  })
})
