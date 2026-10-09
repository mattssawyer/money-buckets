import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import SpendingPlanPage from '../SpendingPlanPage.vue'
import { getSpendingPlan, type SpendingPlanResponse } from '../../api/SpendingPlanService'

vi.mock('../../components/UserMenu.vue', () => ({ default: { template: '<div />' } }))

vi.mock('../../api/SpendingPlanService', () => ({
  getSpendingPlan: vi.fn(),
}))

// The dialog's editor is covered by its own tests; here it only needs to report a save.
vi.mock('../../components/SpendingPlanSetup.vue', () => ({
  default: {
    name: 'SpendingPlanSetup',
    props: ['saved', 'replacing', 'focusBuffer'],
    emits: ['saved'],
    template: '<div class="setup-stub" />',
  },
}))

enableAutoUnmount(afterEach)

const savedResponse: SpendingPlanResponse = {
  account_id: 'checking',
  take_home: 5200,
  gross_pay: null,
  fixed_cost_buffer_percent: 15,
  updated_at: '2026-09-22T19:30:00Z',
  lines: [
    {
      bucket: 'FIXED_COSTS',
      name: 'Rent/mortgage',
      amount: 1450,
      from_paycheck: false,
      percent_of_gross: null,
      items: [],
    },
  ],
}

function mountPage() {
  return mount(SpendingPlanPage, {
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: {
        AppSidebar: true,
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
      },
    },
  })
}

function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAll('button').find((element) => element.text() === label)
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getSpendingPlan).mockResolvedValue(null)
})

describe('spending plan page', () => {
  it('shows a loading state until the saved plan arrives', async () => {
    const wrapper = mountPage()
    expect(wrapper.find('[aria-label="Loading your spending plan"]').exists()).toBe(true)

    await flushPromises()

    expect(wrapper.find('[aria-label="Loading your spending plan"]').exists()).toBe(false)
  })

  it('links to the accounts the plan is built from', async () => {
    vi.mocked(getSpendingPlan).mockResolvedValue(null)
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('.accounts-link').text()).toBe('Tracked accounts')
    expect(wrapper.get('.accounts-link').attributes('href')).toBe('/accounts')
  })

  it('offers setup when no plan has been saved', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await button(wrapper, 'Get started').trigger('click')

    const dialog = wrapper.getComponent({ name: 'Dialog' })
    expect(dialog.props('visible')).toBe(true)
    expect(dialog.props('header')).toBe('Create your spending plan')
    const setup = wrapper.getComponent({ name: 'SpendingPlanSetup' })
    expect(setup.props('saved')).toBeUndefined()
    expect(setup.props('replacing')).toBe(false)
  })

  it('closes the setup dialog when you click the mask', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await button(wrapper, 'Get started').trigger('click')
    const dialog = wrapper.getComponent({ name: 'Dialog' })
    expect(dialog.props('dismissableMask')).toBe(true)

    dialog.vm.$emit('update:visible', false)
    await wrapper.vm.$nextTick()
    expect(wrapper.getComponent({ name: 'Dialog' }).props('visible')).toBe(false)
  })

  it('shows the overview after a plan is saved', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await button(wrapper, 'Get started').trigger('click')

    wrapper.getComponent({ name: 'SpendingPlanSetup' }).vm.$emit('saved', {
      accountId: 'checking',
      takeHome: 4000,
      bufferPercent: 15,
      updatedAt: '2026-09-22T19:30:00Z',
      plan: { fixedCosts: [], investments: [], savings: [] },
    })
    await flushPromises()

    expect(wrapper.getComponent({ name: 'Dialog' }).props('visible')).toBe(false)
    expect(wrapper.get('[aria-label="Plan income"]').text()).toBe('$4,000')
    expect(wrapper.text()).not.toContain('Create your spending plan')
  })

  it('shows a saved plan and edits it without a new setup', async () => {
    vi.mocked(getSpendingPlan).mockResolvedValue(savedResponse)
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[aria-label="Plan income"]').text()).toBe('$5,200')

    await button(wrapper, 'Edit plan').trigger('click')

    expect(wrapper.getComponent({ name: 'Dialog' }).props('header')).toBe('Edit your spending plan')
    const setup = wrapper.getComponent({ name: 'SpendingPlanSetup' })
    expect(setup.props('saved')).toMatchObject({ accountId: 'checking', takeHome: 5200 })
    expect(setup.props('replacing')).toBe(false)
    expect(setup.props('focusBuffer')).toBe(false)
  })

  it('edits the plan at the buffer when the buffer is clicked', async () => {
    vi.mocked(getSpendingPlan).mockResolvedValue(savedResponse)
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('[aria-label="Change the miscellaneous buffer"]').trigger('click')

    expect(wrapper.getComponent({ name: 'Dialog' }).props('header')).toBe('Edit your spending plan')
    const setup = wrapper.getComponent({ name: 'SpendingPlanSetup' })
    expect(setup.props('saved')).toMatchObject({ bufferPercent: 15 })
    expect(setup.props('focusBuffer')).toBe(true)
  })

  it('starts a new setup that replaces the saved plan', async () => {
    vi.mocked(getSpendingPlan).mockResolvedValue(savedResponse)
    const wrapper = mountPage()
    await flushPromises()

    await button(wrapper, 'Start over').trigger('click')

    expect(wrapper.getComponent({ name: 'Dialog' }).props('header')).toBe(
      'Start a new spending plan',
    )
    const setup = wrapper.getComponent({ name: 'SpendingPlanSetup' })
    expect(setup.props('saved')).toBeUndefined()
    expect(setup.props('replacing')).toBe(true)
  })

  it('lets you retry when the saved plan fails to load', async () => {
    vi.mocked(getSpendingPlan).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t load your spending plan.')
    expect(wrapper.text()).not.toContain('Get started')

    vi.mocked(getSpendingPlan).mockResolvedValue(savedResponse)
    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()

    expect(wrapper.get('[aria-label="Plan income"]').text()).toBe('$5,200')
  })
})
