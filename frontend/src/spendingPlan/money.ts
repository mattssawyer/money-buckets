import { formatMoney, formatWholeDollars } from '../money'

export function parseAmount(value: string): number | null {
  const cleaned = value.replace(/[^0-9.]/g, '')
  if (!cleaned) return null
  const parsed = Number.parseFloat(cleaned)
  return Number.isFinite(parsed) ? parsed : null
}

// A typed plan amount keeps the cents it was given: $3,200 or $126.10.
export function formatPlanAmount(amount: number): string {
  return amount % 1 === 0 ? formatWholeDollars(amount) : formatMoney(amount)
}

export function roundCents(value: number): number {
  return Math.round(value * 100) / 100
}
