import { roundCents } from './money'

export type BucketId = 'fixedCosts' | 'investments' | 'savings'

export const BUCKET_IDS: readonly BucketId[] = ['fixedCosts', 'investments', 'savings']

export const PLAN_TITLES = {
  fixedCosts: 'Fixed costs',
  investments: 'Investments',
  savings: 'Savings',
  guiltFree: 'Guilt-free spending',
} as const satisfies Record<BucketId | 'guiltFree', string>

/**
 * Each part of the plan keeps one color everywhere it's charted. Categorical slots from the
 * validated dataviz palette, in fixed order.
 */
export const PLAN_COLORS = {
  fixedCosts: '#2a78d6',
  investments: '#eb6834',
  savings: '#1baf7a',
  guiltFree: '#eda100',
} as const satisfies Record<BucketId | 'guiltFree', string>

/**
 * Default lines for each bucket, from the Conscious Spending Plan spreadsheet. The server offers
 * Jev the same lines (PlanLines.DEFAULT) until a plan is saved, so keep the two in step.
 */
export const PLAN_LINES = {
  fixedCosts: [
    'Rent/mortgage',
    'Utilities',
    'Insurance',
    'Car payment',
    'Debt payments',
    'Groceries',
    'Clothes',
    'Phone',
    'Internet',
    'Subscriptions',
  ],
  investments: ['401(k)', 'Roth IRA', 'Other investments'],
  savings: ['Emergency fund', 'Vacations', 'Gifts', 'House down payment'],
} as const satisfies Record<BucketId, readonly string[]>

/**
 * The spreadsheet adds this share of fixed costs on top, for costs you forgot and prices that
 * rise. Adjustable per plan.
 */
export const DEFAULT_BUFFER_PERCENT = 15

/** Lines that start out marked as taken from the paycheck. */
const PAYCHECK_LINES: ReadonlySet<string> = new Set(['401(k)'])

/** Suggested share of plan income, in percent. */
export interface Target {
  min: number
  max: number
}

export interface PlanItemDraft {
  name: string
  amount: number | null
  /**
   * The recurring stream this item was filled from, or null if typed in. A saved plan keeps its
   * own amounts; this link is what lets later changes to the stream be offered as suggestions.
   */
  streamId: string | null
}

/**
 * A line in one of the plan's buckets. With no breakdown items, `amount` is what the user typed;
 * with items, the line's amount is their sum and `amount` is unused.
 */
export interface PlanLineDraft {
  name: string
  amount: number | null
  items: PlanItemDraft[]
  /**
   * Taken out of the paycheck before it's deposited, like most 401(k)s. Take-home pay already
   * excludes it, so it's added back to the income base instead of being subtracted twice.
   */
  fromPaycheck: boolean
  /**
   * A paycheck line set as a percent of gross pay, like most 401(k) elections; its amount is
   * worked out from it. Left out for a line set in dollars.
   */
  percentOfGross?: number | null
}

export type PlanDraft = Record<BucketId, PlanLineDraft[]>

/** The spreadsheet's lines for every bucket, all blank. */
export function defaultPlan(): PlanDraft {
  const linesFor = (bucket: BucketId): PlanLineDraft[] =>
    PLAN_LINES[bucket].map((name) => ({
      name,
      amount: null,
      items: [],
      fromPaycheck: bucket === 'investments' && PAYCHECK_LINES.has(name),
      percentOfGross: null,
    }))

  return {
    fixedCosts: linesFor('fixedCosts'),
    investments: linesFor('investments'),
    savings: linesFor('savings'),
  }
}

export function lineAmount(line: PlanLineDraft): number | null {
  if (line.items.length === 0) return line.amount
  return roundCents(line.items.reduce((sum, item) => sum + (item.amount ?? 0), 0))
}

/** Where a share of plan income falls against its target; null until plan income is known. */
export type TargetStatus = 'below' | 'within' | 'above'

interface ShareEvaluation {
  /** Percent of plan income, rounded; null until plan income is known. */
  share: number | null
  target: Target
  status: TargetStatus | null
  /** Needs the user's attention: fixed costs above target, or guilt-free spending below zero. */
  flagged: boolean
}

export interface BucketEvaluation extends ShareEvaluation {
  /** Fixed costs include the buffer. */
  amount: number
}

export interface GuiltFreeEvaluation extends ShareEvaluation {
  /** Negative when the plan spends more than plan income; null until take-home pay is known. */
  amount: number | null
}

/** How a plan splits its income, and which parts need attention. The server works it out. */
export interface PlanEvaluation {
  /** Take-home pay plus paycheck contributions; null until take-home pay is known. */
  income: number | null
  /** Investments taken from the paycheck, added back onto take-home pay. */
  fromPaycheck: number
  /** Fixed cost lines before the buffer. */
  fixedCostSubtotal: number
  buffer: number
  buckets: Record<BucketId, BucketEvaluation>
  guiltFree: GuiltFreeEvaluation
}
