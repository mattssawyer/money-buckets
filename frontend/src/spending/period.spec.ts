import { afterEach, describe, expect, it } from 'vitest'
import { periodName, periodRange, recallPeriod, rememberPeriod } from './period'

// The 1st of the month, when this month has nothing in it yet.
const FIRST = new Date(2026, 9, 1, 9, 30)

afterEach(() => localStorage.clear())

describe('periodRange', () => {
  it('covers the whole calendar month for this month and last month', () => {
    expect(periodRange('THIS_MONTH', FIRST)).toEqual({ start: '2026-10-01', end: '2026-10-31' })
    expect(periodRange('LAST_MONTH', FIRST)).toEqual({ start: '2026-09-01', end: '2026-09-30' })
  })

  it('counts rolling periods back from today, including it', () => {
    expect(periodRange('LAST_30_DAYS', FIRST)).toEqual({ start: '2026-09-02', end: '2026-10-01' })
    expect(periodRange('LAST_90_DAYS', FIRST)).toEqual({ start: '2026-07-04', end: '2026-10-01' })
  })

  it('crosses into the previous year in January', () => {
    expect(periodRange('LAST_MONTH', new Date(2027, 0, 15))).toEqual({
      start: '2026-12-01',
      end: '2026-12-31',
    })
  })
})

describe('periodName', () => {
  it('names months and rolling periods', () => {
    expect(periodName('THIS_MONTH', FIRST)).toBe('October')
    expect(periodName('LAST_MONTH', FIRST)).toBe('September')
    expect(periodName('LAST_30_DAYS', FIRST)).toBe('the last 30 days')
  })
})

describe('remembering the period', () => {
  it('starts on this month and remembers what the user picks', () => {
    expect(recallPeriod()).toBe('THIS_MONTH')
    rememberPeriod('LAST_30_DAYS')
    expect(recallPeriod()).toBe('LAST_30_DAYS')
  })

  it('ignores a stored value it does not know', () => {
    localStorage.setItem('moneyBuckets.spendingPeriod', 'LAST_YEAR')
    expect(recallPeriod()).toBe('THIS_MONTH')
  })
})
