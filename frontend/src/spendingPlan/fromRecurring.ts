import type { RecurringStream } from '../api/PlaidService'
import { recurringLabel, yourAmount } from '../api/plaidLabels'
import { roundCents } from './money'
import { PLAN_LINES, defaultPlan, type BucketId, type PlanDraft } from './plan'

type PlanLine = { [B in BucketId]: { bucket: B; line: (typeof PLAN_LINES)[B][number] } }[BucketId]

/**
 * Plaid detailed categories that count toward each line. Credit card payments are left out:
 * the purchases behind them already land in other buckets.
 */
const LINE_BY_DETAILED_CATEGORY: Record<string, PlanLine> = {
  RENT_AND_UTILITIES_RENT: { bucket: 'fixedCosts', line: 'Rent/mortgage' },
  LOAN_PAYMENTS_MORTGAGE_PAYMENT: { bucket: 'fixedCosts', line: 'Rent/mortgage' },
  RENT_AND_UTILITIES_GAS_AND_ELECTRICITY: { bucket: 'fixedCosts', line: 'Utilities' },
  RENT_AND_UTILITIES_WATER: { bucket: 'fixedCosts', line: 'Utilities' },
  RENT_AND_UTILITIES_SEWAGE_AND_WASTE_MANAGEMENT: { bucket: 'fixedCosts', line: 'Utilities' },
  RENT_AND_UTILITIES_OTHER_UTILITIES: { bucket: 'fixedCosts', line: 'Utilities' },
  GENERAL_SERVICES_INSURANCE: { bucket: 'fixedCosts', line: 'Insurance' },
  LOAN_PAYMENTS_CAR_PAYMENT: { bucket: 'fixedCosts', line: 'Car payment' },
  LOAN_PAYMENTS_STUDENT_LOAN_PAYMENT: { bucket: 'fixedCosts', line: 'Debt payments' },
  LOAN_PAYMENTS_PERSONAL_LOAN_PAYMENT: { bucket: 'fixedCosts', line: 'Debt payments' },
  LOAN_PAYMENTS_OTHER_PAYMENT: { bucket: 'fixedCosts', line: 'Debt payments' },
  FOOD_AND_DRINK_GROCERIES: { bucket: 'fixedCosts', line: 'Groceries' },
  GENERAL_MERCHANDISE_CLOTHING_AND_ACCESSORIES: { bucket: 'fixedCosts', line: 'Clothes' },
  RENT_AND_UTILITIES_TELEPHONE: { bucket: 'fixedCosts', line: 'Phone' },
  RENT_AND_UTILITIES_INTERNET_AND_CABLE: { bucket: 'fixedCosts', line: 'Internet' },
  ENTERTAINMENT_TV_AND_MOVIES: { bucket: 'fixedCosts', line: 'Subscriptions' },
  ENTERTAINMENT_MUSIC_AND_AUDIO: { bucket: 'fixedCosts', line: 'Subscriptions' },
  PERSONAL_CARE_GYMS_AND_FITNESS_CENTERS: { bucket: 'fixedCosts', line: 'Subscriptions' },
  // Plaid can't tell a Roth IRA from a brokerage account, so transfers go to the catch-all line.
  TRANSFER_OUT_INVESTMENT_AND_RETIREMENT_FUNDS: {
    bucket: 'investments',
    line: 'Other investments',
  },
  TRANSFER_OUT_SAVINGS: { bucket: 'savings', line: 'Emergency fund' },
}

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

/** The spreadsheet's lines for every bucket, broken down into the recurring bills that match. */
export function planFromRecurring(streams: RecurringStream[]): PlanDraft {
  const plan = defaultPlan()
  for (const stream of streams) {
    if (stream.is_inflow || !stream.category_detailed) continue
    const match = LINE_BY_DETAILED_CATEGORY[stream.category_detailed]
    if (!match) continue
    plan[match.bucket]
      .find((line) => line.name === match.line)
      ?.items.push({
        name: recurringLabel(stream),
        amount: toMonthlyAmount(yourAmount(stream), stream.frequency),
        streamId: stream.stream_id,
      })
  }
  return plan
}
