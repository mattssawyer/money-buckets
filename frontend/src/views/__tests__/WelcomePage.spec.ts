import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { createMemoryHistory, createRouter } from 'vue-router'
import WelcomePage from '../WelcomePage.vue'
import {
  getAccounts,
  getLinkedItems,
  type PlaidAccount,
  type PlaidItem,
} from '../../api/PlaidService'
import { getSpendingPlan } from '../../api/SpendingPlanService'
import { finishOnboarding } from '../../api/UserService'
import { linkItem } from '../../plaid/plaidLink'

vi.mock('../../components/UserMenu.vue', () => ({ default: { template: '<div />' } }))

vi.mock('../../api/PlaidService', () => ({
  addInvestments: vi.fn<() => Promise<never>>(),
  getAccounts: vi.fn<() => Promise<PlaidAccount[]>>(),
  getLinkedItems: vi.fn<() => Promise<PlaidItem[]>>(),
  removeItem: vi.fn<() => Promise<void>>(),
  updateAccountTracking: vi.fn<() => Promise<PlaidAccount>>(),
}))

vi.mock('../../api/SpendingPlanService', () => ({
  getSpendingPlan: vi.fn<() => Promise<null>>(),
}))

vi.mock('../../api/UserService', () => ({
  getCurrentUser: vi.fn<() => Promise<{ onboarded: boolean }>>(),
  finishOnboarding: vi.fn<() => Promise<{ onboarded: boolean }>>(),
}))

vi.mock('../../plaid/plaidLink', () => ({
  closePlaidLink: vi.fn<() => void>(),
  linkItem: vi.fn<typeof linkItem>(),
  openPlaidLink: vi.fn<() => Promise<string | null>>(),
}))

// The plan editor is covered by its own tests; here it only needs to open and report a save.
vi.mock('../../components/SpendingPlanDialog.vue', () => ({
  default: {
    name: 'SpendingPlanDialog',
    props: ['visible', 'header', 'saved', 'replacing', 'focusBuffer'],
    emits: ['update:visible', 'saved'],
    template: '<div class="setup-stub" />',
  },
}))

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const chase: PlaidItem = {
  item_id: 'chase-item',
  institution_name: 'Chase',
  investments: false,
  investments_available: false,
  reconnect: null,
}

function account(name: string, type = 'depository', subtype = 'checking'): PlaidAccount {
  return {
    account_id: name.toLowerCase(),
    balances: {
      available: null,
      current: 1200,
      iso_currency_code: 'USD',
      limit: null,
    },
    mask: '1234',
    name,
    official_name: null,
    subtype,
    type,
    trackable: type !== 'investment',
    tracks_spending: type !== 'investment',
    counts_in_net_worth: true,
    share_percent: 100,
  }
}

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<p>Home</p>' } },
      { path: '/welcome', name: 'welcome', component: WelcomePage },
    ],
  })
  await router.push(path)
  const wrapper = mount(WelcomePage, {
    global: {
      plugins: [router, [PrimeVue, { unstyled: true }]],
      stubs: { RouterLink: { props: ['to'], template: '<a><slot /></a>' } },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAll('button').find((element) => element.text() === label)
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}

function heading(wrapper: VueWrapper) {
  return wrapper.find('h1').text()
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getLinkedItems).mockResolvedValue([])
  vi.mocked(getAccounts).mockResolvedValue([])
  vi.mocked(getSpendingPlan).mockResolvedValue(null)
  vi.mocked(finishOnboarding).mockResolvedValue({ onboarded: true })
})

describe('welcome page', () => {
  it('starts by connecting a bank, and no later step opens without one', async () => {
    const { wrapper } = await mountAt('/welcome?step=plan')

    expect(heading(wrapper)).toBe('Connect your bank accounts')
    expect(wrapper.find('[aria-current="step"]').text()).toContain('Connect banks')
    expect(wrapper.findAll('button').some((element) => element.text() === 'Continue')).toBe(false)
  })

  it('lists a bank once it’s connected and goes on to choosing accounts', async () => {
    vi.mocked(linkItem).mockResolvedValue({ item_id: 'chase-item', same_institution: [] })
    const { wrapper, router } = await mountAt('/welcome')

    vi.mocked(getLinkedItems).mockResolvedValue([chase])
    vi.mocked(getAccounts).mockResolvedValue([account('Checking')])
    await button(wrapper, 'Connect a bank').trigger('click')
    await flushPromises()

    expect(linkItem).toHaveBeenCalledWith({})
    expect(wrapper.find('[aria-label="Connected banks"]').text()).toContain('Chase')

    await button(wrapper, 'Continue').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.step).toBe('accounts')
    expect(heading(wrapper)).toBe('Choose what counts')
    expect(wrapper.text()).toContain('Checking')
  })

  it('stays on connecting when the user closes Plaid without finishing', async () => {
    vi.mocked(linkItem).mockResolvedValue(null)
    const { wrapper } = await mountAt('/welcome')

    await button(wrapper, 'Connect a bank').trigger('click')
    await flushPromises()

    expect(heading(wrapper)).toBe('Connect your bank accounts')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('says so when a bank can’t be connected', async () => {
    vi.mocked(linkItem).mockRejectedValue(new Error('Plaid Link failed'))
    const { wrapper } = await mountAt('/welcome')

    await button(wrapper, 'Connect a bank').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t connect that account. Please try again.')
  })

  it('leaves the plan for later', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([chase])
    const { wrapper, router } = await mountAt('/welcome?step=plan')

    expect(heading(wrapper)).toBe('Your spending plan')
    await button(wrapper, 'Set it up later').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.step).toBe('investments')
    expect(heading(wrapper)).toBe('Add investment accounts')
  })

  it('goes on to investments once the plan is saved', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([chase])
    const { wrapper, router } = await mountAt('/welcome?step=plan')

    await button(wrapper, 'Make it now').trigger('click')
    const dialog = wrapper.findComponent({ name: 'SpendingPlanDialog' })
    expect(dialog.props('visible')).toBe(true)

    dialog.vm.$emit('saved', {})
    await flushPromises()

    expect(router.currentRoute.value.query.step).toBe('investments')
  })

  it('finishes without investments and opens Home', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([chase])
    const { wrapper, router } = await mountAt('/welcome?step=investments')

    await button(wrapper, 'Skip and finish').trigger('click')
    await flushPromises()

    expect(finishOnboarding).toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('home')
  })

  it('shows that setup is done for a moment before Home', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }))
    vi.mocked(getLinkedItems).mockResolvedValue([chase])
    const { wrapper, router } = await mountAt('/welcome?step=investments')
    vi.useFakeTimers()

    await button(wrapper, 'Skip and finish').trigger('click')
    await vi.advanceTimersByTimeAsync(0)

    expect(heading(wrapper)).toBe('You’re all set')
    expect(router.currentRoute.value.name).toBe('welcome')

    await vi.advanceTimersByTimeAsync(1400)

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('links an investment account and lists it', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([chase])
    vi.mocked(linkItem).mockResolvedValue({ item_id: 'fidelity-item', same_institution: [] })
    const { wrapper } = await mountAt('/welcome?step=investments')

    vi.mocked(getAccounts).mockResolvedValue([account('Roth IRA', 'investment', 'roth')])
    await button(wrapper, 'Link investment account').trigger('click')
    await flushPromises()

    expect(linkItem).toHaveBeenCalledWith({ investments: true })
    expect(wrapper.find('[aria-label="Investment accounts"]').text()).toContain('Roth IRA')
    expect(button(wrapper, 'Finish').exists()).toBe(true)
  })

  it('stays put and says so when finishing fails', async () => {
    vi.mocked(getLinkedItems).mockResolvedValue([chase])
    vi.mocked(finishOnboarding).mockRejectedValue(new Error('Server unavailable'))
    const { wrapper, router } = await mountAt('/welcome?step=investments')

    await button(wrapper, 'Skip and finish').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('welcome')
    expect(wrapper.text()).toContain('We couldn’t finish setting up. Please try again.')
  })
})
