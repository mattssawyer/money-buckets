import type { PlaidTransaction, Bucket, RecurringStream } from './PlaidService'
import { PLAN_COLORS, PLAN_TITLES } from '../spendingPlan/plan'

// Same colors as the spending plan page. Unsorted is grey so it reads as not yet decided.
export const BUCKET_STYLES: Record<Bucket | 'NOT_COUNTED', { label: string; color: string }> = {
  FIXED_COSTS: { label: PLAN_TITLES.fixedCosts, color: PLAN_COLORS.fixedCosts },
  GUILT_FREE: { label: PLAN_TITLES.guiltFree, color: PLAN_COLORS.guiltFree },
  SAVINGS: { label: PLAN_TITLES.savings, color: PLAN_COLORS.savings },
  INVESTMENTS: { label: PLAN_TITLES.investments, color: PLAN_COLORS.investments },
  UNSORTED: { label: 'Not sorted yet', color: '#9e9e9e' },
  NOT_COUNTED: { label: 'Not counted', color: '#cfcfcf' },
}

// Money coming in sits outside the plan's buckets, so it gets the same quiet grey as money
// that isn't counted.
const MONEY_IN_COLOR = BUCKET_STYLES.NOT_COUNTED.color

/**
 * The bucket a transaction is shown under. Money coming in never gets one, since it isn't
 * spending, so it's labelled for what it is instead of as waiting to be sorted.
 */
export function bucketStyle(transaction: PlaidTransaction): { label: string; color: string } {
  if (transaction.excluded) return { ...BUCKET_STYLES.NOT_COUNTED, label: 'Excluded' }
  if (transaction.bucket) return BUCKET_STYLES[transaction.bucket]
  if (transaction.category === 'INCOME') return { label: 'Income', color: MONEY_IN_COLOR }
  if (transaction.category === 'TRANSFER_IN') return { label: 'Transfer in', color: MONEY_IN_COLOR }
  return BUCKET_STYLES.UNSORTED
}

// Plaid's primary personal finance categories, minus the incoming ones the server leaves out
// of spending.
const CATEGORY_LABELS: Record<string, string> = {
  BANK_FEES: 'Bank fees',
  ENTERTAINMENT: 'Entertainment',
  FOOD_AND_DRINK: 'Food & drink',
  GENERAL_MERCHANDISE: 'Shopping',
  GENERAL_SERVICES: 'Services',
  GOVERNMENT_AND_NON_PROFIT: 'Government & charity',
  HOME_IMPROVEMENT: 'Home improvement',
  LOAN_PAYMENTS: 'Loan payments',
  MEDICAL: 'Medical',
  PERSONAL_CARE: 'Personal care',
  RENT_AND_UTILITIES: 'Rent & utilities',
  TRANSPORTATION: 'Transportation',
  TRANSFER_OUT: 'Transfers out',
  TRAVEL: 'Travel',
  UNCATEGORIZED: 'Uncategorized',
}

/** Categories a user can file spending under, in the order they're offered. */
export const SPENDING_CATEGORIES = Object.keys(CATEGORY_LABELS)
  .filter((category) => category !== 'UNCATEGORIZED')
  .sort((a, b) => categoryLabel(a).localeCompare(categoryLabel(b)))

export function firstPresent(...values: Array<string | null | undefined>) {
  return values.find((value) => value != null && value.trim() !== '')
}

export function transactionLabel(transaction: PlaidTransaction) {
  return firstPresent(transaction.merchant_name, transaction.name) ?? 'Transaction'
}

/**
 * The user's part of an amount from a shared account; the whole amount otherwise. Rounds the
 * size and keeps the sign, so a charge and its refund still cancel out.
 */
export function yourAmount(entry: { amount: number; share_percent: number }): number {
  if (entry.share_percent === 100) return entry.amount
  return (Math.sign(entry.amount) * Math.round(Math.abs(entry.amount) * entry.share_percent)) / 100
}

// Plaid reports money leaving the account as positive, which reads backwards in a ledger.
export function formatTransactionAmount(transaction: PlaidTransaction) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: transaction.iso_currency_code ?? 'USD',
    signDisplay: 'exceptZero',
  }).format(-transaction.amount)
}

const FREQUENCY_LABELS: Record<string, string> = {
  WEEKLY: 'Weekly',
  BIWEEKLY: 'Every 2 weeks',
  SEMI_MONTHLY: 'Twice a month',
  MONTHLY: 'Monthly',
  QUARTERLY: 'Every 3 months',
  SEMI_ANNUALLY: 'Twice a year',
  ANNUALLY: 'Yearly',
  UNKNOWN: 'Recurring',
}

/** The frequencies a user can pick for a payee, most frequent first. */
export const FREQUENCIES = Object.keys(FREQUENCY_LABELS).filter(
  (frequency) => frequency !== 'UNKNOWN',
)

// Plaid can add frequencies, so fall back to a readable form of whatever it sends.
export function frequencyLabel(frequency: string) {
  return (
    FREQUENCY_LABELS[frequency] ??
    frequency
      .toLowerCase()
      .split('_')
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ')
  )
}

/** A recurring stream's display name, falling back to its category when Plaid sent a blank name. */
export function recurringLabel(stream: RecurringStream) {
  return (
    firstPresent(stream.merchant_name, stream.description) ??
    (stream.category ? categoryLabel(stream.category) : undefined) ??
    'Recurring'
  )
}

// Plaid can add primary categories, so fall back to a readable form of whatever it sends.
export function categoryLabel(category: string) {
  return (
    CATEGORY_LABELS[category] ??
    category
      .toLowerCase()
      .split('_')
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ')
  )
}

/**
 * A detailed category as it reads under its primary one: FOOD_AND_DRINK_GROCERIES is
 * "Groceries" under Food & drink.
 */
export function detailedCategoryLabel(primary: string, detailed: string) {
  const within = detailed.startsWith(`${primary}_`) ? detailed.slice(primary.length + 1) : detailed
  const words = within.toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}
