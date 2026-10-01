/** The stretch of time the spending card covers. */
export type SpendingPeriod = 'THIS_MONTH' | 'LAST_MONTH' | 'LAST_30_DAYS' | 'LAST_90_DAYS'

export const SPENDING_PERIODS: { value: SpendingPeriod; label: string }[] = [
  { value: 'THIS_MONTH', label: 'This month' },
  { value: 'LAST_MONTH', label: 'Last month' },
  { value: 'LAST_30_DAYS', label: 'Last 30 days' },
  { value: 'LAST_90_DAYS', label: 'Last 90 days' },
]

/** First and last day, both included, as YYYY-MM-DD in the user's own time zone. */
export interface DateRange {
  start: string
  end: string
}

/**
 * The days a period covers, counted from the user's today, so a month starts at their
 * midnight. Rolling periods end today; months run to their last day.
 */
export function periodRange(period: SpendingPeriod, today = new Date()): DateRange {
  const year = today.getFullYear()
  const month = today.getMonth()
  switch (period) {
    case 'THIS_MONTH':
      return {
        start: isoDate(new Date(year, month, 1)),
        end: isoDate(new Date(year, month + 1, 0)),
      }
    case 'LAST_MONTH':
      return {
        start: isoDate(new Date(year, month - 1, 1)),
        end: isoDate(new Date(year, month, 0)),
      }
    case 'LAST_30_DAYS':
      return rolling(today, 30)
    case 'LAST_90_DAYS':
      return rolling(today, 90)
  }
}

/** How the card names what it's showing: "October", "September", "the last 30 days". */
export function periodName(period: SpendingPeriod, today = new Date()): string {
  const month = (offset: number) =>
    new Intl.DateTimeFormat('en-US', { month: 'long' }).format(
      new Date(today.getFullYear(), today.getMonth() + offset, 1),
    )
  switch (period) {
    case 'THIS_MONTH':
      return month(0)
    case 'LAST_MONTH':
      return month(-1)
    case 'LAST_30_DAYS':
      return 'the last 30 days'
    case 'LAST_90_DAYS':
      return 'the last 90 days'
  }
}

const STORAGE_KEY = 'moneyBuckets.spendingPeriod'

/** The period the user last picked, or this month. Storage can be unavailable; that's fine. */
export function recallPeriod(): SpendingPeriod {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return SPENDING_PERIODS.some((period) => period.value === stored)
      ? (stored as SpendingPeriod)
      : 'THIS_MONTH'
  } catch {
    return 'THIS_MONTH'
  }
}

export function rememberPeriod(period: SpendingPeriod) {
  try {
    localStorage.setItem(STORAGE_KEY, period)
  } catch {
    // The choice just won't carry over to the next visit.
  }
}

function rolling(today: Date, days: number): DateRange {
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1))
  return { start: isoDate(start), end: isoDate(today) }
}

function isoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}
