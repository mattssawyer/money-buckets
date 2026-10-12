// How money reads in the app. Balances and transactions show cents; summaries round to whole
// dollars; signed amounts mark money in with + and out with −. Plan amounts have their own rule,
// in spendingPlan/money.ts.

const formatters = new Map<string, Intl.NumberFormat>()

function formatter(key: string, options: Intl.NumberFormatOptions) {
  let cached = formatters.get(key)
  if (!cached) {
    cached = new Intl.NumberFormat('en-US', { style: 'currency', ...options })
    formatters.set(key, cached)
  }
  return cached
}

/** "$1,234.56" */
export function formatMoney(amount: number, currency = 'USD'): string {
  return formatter(`cents:${currency}`, { currency }).format(amount)
}

/** "$1,235", rounded. */
export function formatWholeDollars(amount: number): string {
  return formatter('whole:USD', { currency: 'USD', maximumFractionDigits: 0 }).format(amount)
}

/** "+$12.00" or "−$4.50", and "$0.00" with no sign. */
export function formatSignedMoney(amount: number, currency = 'USD'): string {
  return formatter(`signed:${currency}`, { currency, signDisplay: 'exceptZero' }).format(amount)
}

/** "$1.2K", for chart axes. */
export function formatCompactMoney(amount: number): string {
  return formatter('compact:USD', {
    currency: 'USD',
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(amount)
}
