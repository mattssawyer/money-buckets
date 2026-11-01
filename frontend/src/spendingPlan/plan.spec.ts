import { describe, expect, it } from 'vitest'
import { defaultPlan, lineAmount, type PlanLineDraft } from './plan'

function line(overrides: Partial<PlanLineDraft>): PlanLineDraft {
  return { name: 'Line', amount: null, items: [], fromPaycheck: false, ...overrides }
}

describe('lineAmount', () => {
  it('uses the typed amount until the line is broken down', () => {
    expect(lineAmount(line({ name: 'Rent', amount: 1450 }))).toBe(1450)
    expect(lineAmount(line({ name: 'Rent', amount: null }))).toBeNull()
  })

  it('sums breakdown items, counting blank ones as zero', () => {
    expect(
      lineAmount(
        line({
          name: 'Subscriptions',
          amount: 99,
          items: [
            { name: 'Netflix', amount: 15.49, streamId: null },
            { name: 'Spotify', amount: 11.99, streamId: null },
            { name: '', amount: null, streamId: null },
          ],
        }),
      ),
    ).toBe(27.48)
  })
})

describe('paycheck contributions', () => {
  it('starts with only the 401(k) marked as taken from the paycheck', () => {
    const draft = defaultPlan()
    expect(draft.investments.filter((row) => row.fromPaycheck).map((row) => row.name)).toEqual([
      '401(k)',
    ])
    expect([...draft.fixedCosts, ...draft.savings].some((row) => row.fromPaycheck)).toBe(false)
  })
})
