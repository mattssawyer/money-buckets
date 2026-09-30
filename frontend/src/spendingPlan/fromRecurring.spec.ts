import { describe, expect, it } from 'vitest'
import type { RecurringStream } from '../api/PlaidService'
import type { SpendingPlanBucket } from '../api/SpendingPlanService'
import {
  estimateMonthlyTakeHome,
  planFromRecurring,
  toMonthlyAmount,
  unplacedBills,
} from './fromRecurring'
import { PLAN_LINES, defaultPlan, lineAmount } from './plan'

function stream(overrides: Partial<RecurringStream>): RecurringStream {
  return {
    stream_id: 'stream',
    account_id: 'checking',
    merchant_name: 'Payroll',
    description: 'PAYROLL',
    amount: -2400,
    iso_currency_code: 'USD',
    frequency: 'BIWEEKLY',
    next_date: '2026-09-25',
    last_date: '2026-09-11',
    is_inflow: true,
    category: 'INCOME',
    category_detailed: 'INCOME_WAGES',
    share_percent: 100,
    plan_bucket: null,
    plan_line: null,
    ...overrides,
  }
}

describe('toMonthlyAmount', () => {
  it('normalizes each paycheck cadence to a monthly number', () => {
    expect(toMonthlyAmount(500, 'WEEKLY')).toBe(2166.67)
    expect(toMonthlyAmount(-2400, 'BIWEEKLY')).toBe(5200)
    expect(toMonthlyAmount(2100, 'SEMI_MONTHLY')).toBe(4200)
    expect(toMonthlyAmount(1450, 'MONTHLY')).toBe(1450)
    expect(toMonthlyAmount(1200, 'ANNUALLY')).toBe(100)
    expect(toMonthlyAmount(80, 'UNKNOWN')).toBe(80)
  })
})

describe('estimateMonthlyTakeHome', () => {
  it('sums recurring deposits into monthly take-home', () => {
    expect(
      estimateMonthlyTakeHome([
        stream({ amount: -2400, frequency: 'BIWEEKLY' }),
        stream({
          stream_id: 'side',
          merchant_name: 'Consulting',
          amount: -400,
          frequency: 'MONTHLY',
        }),
      ]),
    ).toBe(5600)
  })

  it('leaves out transfers in, such as a housemate paying into a shared account', () => {
    expect(
      estimateMonthlyTakeHome([
        stream({ amount: -2400, frequency: 'BIWEEKLY' }),
        stream({
          stream_id: 'housemate',
          account_id: 'joint',
          merchant_name: 'Transfer from Sam',
          amount: -1200,
          frequency: 'MONTHLY',
          category: 'TRANSFER_IN',
          category_detailed: 'TRANSFER_IN_ACCOUNT_TRANSFER',
          share_percent: 50,
        }),
      ]),
    ).toBe(5200)
  })

  it('ignores bills and returns null when there are no deposits', () => {
    expect(
      estimateMonthlyTakeHome([
        stream({
          stream_id: 'rent',
          merchant_name: 'Landlord',
          amount: 1450,
          frequency: 'MONTHLY',
          is_inflow: false,
          category: 'RENT_AND_UTILITIES',
          category_detailed: 'RENT_AND_UTILITIES_RENT',
        }),
      ]),
    ).toBeNull()
  })
})

/** Where Jev put a payee's bills. */
function onLine(bucket: SpendingPlanBucket, line: string) {
  return { plan_bucket: bucket, plan_line: line }
}

describe('planFromRecurring', () => {
  function bill(overrides: Partial<RecurringStream>) {
    return stream({ frequency: 'MONTHLY', is_inflow: false, ...overrides })
  }

  function amounts(streams: RecurringStream[]) {
    return Object.fromEntries(
      planFromRecurring(streams).fixedCosts.map((row) => [row.name, lineAmount(row)]),
    )
  }

  it('always lists every spreadsheet line in order', () => {
    const plan = planFromRecurring([])
    expect(plan.fixedCosts.map((row) => row.name)).toEqual([...PLAN_LINES.fixedCosts])
    expect(plan.investments.map((row) => row.name)).toEqual([...PLAN_LINES.investments])
    expect(plan.savings.map((row) => row.name)).toEqual([...PLAN_LINES.savings])
    expect(plan).toEqual(defaultPlan())
  })

  it('fills in monthly amounts for the lines Jev put recurring bills on', () => {
    expect(
      amounts([
        bill({ stream_id: 'rent', amount: 1450, ...onLine('FIXED_COSTS', 'Rent/mortgage') }),
        bill({
          stream_id: 'phone',
          amount: 80,
          ...onLine('FIXED_COSTS', 'Phone'),
        }),
        bill({
          stream_id: 'insurance',
          amount: 1200,
          frequency: 'ANNUALLY',
          ...onLine('FIXED_COSTS', 'Insurance'),
        }),
      ]),
    ).toMatchObject({
      'Rent/mortgage': 1450,
      Phone: 80,
      Insurance: 100,
      Utilities: null,
      Groceries: null,
    })
  })

  it("counts a bill from a shared account at the user's share", () => {
    expect(
      amounts([
        bill({
          stream_id: 'rent',
          account_id: 'joint',
          amount: 2400,
          ...onLine('FIXED_COSTS', 'Rent/mortgage'),
          share_percent: 50,
        }),
      ]),
    ).toMatchObject({ 'Rent/mortgage': 1200 })
  })

  it('adds up several bills that belong to the same line', () => {
    expect(
      amounts([
        bill({
          stream_id: 'electric',
          amount: 90.1,
          ...onLine('FIXED_COSTS', 'Utilities'),
        }),
        bill({ stream_id: 'water', amount: 35.2, ...onLine('FIXED_COSTS', 'Utilities') }),
        bill({
          stream_id: 'netflix',
          amount: 15.49,
          ...onLine('FIXED_COSTS', 'Subscriptions'),
        }),
        bill({
          stream_id: 'spotify',
          amount: 11.99,
          ...onLine('FIXED_COSTS', 'Subscriptions'),
        }),
      ]),
    ).toMatchObject({ Utilities: 125.3, Subscriptions: 27.48 })
  })

  it('lists each bill as a monthly breakdown item', () => {
    const subscriptions = planFromRecurring([
      bill({
        stream_id: 'netflix',
        merchant_name: 'Netflix',
        amount: 15.49,
        ...onLine('FIXED_COSTS', 'Subscriptions'),
      }),
      bill({
        stream_id: 'gym',
        merchant_name: null,
        description: 'PLANET FITNESS',
        amount: 120,
        frequency: 'ANNUALLY',
        ...onLine('FIXED_COSTS', 'Subscriptions'),
      }),
    ]).fixedCosts.find((row) => row.name === 'Subscriptions')

    expect(subscriptions?.items).toEqual([
      { name: 'Netflix', amount: 15.49, streamId: 'netflix' },
      { name: 'PLANET FITNESS', amount: 10, streamId: 'gym' },
    ])
  })

  it('names a bill from its category when Plaid sent a blank name', () => {
    const rent = planFromRecurring([
      bill({
        merchant_name: '',
        description: '   ',
        amount: 1450,
        category: 'RENT_AND_UTILITIES',
        ...onLine('FIXED_COSTS', 'Rent/mortgage'),
      }),
    ]).fixedCosts.find((row) => row.name === 'Rent/mortgage')

    expect(rent?.items).toEqual([{ name: 'Rent & utilities', amount: 1450, streamId: 'stream' }])
  })

  it('leaves out bills on no line, and pay', () => {
    const plan = planFromRecurring([
      bill({ stream_id: 'doordash', amount: 40, category_detailed: 'FOOD_AND_DRINK_RESTAURANT' }),
      bill({ stream_id: 'unknown', amount: 20, category_detailed: null }),
      stream({ amount: -2400, ...onLine('FIXED_COSTS', 'Rent/mortgage') }),
    ])

    expect(plan).toEqual(defaultPlan())
  })

  it('adds a line the spreadsheet does not have, such as one from a saved plan', () => {
    const plan = planFromRecurring([
      bill({
        stream_id: 'trip',
        merchant_name: 'Japan fund',
        amount: 300,
        ...onLine('SAVINGS', 'Japan trip'),
      }),
    ])

    expect(plan.savings.at(-1)).toEqual({
      name: 'Japan trip',
      amount: null,
      fromPaycheck: false,
      items: [{ name: 'Japan fund', amount: 300, streamId: 'trip' }],
    })
  })

  it('puts investment and savings transfers in their buckets', () => {
    const plan = planFromRecurring([
      bill({
        stream_id: 'vanguard',
        merchant_name: 'Vanguard',
        amount: 250,
        frequency: 'BIWEEKLY',
        category: 'TRANSFER_OUT',
        ...onLine('INVESTMENTS', 'Other investments'),
      }),
      bill({
        stream_id: 'hysa',
        merchant_name: 'Ally',
        amount: 200,
        category: 'TRANSFER_OUT',
        ...onLine('SAVINGS', 'Emergency fund'),
      }),
    ])

    expect(plan.investments.find((row) => row.name === 'Other investments')?.items).toEqual([
      { name: 'Vanguard', amount: 541.67, streamId: 'vanguard' },
    ])
    expect(plan.savings.find((row) => row.name === 'Emergency fund')?.items).toEqual([
      { name: 'Ally', amount: 200, streamId: 'hysa' },
    ])
    expect(plan.fixedCosts.every((row) => row.items.length === 0)).toBe(true)
  })
})

describe('unplacedBills', () => {
  it('keeps bills on no line for the user to place, but not card payments or pay', () => {
    const rent = stream({
      stream_id: 'rent',
      is_inflow: false,
      amount: 1554.91,
      category_detailed: 'HOME_IMPROVEMENT_REPAIR_AND_MAINTENANCE',
    })
    const netflix = stream({
      stream_id: 'netflix',
      is_inflow: false,
      amount: 15.49,
      ...onLine('FIXED_COSTS', 'Subscriptions'),
    })
    const card = stream({
      stream_id: 'card',
      is_inflow: false,
      amount: 900,
      category_detailed: 'LOAN_PAYMENTS_CREDIT_CARD_PAYMENT',
    })

    expect(unplacedBills([rent, netflix, card, stream({ amount: -2400 })])).toEqual([rent])
  })
})
