import type {
  PlanEvaluationResponse,
  PlanShareResponse,
  SpendingPlanBucket,
  SpendingPlanRequest,
} from '../../api/SpendingPlanService'

/**
 * For tests only: stands in for the server's plan evaluation, so editor tests can type amounts
 * and see totals. The real rules live on the server (PlanEvaluation), which has its own tests.
 */
export function fakeEvaluation(request: SpendingPlanRequest): PlanEvaluationResponse {
  const cents = (value: number) => Math.round(value * 100) / 100
  const lineAmount = (line: SpendingPlanRequest['lines'][number]) => {
    if (line.items.length) return line.items.reduce((sum, item) => sum + (item.amount ?? 0), 0)
    if (line.percent_of_gross != null && request.gross_pay)
      return cents((request.gross_pay * line.percent_of_gross) / 100)
    return line.amount ?? 0
  }
  const total = (bucket: SpendingPlanBucket, paycheckOnly = false) =>
    cents(
      request.lines
        .filter((line) => line.bucket === bucket && (!paycheckOnly || line.from_paycheck))
        .reduce((sum, line) => sum + lineAmount(line), 0),
    )
  const fixedCostSubtotal = total('FIXED_COSTS')
  const buffer = cents((fixedCostSubtotal * (request.fixed_cost_buffer_percent ?? 0)) / 100)
  const fromPaycheck = total('INVESTMENTS', true)
  const income = request.take_home == null ? null : cents(request.take_home + fromPaycheck)
  const amounts = {
    FIXED_COSTS: cents(fixedCostSubtotal + buffer),
    INVESTMENTS: total('INVESTMENTS'),
    SAVINGS: total('SAVINGS'),
  }
  const planned = amounts.FIXED_COSTS + amounts.INVESTMENTS + amounts.SAVINGS
  const guiltFree = income == null ? null : cents(income - planned)

  function part<T extends number | null>(
    amount: T,
    min: number,
    max: number,
    flag: (status: PlanShareResponse['status']) => boolean,
  ) {
    const share =
      amount == null || income == null || income <= 0 ? null : Math.round((amount / income) * 100)
    const status = share == null ? null : share < min ? 'below' : share > max ? 'above' : 'within'
    return { amount, share, target: { min, max }, status, flagged: flag(status) } as const
  }

  return {
    plan_income: income,
    from_paycheck: fromPaycheck,
    fixed_cost_subtotal: fixedCostSubtotal,
    buffer,
    buckets: {
      FIXED_COSTS: part(amounts.FIXED_COSTS, 50, 60, (status) => status === 'above'),
      INVESTMENTS: part(amounts.INVESTMENTS, 10, 10, () => false),
      SAVINGS: part(amounts.SAVINGS, 5, 10, () => false),
    },
    guilt_free: part(guiltFree, 20, 35, () => guiltFree != null && guiltFree < 0),
  }
}
