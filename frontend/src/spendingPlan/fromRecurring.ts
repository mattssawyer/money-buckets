import type { RecurringStream } from '../api/PlaidService'
import { recurringLabel, yourAmount } from '../api/plaidLabels'
import { roundCents } from './money'
import { defaultPlan, type BucketId, type PlanDraft, type PlanItemDraft } from './plan'
import { BUCKET_IDS } from './savedPlan'

const MONTHLY_MULTIPLIER: Record<string, number> = {
  WEEKLY: 52 / 12,
  BIWEEKLY: 26 / 12,
  SEMI_MONTHLY: 2,
  MONTHLY: 1,
  QUARTERLY: 1 / 3,
  SEMI_ANNUALLY: 1 / 6,
  ANNUALLY: 1 / 12,
}

export function toMonthlyAmount(amount: number, frequency: string): number {
  const multiplier = MONTHLY_MULTIPLIER[frequency] ?? 1
  return roundCents(Math.abs(amount) * multiplier)
}

/**
 * Monthly pay from recurring deposits, at the user's share of each account. Transfers are left
 * out: money moved in from another of the user's accounts, or a housemate's part of a shared
 * account, isn't pay.
 */
export function estimateMonthlyTakeHome(streams: RecurringStream[]): number | null {
  const total = streams
    .filter((stream) => stream.is_inflow && stream.category !== 'TRANSFER_IN')
    .reduce((sum, stream) => sum + toMonthlyAmount(yourAmount(stream), stream.frequency), 0)

  return total > 0 ? roundCents(total) : null
}

/**
 * Money out that never goes on a plan line: card payments, whose purchases already count on the
 * card itself.
 */
const NOT_PLAN_MONEY = new Set(['LOAN_PAYMENTS_CREDIT_CARD_PAYMENT'])

/**
 * The spreadsheet's lines for every bucket, broken down into the recurring bills Jev put on
 * them. A bill on a line the plan doesn't have, such as one from a saved plan's lines, adds it.
 */
export function planFromRecurring(streams: RecurringStream[]): PlanDraft {
  const plan = defaultPlan()
  for (const stream of streams) {
    if (stream.is_inflow || !stream.plan_bucket || !stream.plan_line) continue
    addToLine(plan, BUCKET_IDS[stream.plan_bucket], stream.plan_line, stream)
  }
  return plan
}

/**
 * Recurring bills on no line, for the user to place or skip: ones Jev said fit no line, or
 * hasn't judged yet.
 */
export function unplacedBills(streams: RecurringStream[]): RecurringStream[] {
  return streams.filter(
    (stream) =>
      !stream.is_inflow &&
      !stream.plan_line &&
      !(stream.category_detailed && NOT_PLAN_MONEY.has(stream.category_detailed)),
  )
}

/** Adds a recurring bill to the named line as a monthly item, adding the line if it's missing. */
function addToLine(plan: PlanDraft, bucket: BucketId, lineName: string, stream: RecurringStream) {
  let line = plan[bucket].find((candidate) => candidate.name === lineName)
  if (!line) {
    line = { name: lineName, amount: null, items: [], fromPaycheck: false }
    plan[bucket].push(line)
  }
  line.items.push(recurringItem(stream))
}

export function recurringItem(stream: RecurringStream): PlanItemDraft {
  return {
    name: recurringLabel(stream),
    amount: toMonthlyAmount(yourAmount(stream), stream.frequency),
    streamId: stream.stream_id,
  }
}

/** How the recurring payments changed between two loads, such as after the user answered some. */
export interface RecurringChanges {
  /** Payments that count as recurring now, like a candidate the user confirmed. */
  added: RecurringStream[]
  /** Payments that no longer count, like one the user said doesn't repeat, by stream id. */
  removed: Set<string>
  /** Monthly amounts that changed, like after the user set how often a bill is paid. */
  amounts: Map<string, { from: number; to: number }>
}

export function recurringChanges(
  before: RecurringStream[],
  after: RecurringStream[],
): RecurringChanges {
  const previous = new Map(before.map((stream) => [stream.stream_id, stream]))
  const current = new Set(after.map((stream) => stream.stream_id))
  const changes: RecurringChanges = {
    added: [],
    removed: new Set(before.map((stream) => stream.stream_id).filter((id) => !current.has(id))),
    amounts: new Map(),
  }
  for (const stream of after) {
    const old = previous.get(stream.stream_id)
    if (!old) {
      changes.added.push(stream)
      continue
    }
    const from = recurringItem(old).amount ?? 0
    const to = recurringItem(stream).amount ?? 0
    if (from !== to) changes.amounts.set(stream.stream_id, { from, to })
  }
  return changes
}
