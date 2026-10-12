import type { BalancePoint } from '../api/InvestmentsService'
import { formatSignedMoney } from '../money'

export const RANGES = ['1M', '3M', 'YTD', '1Y', 'All'] as const
export type Range = (typeof RANGES)[number]

function isoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/** The same day `months` earlier, or that month's last day when it's shorter (Mar 31 → Feb 28). */
function monthsBefore(today: Date, months: number): Date {
  const start = new Date(today.getFullYear(), today.getMonth() - months, 1)
  const lastDay = new Date(start.getFullYear(), start.getMonth() + 1, 0).getDate()
  start.setDate(Math.min(today.getDate(), lastDay))
  return start
}

/** The first day a range covers, as YYYY-MM-DD, or undefined for all of history. */
export function rangeStart(range: Range, today: Date): string | undefined {
  switch (range) {
    case '1M':
      return isoDate(monthsBefore(today, 1))
    case '3M':
      return isoDate(monthsBefore(today, 3))
    case 'YTD':
      return isoDate(new Date(today.getFullYear(), 0, 1))
    case '1Y':
      return isoDate(monthsBefore(today, 12))
    case 'All':
      return undefined
  }
}

export interface Change {
  amount: number
  /** Null when the range opened at zero, where a percentage means nothing. */
  percent: number | null
}

/**
 * Change between the first and last points, or null with fewer than two points. Percentage uses
 * the starting value's magnitude, and is null when that value is zero.
 */
export function changeOver(points: BalancePoint[]): Change | null {
  if (points.length < 2) return null
  const first = points[0]!.value
  const last = points[points.length - 1]!.value
  const amount = last - first
  return { amount, percent: first === 0 ? null : (amount / Math.abs(first)) * 100 }
}

export function latestValue(points: BalancePoint[]): number | null {
  return points.length ? points[points.length - 1]!.value : null
}

export function formatChange(change: Change): string {
  const amount = formatSignedMoney(change.amount)
  if (change.percent == null) return amount
  const percent = new Intl.NumberFormat('en-US', {
    maximumFractionDigits: 1,
    signDisplay: 'exceptZero',
  }).format(change.percent)
  return `${amount} (${percent}%)`
}

/** A day as "Sep 7", or "Sep 7, 2025" when the year matters. */
export function formatDay(date: string, withYear = false): string {
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    ...(withYear ? { year: 'numeric' } : {}),
  }).format(new Date(`${date}T00:00:00`))
}
