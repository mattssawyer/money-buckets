import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import SameInstitutionNotices from '../SameInstitutionNotices.vue'
import { removeItem, type PlaidItem } from '../../api/PlaidService'

vi.mock('../../api/PlaidService', () => ({
  removeItem: vi.fn<() => void>(),
}))

function item(item_id: string, institution_id: string, institution_name: string): PlaidItem {
  return {
    item_id,
    institution_id,
    institution_name,
    investments: false,
    investments_available: true,
    reconnect: null,
  }
}

const items = [
  item('chase-old', 'ins_3', 'Chase'),
  item('fidelity-old', 'ins_12', 'Fidelity'),
  item('chase-older', 'ins_3', 'Chase'),
]

beforeEach(() => vi.resetAllMocks())

describe('same institution notices', () => {
  function mountNotices() {
    return mount(SameInstitutionNotices, {
      props: { items },
      global: { plugins: [[PrimeVue, { unstyled: true }]] },
    })
  }

  it('warns about each institution separately', () => {
    const wrapper = mountNotices()

    const notices = wrapper.findAll('.same-institution')
    expect(notices).toHaveLength(2)
    expect(notices[0]!.text()).toContain('You already had Chase connected')
    expect(notices[1]!.text()).toContain('You already had Fidelity connected')
  })

  it('removes only the older connections at the chosen institution', async () => {
    vi.mocked(removeItem).mockResolvedValue()
    const wrapper = mountNotices()

    const chase = wrapper.findAll('.same-institution')[0]!
    await chase
      .findAll('button')
      .find((button) => button.text() === 'Remove the older connection')!
      .trigger('click')
    await flushPromises()

    expect(vi.mocked(removeItem).mock.calls).toEqual([['chase-old'], ['chase-older']])
    expect(wrapper.emitted('removed')).toEqual([[['chase-old', 'chase-older']]])
  })

  it('keeps both connections at one institution without touching the others', async () => {
    const wrapper = mountNotices()

    const fidelity = wrapper.findAll('.same-institution')[1]!
    await fidelity
      .findAll('button')
      .find((button) => button.text() === 'Keep both')!
      .trigger('click')

    expect(wrapper.emitted('dismiss')).toEqual([[['fidelity-old']]])
    expect(removeItem).not.toHaveBeenCalled()
  })
})
