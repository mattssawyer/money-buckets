import { afterEach, describe, expect, it } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import SpendingPlanOverview from '../SpendingPlanOverview.vue'
import { defaultPlan } from '../../spendingPlan/plan'
import type { SavedPlan } from '../../spendingPlan/savedPlan'

enableAutoUnmount(afterEach)

const plan: SavedPlan = {
  accountId: 'checking',
  takeHome: 5200,
  grossPay: null,
  bufferPercent: 15,
  updatedAt: '2026-09-22T19:30:00Z',
  plan: {
    fixedCosts: [
      { name: 'Rent/mortgage', amount: 1450, items: [], fromPaycheck: false },
      {
        name: 'Subscriptions',
        amount: null,
        fromPaycheck: false,
        items: [
          { name: 'Netflix', amount: 15.49, streamId: 'netflix' },
          { name: 'Spotify', amount: 11.99, streamId: 'spotify' },
        ],
      },
      { name: 'Clothes', amount: null, items: [], fromPaycheck: false },
    ],
    investments: [
      { name: '401(k)', amount: 600, items: [], fromPaycheck: true },
      { name: 'Roth IRA', amount: null, items: [], fromPaycheck: false },
    ],
    savings: [{ name: 'Vacations', amount: 200, items: [], fromPaycheck: false }],
  },
}

function mountOverview(saved: SavedPlan = plan) {
  return mount(SpendingPlanOverview, {
    props: { plan: saved },
    global: { plugins: [[PrimeVue, { unstyled: true }]] },
  })
}

function button(wrapper: ReturnType<typeof mountOverview>, label: string) {
  const result = wrapper.findAll('button').find((element) => element.text() === label)
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}

describe('spending plan overview', () => {
  it('shows plan income with the paycheck contribution added back', () => {
    const wrapper = mountOverview()

    expect(wrapper.get('[aria-label="Plan income"]').text()).toBe('$5,800')
    expect(wrapper.text()).toContain('$5,200 take-home + $600 from your paycheck')
    expect(wrapper.text()).toContain('Saved Sep 22, 2026')
  })

  it('breaks income into the four buckets with shares and targets', () => {
    const wrapper = mountOverview()

    expect(wrapper.get('[aria-label="Fixed costs amount"]').text()).toBe('$1,699.10')
    expect(wrapper.get('[aria-label="Investments amount"]').text()).toBe('$600')
    expect(wrapper.get('[aria-label="Savings amount"]').text()).toBe('$200')
    expect(wrapper.get('[aria-label="Guilt-free spending amount"]').text()).toBe('$3,300.90')
    expect(wrapper.text()).toContain('Target 50–60%')
    expect(wrapper.text()).toContain('Target ~10%')
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toBe(
      'How income splits: Fixed costs 29%, Investments 10%, Savings 3%, Guilt-free spending 57%',
    )
    expect(wrapper.findAll('.split-segment')).toHaveLength(4)
  })

  it('lists planned lines with their breakdowns and skips blank ones', () => {
    const wrapper = mountOverview()
    const fixedCosts = wrapper.get('[aria-labelledby="overview-fixedCosts-heading"]')

    expect(fixedCosts.text()).toContain('Rent/mortgage')
    expect(fixedCosts.text()).toContain('Netflix')
    expect(fixedCosts.text()).toContain('$27.48')
    expect(fixedCosts.text()).not.toContain('Clothes')
    expect(wrapper.get('[aria-labelledby="overview-investments-heading"]').text()).toContain(
      'From paycheck',
    )
    expect(wrapper.get('[aria-labelledby="overview-investments-heading"]').text()).not.toContain(
      'Roth IRA',
    )
  })

  it('says when a paycheck line is a percent of gross pay', () => {
    const wrapper = mountOverview({
      ...plan,
      grossPay: 10000,
      plan: {
        ...plan.plan,
        investments: [
          { name: '401(k)', amount: 600, items: [], fromPaycheck: true, percentOfGross: 6 },
        ],
      },
    })

    expect(wrapper.get('[aria-labelledby="overview-investments-heading"]').text()).toContain(
      'From paycheck · 6% of gross pay',
    )
  })

  it('lists the miscellaneous buffer under fixed costs, unless it is zero', () => {
    const wrapper = mountOverview()
    const fixedCosts = wrapper.get('[aria-labelledby="overview-fixedCosts-heading"]')

    expect(fixedCosts.get('.buffer-line').text()).toBe('Miscellaneous buffer (15%) $221.62')
    expect(fixedCosts.get('.bucket-total').text()).toBe('$1,699.10')

    const withoutBuffer = mountOverview({ ...plan, bufferPercent: 0 })
    expect(withoutBuffer.find('.buffer-line').exists()).toBe(false)
    expect(withoutBuffer.get('[aria-label="Fixed costs amount"]').text()).toBe('$1,477.48')
  })

  it('opens the buffer for changing when it is clicked', async () => {
    const wrapper = mountOverview()

    await wrapper.get('[aria-label="Change the miscellaneous buffer"]').trigger('click')

    expect(wrapper.emitted('editBuffer')).toHaveLength(1)
  })

  it('flags fixed costs over target and a plan that exceeds income', () => {
    const wrapper = mountOverview({
      ...plan,
      takeHome: 2000,
      plan: {
        ...plan.plan,
        fixedCosts: [{ name: 'Rent/mortgage', amount: 2400, items: [], fromPaycheck: false }],
      },
    })

    expect(wrapper.get('[aria-label="Fixed costs amount"]').classes()).toContain('legend-over')
    expect(wrapper.text()).toContain('106% · over target')
    expect(wrapper.get('[aria-label="Guilt-free spending amount"]').text()).toBe('-$960')
    expect(wrapper.findAll('.split-segment')).toHaveLength(3)
  })

  it('asks for take-home pay when the plan has none', () => {
    const wrapper = mountOverview({ ...plan, takeHome: null, plan: defaultPlan() })

    expect(wrapper.text()).toContain('Add your take-home pay to see how it splits.')
    expect(wrapper.find('[role="img"]').exists()).toBe(false)
    expect(wrapper.findAll('.bucket-empty')).toHaveLength(3)
  })

  it('asks the page to edit or start over', async () => {
    const wrapper = mountOverview()

    await button(wrapper, 'Edit plan').trigger('click')
    await button(wrapper, 'Start over').trigger('click')

    expect(wrapper.emitted('edit')).toHaveLength(1)
    expect(wrapper.emitted('startOver')).toHaveLength(1)
  })
})
