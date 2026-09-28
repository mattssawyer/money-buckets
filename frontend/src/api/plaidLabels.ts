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

export function firstPresent(...values: Array<string | null | undefined>) {
  return values.find((value) => value != null && value.trim() !== '')
}

export function transactionLabel(transaction: PlaidTransaction) {
  return firstPresent(transaction.merchant_name, transaction.name) ?? 'Transaction'
}

/** The user's part of an amount from a shared account; the whole amount otherwise. */
export function yourAmount(entry: { amount: number; share_percent: number }): number {
  if (entry.share_percent === 100) return entry.amount
  return Math.round(entry.amount * entry.share_percent) / 100
}

// Plaid reports money leaving the account as positive, which reads backwards in a ledger.
export function formatTransactionAmount(transaction: PlaidTransaction) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: transaction.iso_currency_code ?? 'USD',
    signDisplay: 'exceptZero',
  }).format(-transaction.amount)
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
