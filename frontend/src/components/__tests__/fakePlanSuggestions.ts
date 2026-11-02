import type { RecurringCandidate, RecurringStream } from '../../api/PlaidService'
import type {
  PlanSuggestionsResponse,
  RecurringPayment,
  SpendingPlanLinePayload,
} from '../../api/SpendingPlanService'
import { recurringLabel, yourAmount } from '../../api/plaidLabels'
import { toSaveRequest } from '../../spendingPlan/savedPlan'
import { defaultPlan } from '../../spendingPlan/plan'

const PER_MONTH: Record<string, number> = {
  WEEKLY: 52 / 12,
  BIWEEKLY: 26 / 12,
  SEMI_MONTHLY: 2,
  MONTHLY: 1,
  QUARTERLY: 1 / 3,
  SEMI_ANNUALLY: 1 / 6,
  ANNUALLY: 1 / 12,
}

/**
 * For tests only: stands in for the server's plan suggestions, so setup tests can describe
 * recurring streams and candidates and see what the plan starts from. The real rules live on
 * the server (PlanSuggestions), which has its own tests.
 */
export function fakeSuggestions(
  streams: RecurringStream[],
  candidates: RecurringCandidate[] = [],
): PlanSuggestionsResponse {
  const cents = (value: number) => Math.round(value * 100) / 100
  const recurring: RecurringPayment[] = [
    ...streams,
    ...candidates.filter((candidate) => candidate.status === 'CONFIRMED'),
  ].map((stream) => ({
    ...stream,
    status: stream.status === 'CONFIRMED' ? 'CONFIRMED' : 'DETECTED',
    plan_item: {
      name: recurringLabel(stream),
      amount: cents(Math.abs(yourAmount(stream)) * (PER_MONTH[stream.frequency] ?? 1)),
      stream_id: stream.stream_id,
    },
  }))

  const pay = recurring
    .filter((payment) => payment.is_inflow && payment.category !== 'TRANSFER_IN')
    .reduce((sum, payment) => sum + payment.plan_item.amount, 0)

  const lines: SpendingPlanLinePayload[] = toSaveRequest(null, null, null, 0, defaultPlan()).lines
  for (const payment of recurring) {
    if (payment.is_inflow || !payment.plan_bucket || !payment.plan_line) continue
    let line = lines.find(
      (candidate) =>
        candidate.bucket === payment.plan_bucket && candidate.name === payment.plan_line,
    )
    if (!line) {
      line = {
        bucket: payment.plan_bucket,
        name: payment.plan_line,
        amount: null,
        from_paycheck: false,
        percent_of_gross: null,
        items: [],
      }
      lines.push(line)
    }
    line.items.push(payment.plan_item)
  }
  const order = ['FIXED_COSTS', 'INVESTMENTS', 'SAVINGS']
  lines.sort((a, b) => order.indexOf(a.bucket) - order.indexOf(b.bucket))

  return {
    take_home: pay > 0 ? cents(pay) : null,
    lines,
    recurring,
    unplaced: recurring.filter(
      (payment) =>
        !payment.is_inflow &&
        !payment.plan_line &&
        payment.category_detailed !== 'LOAN_PAYMENTS_CREDIT_CARD_PAYMENT',
    ),
    suggested_candidates: candidates.filter((candidate) => candidate.status === 'SUGGESTED').length,
  }
}
