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
  return wrapper.findAll('li.row').map((row) => row.text())
}

function tabs(wrapper: ReturnType<typeof mountDialog>) {
  return wrapper.findAll('[role="tab"]').map((tab) => tab.text())
}

async function showTab(wrapper: ReturnType<typeof mountDialog>, label: string) {
  const tab = wrapper.findAll('[role="tab"]').find((element) => element.text().startsWith(label))
  if (!tab) throw new Error(`No ${label} tab`)
  await tab.trigger('click')
}

async function open(wrapper: ReturnType<typeof mountDialog>, name: string) {
  const button = wrapper.findAll('button.name').find((element) => element.text() === name)
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
  it('waits until it is opened, then loads every stream and candidate, dismissed ones too', async () => {
    const wrapper = mountDialog({ visible: false, accountId: 'checking' })
    await flushPromises()
    expect(getRecurringTransactions).not.toHaveBeenCalled()

    await wrapper.setProps({ visible: true, accountLabel: 'Checking ••1234' })
    await flushPromises()

    expect(getRecurringTransactions).toHaveBeenCalledWith('checking', 50, true)
    expect(getRecurringCandidates).toHaveBeenCalledWith('checking')
    expect(wrapper.text()).toContain('Checking ••1234')
  })

  it('keeps what’s recurring, what might be, and what you ruled out in separate lists', async () => {
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    expect(tabs(wrapper)).toEqual(['Recurring 2', 'Possibly recurring 1', 'Not recurring 1'])
    expect(rowTexts(wrapper)).toEqual([
      'AAlabama PowerMonthly · Next Oct 17-$155.59Not recurring',
      'SSterling GroupEvery 3 months · Next Dec 29 · Confirmed by you-$1,554.91Not recurring',
    ])

    await showTab(wrapper, 'Possibly recurring')
    expect(rowTexts(wrapper)).toEqual(['CCursorMonthly? · Last Sep 1-$20.00YesNo'])

    await showTab(wrapper, 'Not recurring')
    expect(rowTexts(wrapper)).toEqual(['YYouTubeLast Sep 17-$13.99Undo'])
  })

  it('moves a row to its new list in one click, then catches up without a loading state', async () => {
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    let saveAnswer: () => void = () => {}
    vi.mocked(answerRecurringCandidate).mockReturnValueOnce(
      new Promise((resolve) => (saveAnswer = resolve)),
    )
    await wrapper.get('button[aria-label="Alabama Power doesn’t repeat"]').trigger('click')
    await flushPromises()

    // The row has moved before the save is even answered.
    expect(tabs(wrapper)).toEqual(['Recurring 1', 'Possibly recurring 1', 'Not recurring 2'])
    expect(getRecurringTransactions).toHaveBeenCalledTimes(1)

    vi.mocked(getRecurringTransactions).mockResolvedValue([
      youtube,
      { ...power, status: 'DISMISSED' },
    ])
    saveAnswer()
    await flushPromises()

    expect(answerRecurringCandidate).toHaveBeenLastCalledWith(
      { kind: 'BILL', merchant_key: 'alabama power' },
      false,
    )
    expect(getRecurringTransactions).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(tabs(wrapper)).toEqual(['Recurring 1', 'Possibly recurring 1', 'Not recurring 2'])
  })

  it('keeps an answer when a load asked before it arrives after it', async () => {
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    // Saving in the editor starts a quiet load; it's still on its way when the answer is given.
    let finishLoad: (streams: RecurringStream[]) => void = () => {}
    await open(wrapper, 'Sterling Group')
    await wrapper.get('select[aria-label="Frequency"]').setValue('MONTHLY')
    vi.mocked(getRecurringTransactions).mockReturnValueOnce(
      new Promise((resolve) => (finishLoad = resolve)),
    )
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()

    vi.mocked(getRecurringTransactions).mockResolvedValue([
      youtube,
      { ...power, status: 'DISMISSED' },
    ])
    await wrapper.get('button[aria-label="Alabama Power doesn’t repeat"]').trigger('click')
    await flushPromises()
    finishLoad([youtube, power])
    await flushPromises()

    expect(tabs(wrapper)).toEqual(['Recurring 1', 'Possibly recurring 1', 'Not recurring 2'])
  })

  it('passes on an answer that’s saved after the dialog has closed', async () => {
    const wrapper = mountDialog({ visible: true })
    await flushPromises()
    let saveAnswer: () => void = () => {}
    vi.mocked(answerRecurringCandidate).mockReturnValueOnce(
      new Promise((resolve) => (saveAnswer = resolve)),
    )

    await wrapper.get('button[aria-label="Alabama Power doesn’t repeat"]').trigger('click')
    await wrapper.setProps({ visible: false })
    expect(wrapper.emitted('changed')).toBeUndefined()

    saveAnswer()
    await flushPromises()

    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(getRecurringTransactions).toHaveBeenCalledTimes(1)
  })

  it('puts a row back where it came from when you undo ruling it out', async () => {
    vi.mocked(getRecurringCandidates).mockResolvedValue([{ ...cursor, status: 'DISMISSED' }])
    const wrapper = mountDialog({ visible: true })
    await flushPromises()
    await showTab(wrapper, 'Not recurring')

    // Saves stay unanswered here, so this is where the rows go before the server is heard from.
    vi.mocked(undoRecurringAnswer).mockReturnValue(new Promise(() => {}))

    // One of Plaid's streams is recurring again; one of Jev's guesses is a guess again.
    await wrapper.get('button[aria-label="Undo dismissing YouTube"]').trigger('click')
    await wrapper.get('button[aria-label="Undo dismissing Cursor"]').trigger('click')
    await flushPromises()

    expect(undoRecurringAnswer).toHaveBeenCalledWith({ kind: 'BILL', merchant_key: 'youtube' })
    expect(undoRecurringAnswer).toHaveBeenCalledWith({ kind: 'BILL', merchant_key: 'cursor' })
    expect(tabs(wrapper)).toEqual(['Recurring 2', 'Possibly recurring 1', 'Not recurring 0'])
  })

  it('tells what’s behind it to load again once, when it closes, and only if something changed', async () => {
    const wrapper = mountDialog({ visible: true })
    await flushPromises()
    await wrapper.setProps({ visible: false })
    expect(wrapper.emitted('changed')).toBeUndefined()

    await wrapper.setProps({ visible: true })
    await flushPromises()
    await wrapper.get('button[aria-label="Alabama Power doesn’t repeat"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('changed')).toBeUndefined()

    await wrapper.setProps({ visible: false })
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('puts the row back and says so when an answer can’t be saved, and offers none without a payee', async () => {
    vi.mocked(getRecurringTransactions).mockResolvedValue([
      power,
      { ...power, stream_id: 'unnamed', merchant_name: 'Mystery', kind: null, merchant_key: null },
    ])
    vi.mocked(answerRecurringCandidate).mockRejectedValueOnce(new Error('offline'))
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    expect(wrapper.find('button[aria-label="Mystery doesn’t repeat"]').exists()).toBe(false)

    await wrapper.get('button[aria-label="Alabama Power doesn’t repeat"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(
      'We couldn’t save your answer for Alabama Power. Please try again.',
    )
    expect(tabs(wrapper)[0]).toBe('Recurring 3')
    await wrapper.setProps({ visible: false })
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('lets you change how often a payee is paid, then catches up without a loading state', async () => {
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    await open(wrapper, 'Sterling Group')
    const save = button(wrapper, 'Save')
    expect(save.attributes('disabled')).toBeDefined()

    await wrapper.get('select[aria-label="Frequency"]').setValue('MONTHLY')
    vi.mocked(getRecurringCandidates).mockResolvedValue([
      cursor,
      { ...rent, frequency: 'MONTHLY', frequency_set: true, next_date: '2026-10-29' },
    ])
    await save.trigger('click')
    await flushPromises()

    expect(answerRecurringCandidate).toHaveBeenCalledWith(
      { kind: 'BILL', merchant_key: 'sterling group' },
      true,
      'MONTHLY',
    )
    expect(getRecurringTransactions).toHaveBeenCalledTimes(2)
    expect(rowTexts(wrapper)[1]).toContain('Monthly · Next Oct 29 · Confirmed by you')
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

  it('keeps the editor open and says so when saving there fails', async () => {
    vi.mocked(answerRecurringCandidate).mockRejectedValueOnce(new Error('offline'))
    const wrapper = mountDialog({ visible: true })
    await flushPromises()

    await open(wrapper, 'Alabama Power')
    await button(wrapper, 'It doesn’t repeat').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t save that. Please try again.')
  })

  it('still lists Plaid’s streams when candidates can’t be loaded, and offers a retry when streams can’t', async () => {
    vi.mocked(getRecurringCandidates).mockRejectedValue(new Error('offline'))
    const wrapper = mountDialog({ visible: true })
    await flushPromises()
    expect(tabs(wrapper)).toEqual(['Recurring 1', 'Possibly recurring 0', 'Not recurring 1'])

    vi.mocked(getRecurringTransactions).mockRejectedValueOnce(new Error('offline'))
    await wrapper.setProps({ accountId: 'savings' })
    await flushPromises()
    expect(wrapper.text()).toContain('We couldn’t load your recurring transactions.')

    await button(wrapper, 'Try again').trigger('click')
    await flushPromises()
    expect(tabs(wrapper)[0]).toBe('Recurring 1')
  })
})
