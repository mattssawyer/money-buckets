import type { Bucket, SpendingByBucket } from '../api/PlaidService'
import {
  BUCKET_STYLES,
  categoryLabel,
  detailedCategoryLabel,
  transactionLabel,
  yourAmount,
} from '../api/plaidLabels'
import { isoDate, type DateRange } from '../spending/period'
import { roundCents } from './money'

/** How many whole months of spending the user can look back over. */
export const HISTORY_MONTHS = [3, 6, 12] as const
export type HistoryMonths = (typeof HISTORY_MONTHS)[number]

/**
 * The last `months` whole calendar months, ending with last month. This month is left out: it
 * isn't over, so it would pull every average down.
 */
export function historyRange(months: HistoryMonths, today = new Date()): DateRange {
  const year = today.getFullYear()
  const month = today.getMonth()
  return {
    start: isoDate(new Date(year, month - months, 1)),
    end: isoDate(new Date(year, month, 0)),
  }
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

/** The order buckets are listed in, following the plan, with what isn't sorted yet last. */
const BUCKET_ORDER: Bucket[] = ['FIXED_COSTS', 'INVESTMENTS', 'SAVINGS', 'GUILT_FREE', 'UNSORTED']

/**
 * Buckets listed as one total. Guilt-free spending is what's left after the plan's lines, so
 * what it went on doesn't help choose an amount; neither does spending that isn't sorted yet.
 */
const UNDIVIDED: Partial<Record<Bucket, string>> = {
  GUILT_FREE: 'All guilt-free spending',
  UNSORTED: 'Everything not sorted yet',
}

/**
 * Spending in every bucket, regrouped by plan line and payee with monthly averages. Spending at
 * payees on no line is listed by Plaid category instead, so costs the plan has no line for yet
 * can be found.
 */
export function spendingHistory(spending: SpendingByBucket): SpendingHistory {
  const transactions = spending.buckets.flatMap((bucket) =>
    bucket.categories.flatMap((category) => category.transactions),
  )
  const months = monthsBetween(
    transactions.reduce(
      (earliest, transaction) => (transaction.date < earliest ? transaction.date : earliest),
      spending.end,
    ),
    spending.end,
  )
  if (transactions.length === 0) return { months: [], groups: [] }

  const groups = new Map<
    Bucket,
    Map<string, { label: string; onLine: boolean; payees: Map<string, number> }>
  >()
  for (const bucket of spending.buckets) {
    const categories = groups.get(bucket.bucket) ?? new Map()
    groups.set(bucket.bucket, categories)
    for (const transaction of bucket.categories.flatMap((category) => category.transactions)) {
      const primary = transaction.category ?? 'UNCATEGORIZED'
      const detailed = transaction.category_detailed
      const whole = UNDIVIDED[bucket.bucket]
      const line = whole ? null : transaction.plan_line
      const category = whole
        ? `${bucket.bucket}/all`
        : line
          ? `${bucket.bucket}/line/${line}`
          : `${bucket.bucket}/category/${primary}/${detailed ?? ''}`
      const payee = transactionLabel(transaction)
      const amount = yourAmount(transaction)
      const found = categories.get(category) ?? {
        label:
          whole ??
          line ??
          (detailed ? detailedCategoryLabel(primary, detailed) : categoryLabel(primary)),
        onLine: line != null,
        payees: new Map<string, number>(),
      }
      categories.set(category, found)
      found.payees.set(payee, (found.payees.get(payee) ?? 0) + amount)
    }
  }

  const perMonth = (total: number) => roundCents(total / months.length)
  // Refunds can net a payee or a category to nothing; there's then nothing to show for it.
  const spent = (entry: { average: number }) => entry.average > 0
  const largestFirst = (a: { average: number }, b: { average: number }) => b.average - a.average

  return {
    months,
    groups: BUCKET_ORDER.flatMap((bucket) => {
      const categories = groups.get(bucket)
      return categories ? [[bucket, categories] as const] : []
    })
      .map(([bucket, categories]) => {
        const listed = [...categories]
          .map(([key, category]) => ({
            key,
            label: category.label,
            onLine: category.onLine,
            average: perMonth(sum([...category.payees.values()])),
            payees: [...category.payees]
              .map(([name, total]) => ({
                key: payeeKey(key, name),
                name,
                average: perMonth(total),
              }))
              .filter(spent)
              .sort(largestFirst),
          }))
          .filter(spent)
          .sort((a, b) => Number(b.onLine) - Number(a.onLine) || largestFirst(a, b))
        return {
          key: bucket,
          label: BUCKET_STYLES[bucket].label,
          average: roundCents(sum(listed.map((category) => category.average))),
          categories: listed,
        }
      })
      .filter((group) => group.categories.length > 0),
  }
}

/**
 * What the user spends in a month on each plan line, by the line's name, in whole dollars: an
 * average isn't exact to the cent. Lines with no spending are left out.
 */
export function lineAverages(history: SpendingHistory): Map<string, number> {
  const averages = new Map<string, number>()
  for (const category of history.groups.flatMap((group) => group.categories)) {
    if (!category.onLine) continue
    averages.set(category.label, (averages.get(category.label) ?? 0) + category.average)
  }
  return new Map(
    [...averages]
      .map(([line, average]) => [line, Math.round(average)] as const)
      .filter(([, average]) => average > 0),
  )
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

function payeeKey(category: string, payee: string) {
  return `${category}|${payee}`
}

/** Every month from the one `start` is in to the one `end` is in, as YYYY-MM. */
function monthsBetween(start: string, end: string): string[] {
  const months: string[] = []
  let [year, month] = start.split('-').map(Number) as [number, number]
  const last = end.slice(0, 7)
  for (;;) {
    const current = `${year}-${String(month).padStart(2, '0')}`
    if (current > last) return months
    months.push(current)
    month += 1
    if (month > 12) {
      month = 1
      year += 1
    }
  }
}

function sum(amounts: number[]): number {
  return amounts.reduce((total, amount) => total + amount, 0)
}
