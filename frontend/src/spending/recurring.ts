import type { RecurringCandidate, RecurringStream } from '../api/PlaidService'

/**
 * Plaid's recurring streams together with the candidates the user confirmed, soonest expected
 * first, then most recently paid. Suggested and dismissed candidates are left out.
 */
export function withConfirmed(
  streams: RecurringStream[],
  candidates: RecurringCandidate[],
): RecurringStream[] {
  return [...streams, ...candidates.filter((candidate) => candidate.status === 'CONFIRMED')].sort(
    (a, b) =>
      compareDates(a.next_date, b.next_date, 1) || compareDates(a.last_date, b.last_date, -1),
  )
}

export function isConfirmedCandidate(
  stream: RecurringStream,
): stream is RecurringCandidate & { status: 'CONFIRMED' } {
  return 'status' in stream && (stream as RecurringCandidate).status === 'CONFIRMED'
}

// ISO dates compare as strings. Order 1 is earliest first and -1 latest first; a missing date
// sorts last either way.
function compareDates(a: string | null, b: string | null, order: 1 | -1) {
  if (a === b) return 0
  if (a === null) return 1
  if (b === null) return -1
  return a < b ? -order : order
}
