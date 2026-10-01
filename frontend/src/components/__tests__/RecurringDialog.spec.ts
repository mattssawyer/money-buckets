import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import RecurringDialog from '../RecurringDialog.vue'
import {
  answerRecurringCandidate,
  getRecurringCandidates,
  getRecurringTransactions,
  undoRecurringAnswer,
  type RecurringCandidate,
  type RecurringStream,
} from '../../api/PlaidService'

vi.mock('../../api/PlaidService', () => ({
  answerRecurringCandidate: vi.fn(),
  getRecurringCandidates: vi.fn(),
  getRecurringTransactions: vi.fn(),
  undoRecurringAnswer: vi.fn(),
}))
enableAutoUnmount(afterEach)

const power: RecurringStream = {
  stream_id: 'power',
  account_id: 'checking',
  merchant_name: 'Alabama Power',
  description: 'ALABAMA POWER',
  amount: 155.59,
  iso_currency_code: 'USD',
  frequency: 'MONTHLY',
  frequency_set: false,
  next_date: '2026-10-17',
  last_date: '2026-09-17',
  is_inflow: false,
  category: 'RENT_AND_UTILITIES',
  category_detailed: 'RENT_AND_UTILITIES_GAS_AND_ELECTRICITY',
  share_percent: 100,
  plan_bucket: 'FIXED_COSTS',
  plan_line: 'Utilities',
  kind: 'BILL',
  merchant_key: 'alabama power',
  status: 'DETECTED',
}

const youtube: RecurringStream = {
  ...power,
  stream_id: 'youtube',
  merchant_name: 'YouTube',
  amount: 13.99,
  merchant_key: 'youtube',
  status: 'DISMISSED',
}

const rent: RecurringCandidate = {
  ...power,
  stream_id: 'candidate-rent',
  merchant_name: 'Sterling Group',
  amount: 1554.91,
  frequency: 'QUARTERLY',
  next_date: '2026-12-29',
  merchant_key: 'sterling group',
  kind: 'BILL',
  probability: 0.9,
  status: 'CONFIRMED',
}

const cursor: RecurringCandidate = {
  ...rent,
  stream_id: 'candidate-cursor',
  merchant_name: 'Cursor',
  amount: 20,
  frequency: 'MONTHLY',
  next_date: null,
  last_date: '2026-09-01',
  merchant_key: 'cursor',
  status: 'SUGGESTED',
}

function mountDialog(props: { visible: boolean; accountId?: string; accountLabel?: string }) {
  return mount(RecurringDialog, {
    props,
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: { teleport: true },
    },
  })
}

function rowTexts(wrapper: ReturnType<typeof mountDialog>) {
  return wrapper.findAll('tbody tr').map((row) => row.findAll('td').map((cell) => cell.text()))
}

async function open(wrapper: ReturnType<typeof mountDialog>, name: string) {
  const button = wrapper.findAll('button.name-button').find((element) => element.text() === name)
  if (!button) throw new Error(`No row for ${name}`)
  await button.trigger('click')
}

function button(wrapper: ReturnType<typeof mountDialog>, label: string) {
  const found = wrapper.findAll('button').find((element) => element.text() === label)
  if (!found) throw new Error(`No ${label} button`)
  return found
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getRecurringTransactions).mockResolvedValue([youtube, power])
  vi.mocked(getRecurringCandidates).mockResolvedValue([cursor, rent])
})

describe('recurring dialog', () => {
  it('waits until it is opened, then lists every stream and candidate, dismissed ones too', async () => {
    const wrapper = mountDialog({ visible: false, accountId: 'checking' })
    await flushPromises()
    expect(getRecurringTransactions).not.toHaveBeenCalled()

    await wrapper.setProps({ visible: true, accountLabel: 'Checking ••1234' })
    await flushPromises()

    expect(getRecurringTransactions).toHaveBeenCalledWith('checking', 50, true)
    expect(getRecurringCandidates).toHaveBeenCalledWith('checking')
    expect(rowTexts(wrapper)).toEqual([
      ['AAlabama Power', 'Monthly', 'Next Oct 17', 'Recurring', '-$155.59'],
      ['SSterling Group', 'Every 3 months', 'Next Dec 29', 'Confirmed by you', '-$1,554.91'],
      ['CCursor', 'Monthly', 'Last Sep 1', 'Possibly recurring', '-$20.00'],
      ['YYouTube', '', '', 'Not recurring', '-$13.99'],
    ])
    expect(wrapper.text()).toContain('Checking ••1234 · 2 recurring')
  })

  it('lets you say a payee Plaid detects doesn’t repeat', async () => {
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    await open(wrapper, 'Alabama Power')
    await button(wrapper, 'It doesn’t repeat').trigger('click')
    await flushPromises()

    expect(answerRecurringCandidate).toHaveBeenCalledWith(
      { kind: 'BILL', merchant_key: 'alabama power' },
      false,
    )
    expect(getRecurringTransactions).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('lets you change how often a payee is paid', async () => {
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    await open(wrapper, 'Sterling Group')
    const save = button(wrapper, 'Save')
    expect(save.attributes('disabled')).toBeDefined()

    await wrapper.get('select[aria-label="Frequency"]').setValue('MONTHLY')
    await save.trigger('click')
    await flushPromises()

    expect(answerRecurringCandidate).toHaveBeenCalledWith(
      { kind: 'BILL', merchant_key: 'sterling group' },
      true,
      'MONTHLY',
    )
  })

  it('hands a frequency you set back to automatic', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([
      { ...power, frequency: 'QUARTERLY', frequency_set: true },
    ])
    vi.mocked(getRecurringCandidates).mockResolvedValue([{ ...rent, frequency_set: true }])
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    await open(wrapper, 'Alabama Power')
    await button(wrapper, 'Go back to automatic').trigger('click')
    await flushPromises()
    // Plaid's stream needs no answer; a payee you confirmed keeps its yes.
    expect(undoRecurringAnswer).toHaveBeenCalledWith({
      kind: 'BILL',
      merchant_key: 'alabama power',
    })

    await open(wrapper, 'Sterling Group')
    await button(wrapper, 'Go back to automatic').trigger('click')
    await flushPromises()
    expect(answerRecurringCandidate).toHaveBeenCalledWith(
      { kind: 'BILL', merchant_key: 'sterling group' },
      true,
    )
  })

  it('answers a possible one and undoes a dismissal', async () => {
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    await open(wrapper, 'Cursor')
    await button(wrapper, 'This repeats').trigger('click')
    await flushPromises()
    expect(answerRecurringCandidate).toHaveBeenCalledWith(
      { kind: 'BILL', merchant_key: 'cursor' },
      true,
    )

    await open(wrapper, 'YouTube')
    expect(wrapper.find('select[aria-label="Frequency"]').exists()).toBe(false)
    await button(wrapper, 'Undo').trigger('click')
    await flushPromises()
    expect(undoRecurringAnswer).toHaveBeenCalledWith({ kind: 'BILL', merchant_key: 'youtube' })
  })

  it('keeps the editor open and says so when saving fails', async () => {
    vi.mocked(answerRecurringCandidate).mockRejectedValueOnce(new Error('offline'))
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    await open(wrapper, 'Alabama Power')
    await button(wrapper, 'It doesn’t repeat').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t save that. Please try again.')
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('still lists Plaid’s streams when candidates can’t be loaded, and offers a retry when streams can’t', async () => {
    vi.mocked(getRecurringCandidates).mockRejectedValue(new Error('offline'))
    const wrapper = mountDialog({ visible: true })
    await flushPromises()
    expect(rowTexts(wrapper)).toHaveLength(2)

    vi.mocked(getRecurringTransactions).mockRejectedValueOnce(new Error('offline'))
    await wrapper.setProps({ accountId: 'savings' })
    await flushPromises()
    expect(wrapper.text()).toContain('We couldn’t load your recurring transactions.')

    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()
    expect(rowTexts(wrapper)).toHaveLength(2)
  })
})
