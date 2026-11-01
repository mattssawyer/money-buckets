import type { PlanEvaluationResponse, PlanShareResponse } from '../api/SpendingPlanService'

/** For tests: an evaluation as the server sends it, with every part at zero and no income. */
export function evaluationResponse(
  overrides: Partial<Omit<PlanEvaluationResponse, 'buckets'>> & {
    buckets?: Partial<PlanEvaluationResponse['buckets']>
  } = {},
): PlanEvaluationResponse {
  const part = (min: number, max: number): PlanShareResponse & { amount: number } => ({
    amount: 0,
    share: null,
    target: { min, max },
    status: null,
    flagged: false,
  })
  const { buckets, ...rest } = overrides
  return {
    income: null,
    from_paycheck: 0,
    fixed_cost_subtotal: 0,
    buffer: 0,
    guilt_free: { ...part(20, 35), amount: null },
    ...rest,
    buckets: {
      FIXED_COSTS: part(50, 60),
      INVESTMENTS: part(10, 10),
      SAVINGS: part(5, 10),
      ...buckets,
    },
  }
}
