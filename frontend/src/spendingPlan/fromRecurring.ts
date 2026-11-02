import type { RecurringPayment } from '../api/SpendingPlanService'

/** How the recurring payments changed between two loads, such as after the user answered some. */
export interface RecurringChanges {
  /** Payments that count as recurring now, like a candidate the user confirmed. */
  added: RecurringPayment[]
  /** Payments that no longer count, like one the user said doesn't repeat, by stream id. */
  removed: Set<string>
  /** Monthly amounts that changed, like after the user set how often a bill is paid. */
  amounts: Map<string, { from: number; to: number }>
}

/** Compares two of the server's lists of recurring payments. */
export function recurringChanges(
  before: RecurringPayment[],
  after: RecurringPayment[],
): RecurringChanges {
  const previous = new Map(before.map((payment) => [payment.stream_id, payment]))
  const current = new Set(after.map((payment) => payment.stream_id))
  const changes: RecurringChanges = {
    added: [],
    removed: new Set(before.map((payment) => payment.stream_id).filter((id) => !current.has(id))),
    amounts: new Map(),
  }
  for (const payment of after) {
    const old = previous.get(payment.stream_id)
    if (!old) {
      changes.added.push(payment)
      continue
    }
    const from = old.plan_item.amount
    const to = payment.plan_item.amount
    if (from !== to) changes.amounts.set(payment.stream_id, { from, to })
  }
  return changes
}
