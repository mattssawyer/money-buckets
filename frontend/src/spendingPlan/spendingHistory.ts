import type { Bucket } from '../api/PlaidService'
import type { CategoryHistoryResponse, SpendingHistoryResponse } from '../api/SpendingPlanService'
import { BUCKET_STYLES, categoryLabel, detailedCategoryLabel } from '../api/plaidLabels'
import { isoDate } from '../spending/period'

/** How many whole months of spending the user can look back over. */
export const HISTORY_MONTHS = [3, 6, 12] as const
export type HistoryMonths = (typeof HISTORY_MONTHS)[number]

/**
 * The month the user is in, as YYYY-MM in their own time zone. Spending history covers whole
 * months before it: this month isn't over, so it would pull every average down.
 */
export function currentMonth(today = new Date()): string {
  return isoDate(today).slice(0, 7)
}

export interface PayeeHistory {
  /** Unique across the history: the same payee under two categories is listed under each. */
  key: string
  name: string
  /** Spent per month on average. */
  average: number
}

/**
 * What a bucket's spending is listed by: a plan line, for payees Jev put on one, or a Plaid
 * category for the spending on no line.
 */
export interface CategoryHistory {
  key: string
  label: string
  /** Whether this is one of the plan's lines rather than a category of spending on no line. */
  onLine: boolean
  average: number
  /** Largest first. */
  payees: PayeeHistory[]
}

/** A bucket's spending: its plan lines first, then the categories on no line, each largest first. */
export interface CategoryGroup {
  key: string
  label: string
  average: number
  categories: CategoryHistory[]
}

export interface SpendingHistory {
  /**
   * The months averages are taken over, as YYYY-MM, oldest first. They start at the first month
   * with any spending, so an account linked recently isn't averaged over months it has no
   * transactions for.
   */
  months: string[]
  groups: CategoryGroup[]
}

/** How the buckets listed as one total read. */
const UNDIVIDED: Partial<Record<Bucket, string>> = {
  GUILT_FREE: 'All guilt-free spending',
  UNSORTED: 'Everything not sorted yet',
}

/** The server's spending history, labelled for reading. */
export function labelHistory(history: SpendingHistoryResponse): SpendingHistory {
  return {
    months: history.months,
    groups: history.groups.map((group) => ({
      key: group.bucket,
      label: BUCKET_STYLES[group.bucket].label,
      average: group.average,
      categories: group.categories.map((category) => ({
        key: category.key,
        label: categoryHistoryLabel(group.bucket, category),
        onLine: category.kind === 'LINE',
        average: category.average,
        payees: category.payees,
      })),
    })),
  }
}

function categoryHistoryLabel(bucket: Bucket, category: CategoryHistoryResponse): string {
  if (category.kind === 'ALL') return UNDIVIDED[bucket] ?? BUCKET_STYLES[bucket].label
  if (category.kind === 'LINE') return category.line ?? ''
  const primary = category.category ?? 'UNCATEGORIZED'
  return category.category_detailed
    ? detailedCategoryLabel(primary, category.category_detailed)
    : categoryLabel(primary)
}

/**
 * The groups with a line, category or payee matching what the user typed. A category matching by its
 * own or its group's name keeps all its payees; otherwise only the payees that match.
 */
export function searchHistory(groups: CategoryGroup[], query: string): CategoryGroup[] {
  const text = query.trim().toLowerCase()
  if (!text) return groups
  const matches = (label: string) => label.toLowerCase().includes(text)
  return groups
    .map((group) => ({
      ...group,
      categories: group.categories
        .map((category) =>
          matches(group.label) || matches(category.label)
            ? category
            : { ...category, payees: category.payees.filter((payee) => matches(payee.name)) },
        )
        .filter((category) => category.payees.length > 0),
    }))
    .filter((group) => group.categories.length > 0)
}
