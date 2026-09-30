import { describe, expect, it } from 'vitest'
import type { RecurringCandidate, RecurringStream } from '../api/PlaidService'
import { isConfirmedCandidate, withConfirmed } from './recurring'

const stream: RecurringStream = {
  stream_id: 'rent',
  account_id: 'checking',
  merchant_name: 'Landlord',
  description: 'RENT',
  amount: 1450,
  iso_currency_code: 'USD',
  frequency: 'MONTHLY',
  next_date: '2026-10-01',
  last_date: '2026-09-01',
  is_inflow: false,
  category: 'RENT_AND_UTILITIES',
  category_detailed: 'RENT_AND_UTILITIES_RENT',
  share_percent: 100,
  plan_bucket: null,
  plan_line: null,
}

function candidate(overrides: Partial<RecurringCandidate>): RecurringCandidate {
  return {
    ...stream,
    kind: 'BILL',
    merchant_key: 'netflix',
    probability: 0.9,
    status: 'CONFIRMED',
    ...overrides,
  }
}

describe('withConfirmed', () => {
  it('adds only confirmed candidates to Plaid’s streams', () => {
    const merged = withConfirmed(
      [stream],
      [
        candidate({ stream_id: 'yes', status: 'CONFIRMED' }),
        candidate({ stream_id: 'maybe', status: 'SUGGESTED' }),
        candidate({ stream_id: 'no', status: 'DISMISSED' }),
      ],
    )

    expect(merged.map((entry) => entry.stream_id)).toEqual(['rent', 'yes'])
    expect(merged.filter(isConfirmedCandidate).map((entry) => entry.stream_id)).toEqual(['yes'])
  })

  it('lists the soonest expected first, then the most recently paid, with missing dates last', () => {
    const merged = withConfirmed(
      [
        { ...stream, stream_id: 'later', next_date: '2026-10-20' },
        { ...stream, stream_id: 'overdue-old', next_date: null, last_date: '2026-06-01' },
        { ...stream, stream_id: 'undated', next_date: null, last_date: null },
      ],
      [
        candidate({ stream_id: 'soon', next_date: '2026-10-02' }),
        candidate({ stream_id: 'overdue-recent', next_date: null, last_date: '2026-08-15' }),
      ],
    )

    expect(merged.map((entry) => entry.stream_id)).toEqual([
      'soon',
      'later',
      'overdue-recent',
      'overdue-old',
      'undated',
    ])
  })
})
