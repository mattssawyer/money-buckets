import type { SpendingByBucket } from '../api/PlaidService'
import {
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

/** One transaction's part in the history: what was spent, where, and in which month. */
interface Entry {
  category: string
  payee: string
  /** Index into the history's months. */
  month: number
  amount: number
}

export interface PayeeHistory {
  /** Unique across the history: the same payee under two categories is listed under each. */
  key: string
  name: string
  /** Spent per month on average. */
  average: number
}

export interface CategoryHistory {
  key: string
  label: string
  average: number
  /** Largest first. */
  payees: PayeeHistory[]
}

/** A Plaid primary category and the detailed ones within it, largest first. */
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
  entries: Entry[]
}

/** What the user picked to add up: whole categories, and single payees within others. */
export interface Selection {
  categories: ReadonlySet<string>
  payees: ReadonlySet<string>
}

export interface SelectionHistory {
  average: number
  /** Spent in each of the history's months. */
  byMonth: number[]
}

/** Spending in every bucket, regrouped by category and payee with monthly averages. */
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
  if (transactions.length === 0) return { months: [], groups: [], entries: [] }

  const entries: Entry[] = []
  const groups = new Map<string, Map<string, { label: string; payees: Map<string, number> }>>()
  for (const transaction of transactions) {
    const primary = transaction.category ?? 'UNCATEGORIZED'
    const detailed = transaction.category_detailed
    const category = `${primary}/${detailed ?? ''}`
    const payee = transactionLabel(transaction)
    const amount = yourAmount(transaction)
    entries.push({ category, payee, month: months.indexOf(transaction.date.slice(0, 7)), amount })

    const categories = groups.get(primary) ?? new Map()
    groups.set(primary, categories)
    const found = categories.get(category) ?? {
      label: detailed ? detailedCategoryLabel(primary, detailed) : categoryLabel(primary),
      payees: new Map<string, number>(),
    }
    categories.set(category, found)
    found.payees.set(payee, (found.payees.get(payee) ?? 0) + amount)
  }

  const perMonth = (total: number) => roundCents(total / months.length)
  // Refunds can net a payee or a category to nothing; there's then nothing to show for it.
  const spent = (entry: { average: number }) => entry.average > 0
  const largestFirst = (a: { average: number }, b: { average: number }) => b.average - a.average

  return {
    months,
    entries,
    groups: [...groups]
      .map(([primary, categories]) => {
        const listed = [...categories]
          .map(([key, category]) => ({
            key,
            label: category.label,
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
          .sort(largestFirst)
        return {
          key: primary,
          label: categoryLabel(primary),
          average: roundCents(sum(listed.map((category) => category.average))),
          categories: listed,
        }
      })
      .filter((group) => group.categories.length > 0)
      .sort(largestFirst),
  }
}

/** What the selection adds up to; with nothing picked, all spending. */
export function selectionHistory(history: SpendingHistory, selection: Selection): SelectionHistory {
  const everything = selection.categories.size === 0 && selection.payees.size === 0
  const byMonth = history.months.map(() => 0)
  for (const entry of history.entries) {
    if (
      everything ||
      selection.categories.has(entry.category) ||
      selection.payees.has(payeeKey(entry.category, entry.payee))
    ) {
      byMonth[entry.month] = (byMonth[entry.month] ?? 0) + entry.amount
    }
  }
  return {
    average: history.months.length ? roundCents(sum(byMonth) / history.months.length) : 0,
    byMonth: byMonth.map(roundCents),
  }
}

/**
 * The groups with a category or payee matching what the user typed. A category matching by its
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

/** "Jul" for 2026-07. */
export function monthLabel(month: string): string {
  const [year, index] = month.split('-').map(Number)
  return new Intl.DateTimeFormat('en-US', { month: 'short' }).format(
    new Date(year ?? 0, (index ?? 1) - 1, 1),
  )
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
