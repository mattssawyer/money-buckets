import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import SameInstitutionNotice from '../SameInstitutionNotice.vue'
import { removeItem } from '../../api/PlaidService'

vi.mock('../../api/PlaidService', () => ({
  removeItem: vi.fn(),
}))

const items = [
  {
    item_id: 'first',
    institution_name: 'Fidelity',
    investments: false,
    investments_available: true,
    reconnect: null,
  },
  {
    item_id: 'second',
    institution_name: 'Fidelity',
    investments: true,
    investments_available: true,
    reconnect: null,
  },
]

function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAll('button').find((element) => element.text() === label)
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}

beforeEach(() => vi.resetAllMocks())

describe('same institution notice', () => {
  it('retries only the connections it hasn’t removed yet', async () => {
    vi.mocked(removeItem)
      .mockResolvedValueOnce()
      .mockRejectedValueOnce(new Error('Plaid unavailable'))
      .mockResolvedValueOnce()
    const wrapper = mount(SameInstitutionNotice, {
      props: { items },
      global: { plugins: [[PrimeVue, { unstyled: true }]] },
    })

    await button(wrapper, 'Remove the older connection').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('We couldn’t remove the older connection.')
    expect(wrapper.emitted('removed')).toBeUndefined()

    await button(wrapper, 'Remove the older connection').trigger('click')
    await flushPromises()

    expect(vi.mocked(removeItem).mock.calls).toEqual([['first'], ['second'], ['second']])
    expect(wrapper.emitted('removed')).toHaveLength(1)
  })
})
