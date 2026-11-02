import { describe, expect, it } from 'vitest'
import type { RecurringPayment } from '../api/SpendingPlanService'
import { recurringChanges } from './fromRecurring'

function payment(streamId: string, monthly: number): RecurringPayment {
  return {
    stream_id: streamId,
    account_id: 'checking',
    merchant_name: streamId,
    description: streamId.toUpperCase(),
    amount: monthly,
    iso_currency_code: 'USD',
    frequency: 'MONTHLY',
    frequency_set: false,
    next_date: '2026-10-01',
    last_date: '2026-09-01',
    is_inflow: false,
    category: 'GENERAL_SERVICES',
    category_detailed: null,
    share_percent: 100,
    plan_bucket: null,
    plan_line: null,
    kind: null,
    merchant_key: null,
    status: 'DETECTED',
    plan_item: { name: streamId, amount: monthly, stream_id: streamId },
  }
}

describe('recurringChanges', () => {
  const rent = payment('rent', 1450)
  const gym = payment('gym', 40)
  const netflix = payment('netflix', 15.49)

  it('finds the payments that started and stopped counting as recurring', () => {
    const changes = recurringChanges([rent, gym], [rent, netflix])

    expect(changes.added).toEqual([netflix])
    expect([...changes.removed]).toEqual(['gym'])
    expect(changes.amounts.size).toBe(0)
  })

  it('finds monthly amounts that changed with how often a bill is paid', () => {
    const biweekly = {
      ...gym,
      frequency: 'BIWEEKLY',
      plan_item: { ...gym.plan_item, amount: 86.67 },
    }

    const changes = recurringChanges([gym], [biweekly])

    expect(changes.amounts.get('gym')).toEqual({ from: 40, to: 86.67 })
    expect(changes.added).toEqual([])
    expect(changes.removed.size).toBe(0)
  })
})
