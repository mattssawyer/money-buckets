import { describe, expect, it } from 'vitest'
import type { BucketSpend, SpendingByBucket } from '../api/PlaidService'
import { guiltFreeLeft } from './guiltFreeLeft'
import type { SavedPlan } from './savedPlan'

function savedPlan(takeHome: number | null): SavedPlan {
  return {
    accountId: null,
    takeHome,
    grossPay: null,
    bufferPercent: 10,
    plan: {
      fixedCosts: [{ name: 'Rent', amount: 2000, items: [], fromPaycheck: false }],
      investments: [{ name: '401(k)', amount: 500, items: [], fromPaycheck: true }],
      savings: [{ name: 'Emergency fund', amount: 300, items: [], fromPaycheck: false }],
    },
    updatedAt: '2026-10-01T00:00:00Z',
  }
}

function month(...buckets: [BucketSpend['bucket'], number][]): SpendingByBucket {
  return {
    start: '2026-10-01',
    end: '2026-10-31',
    total: buckets.reduce((sum, [, amount]) => sum + amount, 0),
    buckets: buckets.map(([bucket, amount]) => ({ bucket, amount, categories: [] })),
  }
}

describe('guiltFreeLeft', () => {
  // 4,000 take-home + 500 from the paycheck, less 2,200 fixed with the buffer, 500 and 300.
  it("takes the month's guilt-free spending from what the plan leaves for it", () => {
    expect(
      guiltFreeLeft(savedPlan(4000), month(['FIXED_COSTS', 2100], ['GUILT_FREE', 612.4])),
    ).toEqual({
      budget: 1500,
      spent: 612.4,
      left: 887.6,
    })
  })

  it('has all of it left before any guilt-free spending', () => {
    expect(guiltFreeLeft(savedPlan(4000), month(['FIXED_COSTS', 2100]))?.left).toBe(1500)
  })

  it('goes below zero once spending passes the budget', () => {
    expect(guiltFreeLeft(savedPlan(4000), month(['GUILT_FREE', 1620]))?.left).toBe(-120)
  })

  it('is unknown until the plan has take-home pay', () => {
    expect(guiltFreeLeft(savedPlan(null), month(['GUILT_FREE', 50]))).toBeNull()
  })
})
