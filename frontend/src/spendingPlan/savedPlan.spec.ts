import { describe, expect, it } from 'vitest'
import type { SpendingPlanResponse } from '../api/SpendingPlanService'
import { defaultPlan, type PlanDraft } from './plan'
import { evaluationResponse } from './evaluationFixture'
import { fromEvaluation, fromSaved, toSaveRequest } from './savedPlan'

const plan: PlanDraft = {
  fixedCosts: [
    { name: 'Rent/mortgage', amount: 1450, items: [], fromPaycheck: false, percentOfGross: null },
    {
      name: 'Subscriptions',
      amount: null,
      fromPaycheck: false,
      percentOfGross: null,
      items: [
        { name: 'Netflix', amount: 15.49, streamId: 'stream-netflix' },
        { name: 'Gym', amount: 40, streamId: null },
      ],
    },
  ],
  investments: [
    { name: '401(k)', amount: 600, items: [], fromPaycheck: true, percentOfGross: 7.5 },
  ],
  savings: [
    { name: 'Vacations', amount: null, items: [], fromPaycheck: false, percentOfGross: null },
  ],
}

describe('toSaveRequest', () => {
  it('flattens the buckets into ordered lines in the API shape', () => {
    const request = toSaveRequest('checking', 5200, 8000, 12.5, plan)

    expect(request.account_id).toBe('checking')
    expect(request.take_home).toBe(5200)
    expect(request.gross_pay).toBe(8000)
    expect(request.fixed_cost_buffer_percent).toBe(12.5)
    expect(request.lines.map((line) => [line.bucket, line.name])).toEqual([
      ['FIXED_COSTS', 'Rent/mortgage'],
      ['FIXED_COSTS', 'Subscriptions'],
      ['INVESTMENTS', '401(k)'],
      ['SAVINGS', 'Vacations'],
    ])
    expect(request.lines[1]?.items[0]).toEqual({
      name: 'Netflix',
      amount: 15.49,
      stream_id: 'stream-netflix',
    })
    expect(request.lines[2]?.from_paycheck).toBe(true)
    expect(request.lines[2]?.percent_of_gross).toBe(7.5)
    expect(request.lines[0]?.percent_of_gross).toBeNull()
  })
})

describe('fromSaved', () => {
  it('rebuilds the same plan the editor saved', () => {
    const response: SpendingPlanResponse = {
      ...toSaveRequest('checking', 5200, 8000, 12.5, plan),
      updated_at: '2026-09-22T19:30:00Z',
      evaluation: evaluationResponse(),
    }

    expect(fromSaved(response)).toEqual({
      accountId: 'checking',
      takeHome: 5200,
      grossPay: 8000,
      bufferPercent: 12.5,
      plan,
      updatedAt: '2026-09-22T19:30:00Z',
      evaluation: fromEvaluation(evaluationResponse()),
    })
  })

  it('keeps buckets that were saved empty', () => {
    const empty: PlanDraft = { ...defaultPlan(), investments: [], savings: [] }
    const response = {
      ...toSaveRequest(null, null, null, 0, empty),
      updated_at: '2026-09-22T19:30:00Z',
      evaluation: evaluationResponse(),
    }

    expect(fromSaved(response).plan).toEqual(empty)
  })
})

describe('fromEvaluation', () => {
  it("puts the server's evaluation in the app's bucket names", () => {
    const evaluation = fromEvaluation(
      evaluationResponse({
        income: 5800,
        from_paycheck: 600,
        fixed_cost_subtotal: 1477.48,
        buffer: 221.62,
        buckets: {
          FIXED_COSTS: {
            amount: 1699.1,
            share: 29,
            target: { min: 50, max: 60 },
            status: 'below',
            flagged: false,
          },
        },
        guilt_free: {
          amount: -12,
          share: 0,
          target: { min: 20, max: 35 },
          status: 'below',
          flagged: true,
        },
      }),
    )

    expect(evaluation.income).toBe(5800)
    expect(evaluation.fromPaycheck).toBe(600)
    expect(evaluation.fixedCostSubtotal).toBe(1477.48)
    expect(evaluation.buffer).toBe(221.62)
    expect(evaluation.buckets.fixedCosts).toEqual({
      amount: 1699.1,
      share: 29,
      target: { min: 50, max: 60 },
      status: 'below',
      flagged: false,
    })
    expect(evaluation.buckets.savings.target).toEqual({ min: 5, max: 10 })
    expect(evaluation.guiltFree.amount).toBe(-12)
    expect(evaluation.guiltFree.flagged).toBe(true)
  })
})
