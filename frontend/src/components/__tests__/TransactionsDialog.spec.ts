import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import TransactionsDialog from '../TransactionsDialog.vue'
import { getTransactionPage, type PlaidTransaction } from '../../api/PlaidService'

vi.mock('../../api/PlaidService', () => ({ getTransactionPage: vi.fn() }))
enableAutoUnmount(afterEach)

const coffee: PlaidTransaction = {
  transaction_id: 'txn-1',
  account_id: 'checking',
  amount: 4.75,
  iso_currency_code: 'USD',
  date: '2026-09-17',
  name: 'COFFEE SHOP',
  merchant_name: 'Coffee Shop',
  logo_url: null,
  pending: true,
  category: 'FOOD_AND_DRINK',
  bucket: 'GUILT_FREE',
  share_percent: 100,
  payee_key: 'coffee shop',
  payee_kind: 'BILL',
  bucket_corrected: false,
  category_corrected: false,
  recurring: null,
}

function mountDialog(props: { visible: boolean; accountId?: string; accountLabel?: string }) {
  return mount(TransactionsDialog, {
    props,
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: { teleport: true },
    },
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getTransactionPage).mockResolvedValue({ transactions: [coffee], total: 1 })
})

describe('transactions dialog', () => {
  it('waits until it is opened to load', async () => {
    const wrapper = mountDialog({ visible: false, accountId: 'checking' })
    await flushPromises()
    expect(getTransactionPage).not.toHaveBeenCalled()

    await wrapper.setProps({ visible: true })
    await flushPromises()

    expect(getTransactionPage).toHaveBeenCalledWith(0, 50, 'checking')
  })

  it('shows each transaction as a table row', async () => {
    const wrapper = mountDialog({
      visible: true,
      accountId: 'checking',
      accountLabel: 'Checking ••1234',
    })
    await flushPromises()

    const cells = wrapper.findAll('tbody tr td').map((cell) => cell.text())
    expect(cells).toEqual([
      'Sep 17, 2026',
      'CCoffee ShopPending',
      'Food & drink',
      'Guilt-free spending',
      '-$4.75',
    ])
    expect(wrapper.text()).toContain('Checking ••1234 · 1 total')
  })

  it('labels transactions sorting has not reached yet', async () => {
    vi.mocked(getTransactionPage).mockResolvedValue({
      transactions: [{ ...coffee, bucket: null }],
      total: 1,
    })
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    expect(wrapper.text()).toContain('Not sorted yet')
  })

  it('loads the next page from the paginator', async () => {
    vi.mocked(getTransactionPage).mockResolvedValue({ transactions: [coffee], total: 120 })
    const wrapper = mountDialog({ visible: true, accountId: 'checking' })
    await flushPromises()

    await wrapper.get('button[aria-label="Next Page"]').trigger('click')
    await flushPromises()

    expect(getTransactionPage).toHaveBeenLastCalledWith(1, 50, 'checking')
  })

  it('offers a retry when a page fails to load', async () => {
    vi.mocked(getTransactionPage).mockRejectedValueOnce(new Error('Server unavailable'))
    const wrapper = mountDialog({ visible: true, accountId: 'checking' })
    await flushPromises()
    expect(wrapper.text()).toContain('We couldn’t load your transactions.')

    await wrapper
      .findAll('button')
      .find((element) => element.text() === 'Try again')!
      .trigger('click')
    await flushPromises()

    expect(getTransactionPage).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Coffee Shop')
  })
})
