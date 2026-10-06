import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import TransactionEditor from '../TransactionEditor.vue'
import {
  answerRecurringCandidate,
  correctPayee,
  setTransactionCounting,
  undoPayeeCorrection,
  undoRecurringAnswer,
  type PlaidTransaction,
} from '../../api/PlaidService'

vi.mock('../../api/PlaidService', () => ({
  answerRecurringCandidate: vi.fn(),
  correctPayee: vi.fn(),
  setTransactionCounting: vi.fn(),
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
  category_detailed: 'HOME_IMPROVEMENT_REPAIR_AND_MAINTENANCE',
  bucket: 'GUILT_FREE',
  plan_line: null,
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
  it('excludes only this transaction by default', async () => {
    const wrapper = await mountEditor(rent)
    await button(wrapper, 'Exclude transaction').trigger('click')
    await flushPromises()
    expect(setTransactionCounting).toHaveBeenCalledWith('rent-sep', true, false)
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(wrapper.emitted('update:visible')).toEqual([[false]])
  })

  it('can also exclude future transactions with this recipient', async () => {
    const wrapper = await mountEditor(rent)
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await button(wrapper, 'Exclude transaction').trigger('click')
    await flushPromises()
    expect(setTransactionCounting).toHaveBeenCalledWith('rent-sep', true, true)
  })

  it('restores one transaction without removing its future rule', async () => {
    const wrapper = await mountEditor({ ...rent, excluded: true, future_excluded: true })
    await button(wrapper, 'Include transaction').trigger('click')
    await flushPromises()
    expect(setTransactionCounting).toHaveBeenCalledWith('rent-sep', false, false)
  })

  it('can stop excluding future transactions', async () => {
    const wrapper = await mountEditor({ ...rent, excluded: true, future_excluded: true })
    await button(wrapper, 'Include this and future').trigger('click')
    await flushPromises()
    expect(setTransactionCounting).toHaveBeenCalledWith('rent-sep', false, true)
  })

  it('supports a transaction without a recipient, but offers no future rule', async () => {
    const wrapper = await mountEditor({ ...rent, payee_key: null, payee_kind: null })
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    await button(wrapper, 'Exclude transaction').trigger('click')
    await flushPromises()
    expect(setTransactionCounting).toHaveBeenCalledWith('rent-sep', true, false)
  })

  it('keeps the editor open when excluding fails', async () => {
    vi.mocked(setTransactionCounting).mockRejectedValueOnce(new Error('offline'))
    const wrapper = await mountEditor(rent)
    await button(wrapper, 'Exclude transaction').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('We couldn’t save that. Please try again.')
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('moves every charge from the payee to the bucket and category the user picks', async () => {
    const wrapper = await mountEditor(rent)

    expect(wrapper.text()).toContain('Applies to past and future transactions with this recipient.')
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

    await button(wrapper, 'Reset to automatic').trigger('click')
    await flushPromises()

    expect(undoPayeeCorrection).toHaveBeenCalledWith('sterling group')
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('lets the user say a payment repeats', async () => {
    const wrapper = await mountEditor(rent)

    await button(wrapper, 'Yes').trigger('click')
    await flushPromises()

    expect(answerRecurringCandidate).toHaveBeenCalledWith(
      { kind: 'BILL', merchant_key: 'sterling group' },
      true,
    )
  })

  it('undoes an earlier answer', async () => {
    const wrapper = await mountEditor({ ...rent, recurring: 'CONFIRMED' })

    expect(wrapper.text()).toContain('Marked as recurring')
    await button(wrapper, 'Undo').trigger('click')
    await flushPromises()

    expect(undoRecurringAnswer).toHaveBeenCalledWith({
      kind: 'BILL',
      merchant_key: 'sterling group',
    })
  })

  it('does not ask about a payment Plaid already detects', async () => {
    const wrapper = await mountEditor({ ...rent, recurring: 'DETECTED' })

    expect(wrapper.text()).toContain('Marked as recurring')
    expect(wrapper.findAll('button').some((element) => element.text() === 'Yes')).toBe(false)
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
