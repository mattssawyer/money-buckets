import { describe, expect, it } from 'vitest'
import { yourAmount } from './plaidLabels'

describe('yourAmount', () => {
  it('keeps the whole amount when the account isn’t shared', () => {
    expect(yourAmount({ amount: 4.75, share_percent: 100 })).toBe(4.75)
  })

  it('rounds a charge and its refund the same way, so they cancel out', () => {
    const charge = yourAmount({ amount: 4.75, share_percent: 50 })
    const refund = yourAmount({ amount: -4.75, share_percent: 50 })

    expect(charge).toBe(2.38)
    expect(refund).toBe(-2.38)
    expect(charge + refund).toBe(0)
  })
})
