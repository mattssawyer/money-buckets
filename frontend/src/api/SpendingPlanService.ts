import { isAxiosError } from 'axios'
import { apiClient } from './client'

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
  income: number | null
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
  /** Null until the plan has take-home pay. */
  budget: number | null
  spent: number
  /** Negative once spending passes the budget; null until the plan has take-home pay. */
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
