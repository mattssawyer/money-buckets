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

export interface SpendingPlanResponse extends SpendingPlanRequest {
  updated_at: string
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
