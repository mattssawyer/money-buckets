import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import TransactionEditor from '../TransactionEditor.vue'
import {
  answerRecurringCandidate,
  correctPayee,
  undoPayeeCorrection,
  undoRecurringAnswer,
  type PlaidTransaction,
} from '../../api/PlaidService'

vi.mock('../../api/PlaidService', () => ({
  answerRecurringCandidate: vi.fn(),
  correctPayee: vi.fn(),
  undoPayeeCorrection: vi.fn(),
  undoRecurringAnswer: vi.fn(),
}))
enableAutoUnmount(afterEach)

// Rent Plaid labels home improvement, which sorting put in guilt-free spending.
const rent: PlaidTransaction = {
  transaction_id: 'rent-sep',
  account_id: 'joint',
  amount: 1554.91,
  iso_currency_code: 'USD',
  date: '2026-09-01',
  name: 'STERLING GROUP WEB PMTS',
  merchant_name: 'Sterling Group',
  logo_url: null,
  pending: false,
  category: 'HOME_IMPROVEMENT',
  bucket: 'GUILT_FREE',
  share_percent: 50,
  payee_key: 'sterling group',
  payee_kind: 'BILL',
  bucket_corrected: false,
  category_corrected: false,
  recurring: null,
}

async function mountEditor(transaction: PlaidTransaction) {
  const wrapper = mount(TransactionEditor, {
    props: { visible: true, transaction },
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: { teleport: true },
    },
  })
  // The dialog renders once it has opened.
  await flushPromises()
  return wrapper
}

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find((element) => element.text() === label)
  if (!found) throw new Error(`Button not found: ${label}`)
  return found
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('transaction editor', () => {
  it('moves every charge from the payee to the bucket and category the user picks', async () => {
    const wrapper = await mountEditor(rent)

    expect(wrapper.text()).toContain(
      'Applies to every charge from Sterling Group, including future ones.',
    )
    await wrapper.get('[aria-label="Bucket"]').setValue('FIXED_COSTS')
    await wrapper.get('[aria-label="Category"]').setValue('RENT_AND_UTILITIES')
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()

    expect(correctPayee).toHaveBeenCalledWith('sterling group', 'FIXED_COSTS', 'RENT_AND_UTILITIES')
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(wrapper.emitted('update:visible')).toEqual([[false]])
  })

  it('leaves the part the user did not change to sorting and Plaid', async () => {
    const wrapper = await mountEditor(rent)

    await wrapper.get('[aria-label="Bucket"]').setValue('FIXED_COSTS')
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()

    expect(correctPayee).toHaveBeenCalledWith('sterling group', 'FIXED_COSTS', null)
  })

  it('forgets unsaved picks when it opens again', async () => {
    const wrapper = await mountEditor(rent)

    await wrapper.get('[aria-label="Bucket"]').setValue('FIXED_COSTS')
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    expect((wrapper.get('[aria-label="Bucket"]').element as HTMLSelectElement).value).toBe(
      'GUILT_FREE',
    )
  })

  it('saves nothing until something changes', async () => {
    const wrapper = await mountEditor(rent)

    expect(button(wrapper, 'Save').attributes('disabled')).toBeDefined()
  })

  it('goes back to automatic sorting for a payee the user corrected', async () => {
    const wrapper = await mountEditor({ ...rent, bucket: 'FIXED_COSTS', bucket_corrected: true })

    expect(wrapper.text()).toContain('You’ve set this yourself, so it won’t be re-sorted.')
    await button(wrapper, 'Go back to automatic').trigger('click')
    await flushPromises()

    expect(undoPayeeCorrection).toHaveBeenCalledWith('sterling group')
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('lets the user say a payment repeats', async () => {
    const wrapper = await mountEditor(rent)

    await button(wrapper, 'This repeats').trigger('click')
    await flushPromises()

    expect(answerRecurringCandidate).toHaveBeenCalledWith(
      { kind: 'BILL', merchant_key: 'sterling group' },
      true,
    )
  })

  it('undoes an earlier answer', async () => {
    const wrapper = await mountEditor({ ...rent, recurring: 'CONFIRMED' })

    expect(wrapper.text()).toContain('You said this repeats')
    await button(wrapper, 'Undo').trigger('click')
    await flushPromises()

    expect(undoRecurringAnswer).toHaveBeenCalledWith({
      kind: 'BILL',
      merchant_key: 'sterling group',
    })
  })

  it('does not ask about a payment Plaid already detects', async () => {
    const wrapper = await mountEditor({ ...rent, recurring: 'DETECTED' })

    expect(wrapper.text()).toContain('Plaid already detects this as recurring.')
    expect(wrapper.findAll('button').some((element) => element.text() === 'This repeats')).toBe(
      false,
    )
  })

  it('only asks whether pay is pay', async () => {
    const wrapper = await mountEditor({
      ...rent,
      amount: -2400,
      name: 'ACME PAYROLL',
      merchant_name: null,
      category: 'INCOME',
      bucket: null,
      payee_key: 'acme payroll',
      payee_kind: 'PAYCHECK',
    })

    expect(wrapper.find('[aria-label="Bucket"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Is this your pay?')
  })

  it('keeps the dialog open and says so when saving fails', async () => {
    vi.mocked(correctPayee).mockRejectedValueOnce(new Error('offline'))
    const wrapper = await mountEditor(rent)

    await wrapper.get('[aria-label="Bucket"]').setValue('FIXED_COSTS')
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t save that. Please try again.')
    expect(wrapper.emitted('changed')).toBeUndefined()
  })
})
