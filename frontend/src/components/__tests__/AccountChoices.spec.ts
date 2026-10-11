import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import AccountChoices from '../AccountChoices.vue'
import {
  updateAccountTracking,
  type AccountTracking,
  type PlaidAccount,
} from '../../api/PlaidService'

vi.mock('../../api/PlaidService', () => ({
  updateAccountTracking:
    vi.fn<(accountId: string, tracking: AccountTracking) => Promise<PlaidAccount>>(),
}))

enableAutoUnmount(afterEach)

function account(name: string, type: string, subtype: string, trackable: boolean): PlaidAccount {
  return {
    account_id: name.toLowerCase(),
    balances: { available: null, current: 100, iso_currency_code: 'USD', limit: null },
    mask: null,
    name,
    official_name: null,
    subtype,
    type,
    trackable,
    tracks_spending: trackable,
    counts_in_net_worth: true,
    share_percent: 100,
  }
}

const checking = account('Checking', 'depository', 'checking', true)
const cd = account('Rainy day CD', 'depository', 'cd', false)

/** Mounted as v-model would use it, so changes come back as the new accounts. */
function mountChoices() {
  const wrapper = mount(AccountChoices, {
    props: {
      accounts: [checking, cd],
      'onUpdate:accounts': (accounts: PlaidAccount[]) => wrapper.setProps({ accounts }),
    },
    global: { plugins: [[PrimeVue, { unstyled: true }]] },
  })
  return wrapper
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(updateAccountTracking).mockImplementation(async (accountId, tracking) => ({
    ...(accountId === checking.account_id ? checking : cd),
    ...tracking,
  }))
})

describe('account choices', () => {
  it('lists only the accounts spending can come from', () => {
    const wrapper = mountChoices()

    expect(wrapper.text()).toContain('Checking')
    expect(wrapper.text()).not.toContain('Rainy day CD')
  })

  it('saves a shared account at an even split and asks for the user’s share', async () => {
    const wrapper = mountChoices()

    await wrapper.find('[aria-label="Checking is shared"]').setValue(true)
    await flushPromises()

    expect(updateAccountTracking).toHaveBeenCalledWith('checking', {
      tracks_spending: true,
      counts_in_net_worth: true,
      share_percent: 50,
    })
    expect(wrapper.find('[aria-label="Your share of Checking, in percent"]').exists()).toBe(true)
  })
})
