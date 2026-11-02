import { isAxiosError } from 'axios'
import { apiClient } from './client'
import type { Bucket, RecurringStream } from './PlaidService'

export type SpendingPlanBucket = 'FIXED_COSTS' | 'INVESTMENTS' | 'SAVINGS'

export interface SpendingPlanItemPayload {
  name: string
  amount: number | null
  stream_id: string | null
}

export interface SpendingPlanLinePayload {
  bucket: SpendingPlanBucket
  name: string
  /** Only used when the line has no items. */
  amount: number | null
  from_paycheck: boolean
  /** Set as a percent of gross pay; `amount` is still sent, worked out from it. */
  percent_of_gross: number | null
  items: SpendingPlanItemPayload[]
}

export interface SpendingPlanRequest {
  account_id: string | null
  take_home: number | null
  /** Monthly pay before taxes and deductions, for lines set as a percent of it. */
  gross_pay: number | null
  /** Percent of fixed costs added on top; the server defaults it to 15 when left out. */
  fixed_cost_buffer_percent: number
  lines: SpendingPlanLinePayload[]
}

export type PlanStatus = 'below' | 'within' | 'above'

/** A part of the plan measured against plan income. The server works these out. */
export interface PlanShareResponse {
  amount: number | null
  /** Percent of plan income, rounded; null until plan income is known. */
  share: number | null
  /** Suggested share of plan income, in percent. */
  target: { min: number; max: number }
  status: PlanStatus | null
  /** Needs the user's attention: fixed costs above target, or guilt-free spending below zero. */
  flagged: boolean
}

/** How a plan splits its income. */
export interface PlanEvaluationResponse {
  /** Take-home pay plus paycheck contributions; null until take-home pay is known. */
  plan_income: number | null
  from_paycheck: number
  /** Fixed cost lines before the buffer. */
  fixed_cost_subtotal: number
  buffer: number
  /** Fixed costs include the buffer. */
  buckets: Record<SpendingPlanBucket, PlanShareResponse & { amount: number }>
  /** Negative when the plan spends more than plan income. */
  guilt_free: PlanShareResponse
}

export interface SpendingPlanResponse extends SpendingPlanRequest {
  updated_at: string
  evaluation: PlanEvaluationResponse
}

/** What's left of the plan's guilt-free spending for a calendar month, across every tracked account. */
export interface GuiltFreeLeftResponse {
  /** What the plan leaves for guilt-free spending; null until the plan has take-home pay. */
  planned: number | null
  spent: number
  /** Negative once spending passes what was planned; null until the plan has take-home pay. */
  left: number | null
  /** The month's spending not sorted into a bucket yet. */
  unsorted: number
}

/** The user's saved plan, or null if they haven't saved one yet. */
export async function getSpendingPlan(): Promise<SpendingPlanResponse | null> {
  try {
    const { data } = await apiClient.get<SpendingPlanResponse>('/spending-plan')
    return data
  } catch (error) {
    if (isAxiosError(error) && error.response?.status === 404) return null
    throw error
  }
}

/** Saves the whole plan, replacing any saved one. */
export async function saveSpendingPlan(plan: SpendingPlanRequest): Promise<SpendingPlanResponse> {
  const { data } = await apiClient.put<SpendingPlanResponse>('/spending-plan', plan)
  return data
}

/** How a plan still being edited splits its income, without saving it. */
export async function evaluateSpendingPlan(
  plan: SpendingPlanRequest,
): Promise<PlanEvaluationResponse> {
  const { data } = await apiClient.post<PlanEvaluationResponse>('/spending-plan/evaluate', plan)
  return data
}

/**
 * What's left of guilt-free spending between two dates, a calendar month in the user's time
 * zone, or null if they haven't saved a plan.
 */
export async function getGuiltFreeLeft(range: {
  start: string
  end: string
}): Promise<GuiltFreeLeftResponse | null> {
  try {
    const { data } = await apiClient.get<GuiltFreeLeftResponse>('/spending-plan/guilt-free-left', {
      params: range,
    })
    return data
  } catch (error) {
    if (isAxiosError(error) && error.response?.status === 404) return null
    throw error
  }
}

/** A recurring payment as a breakdown item: named for who's paid, at the user's monthly share. */
export type PlanItemResponse = SpendingPlanItemPayload & { amount: number }

/**
 * A payment that counts as recurring: one of Plaid's streams (DETECTED) or a candidate the user
 * confirmed (CONFIRMED), with the item it makes on a plan.
 */
export interface RecurringPayment extends Omit<RecurringStream, 'status'> {
  status: 'DETECTED' | 'CONFIRMED'
  plan_item: PlanItemResponse
}

/** What a plan starts from, worked out by the server from the recurring payments. */
export interface PlanSuggestionsResponse {
  /** Monthly pay from recurring deposits; null when there are none. */
  take_home: number | null
  /** The spreadsheet's lines, then any others Jev put bills on, each bill as an item. */
  lines: SpendingPlanLinePayload[]
  /** Every payment that counts as recurring, soonest expected first. */
  recurring: RecurringPayment[]
  /** The bills among them on no line, for the user to place or skip. */
  unplaced: RecurringPayment[]
  /** Recurring candidates waiting for the user's answer, which aren't filled in. */
  suggested_candidates: number
}

/**
 * What a plan starts from. A new plan, even one replacing the saved plan, is offered every bill
 * on no line; otherwise bills already on the saved plan aren't offered again.
 */
export async function getPlanSuggestions(newPlan: boolean): Promise<PlanSuggestionsResponse> {
  const { data } = await apiClient.get<PlanSuggestionsResponse>('/spending-plan/suggestions', {
    params: { new_plan: newPlan },
  })
  return data
}

export interface PayeeHistoryResponse {
  key: string
  name: string
  average: number
}

/**
 * A plan line (LINE), a Plaid category for spending on no line (CATEGORY), or all of a
 * bucket's spending (ALL), with what was spent per month on average.
 */
export interface CategoryHistoryResponse {
  key: string
  kind: 'LINE' | 'CATEGORY' | 'ALL'
  line: string | null
  category: string | null
  category_detailed: string | null
  average: number
  /** Largest first. */
  payees: PayeeHistoryResponse[]
}

export interface GroupHistoryResponse {
  bucket: Bucket
  average: number
  /** Lines first, then categories, each largest first. */
  categories: CategoryHistoryResponse[]
}

/** What the user spent in each of the last few whole months, with the average per month. */
export interface SpendingHistoryResponse {
  start: string
  end: string
  /** The months averages are taken over, as YYYY-MM, from the first with any spending. */
  months: string[]
  groups: GroupHistoryResponse[]
}

/**
 * Spending over the `months` whole calendar months before `month` (YYYY-MM, the one the user is
 * in now), across every tracked account.
 */
export async function getSpendingHistory(
  month: string,
  months: number,
): Promise<SpendingHistoryResponse> {
  const { data } = await apiClient.get<SpendingHistoryResponse>('/spending-plan/spending-history', {
    params: { month, months },
  })
  return data
}

/**
 * What each blank line counts as in plan setup, by line name, in whole dollars: the user's
 * average monthly spending on it over the three whole months before `month` (YYYY-MM).
 */
export async function getLineDefaults(month: string): Promise<Map<string, number>> {
  const { data } = await apiClient.get<{ lines: Record<string, number> }>(
    '/spending-plan/line-defaults',
    { params: { month } },
  )
  return new Map(Object.entries(data.lines))
}
