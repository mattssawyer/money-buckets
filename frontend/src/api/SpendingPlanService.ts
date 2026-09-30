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
  items: SpendingPlanItemPayload[]
}

export interface SpendingPlanRequest {
  account_id: string | null
  take_home: number | null
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

export interface RecurringPaymentPayload {
  id: string
  description: string | null
  merchant: string | null
  /** The whole payment, not the user's share: who's paid and how much is what tells its line. */
  amount: number
  frequency: string
}

export interface ChosenLine {
  id: string
  bucket: SpendingPlanBucket
  line: string
}

/**
 * Asks Jev which of the plan's lines each recurring payment belongs under. Payments that fit no
 * line are left out of the answer.
 */
export async function chooseRecurringLines(
  lines: { fixed_costs: string[]; investments: string[]; savings: string[] },
  payments: RecurringPaymentPayload[],
): Promise<ChosenLine[]> {
  const { data } = await apiClient.post<{ lines: ChosenLine[] }>('/spending-plan/recurring-lines', {
    lines,
    payments,
  })
  return data.lines
}
