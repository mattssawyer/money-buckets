import { describe, expect, it } from 'vitest'
import { formatMoney, formatSignedMoney, formatWholeDollars } from './money'

describe('formatMoney', () => {
  it('always shows cents', () => {
    expect(formatMoney(1450)).toBe('$1,450.00')
    expect(formatMoney(15.49)).toBe('$15.49')
  })
})

describe('formatSignedMoney', () => {
  it('marks money in and out', () => {
    expect(formatSignedMoney(500)).toBe('+$500.00')
    expect(formatSignedMoney(-5.4)).toBe('-$5.40')
    expect(formatSignedMoney(0)).toBe('$0.00')
  })
})

describe('formatWholeDollars', () => {
  it('rounds to the dollar', () => {
    expect(formatWholeDollars(514.62)).toBe('$515')
  })
})
