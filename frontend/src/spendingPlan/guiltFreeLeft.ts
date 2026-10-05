import type { SpendingByBucket } from '../api/PlaidService'
import { roundCents } from './money'
import { evaluatePlan } from './plan'
import type { SavedPlan } from './savedPlan'

export interface GuiltFreeLeft {
  /** What the plan leaves for guilt-free spending each month. */
  budget: number
  spent: number
  /** Negative once spending goes over the budget. */
  left: number
}

/**
 * How much of the plan's guilt-free spending is left, given a month's spending across every
 * tracked account. Null until the plan has take-home pay to work the budget out from.
 */
export function guiltFreeLeft(plan: SavedPlan, month: SpendingByBucket): GuiltFreeLeft | null {
  const budget = evaluatePlan(plan.takeHome, plan.plan, plan.bufferPercent).guiltFree.amount
  if (budget == null) return null
  const spent = month.buckets.find((entry) => entry.bucket === 'GUILT_FREE')?.amount ?? 0
  return { budget, spent, left: roundCents(budget - spent) }
}
