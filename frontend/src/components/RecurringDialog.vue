<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import RecurringEditor from './RecurringEditor.vue'
import {
  answerRecurringCandidate,
  getRecurringCandidates,
  getRecurringTransactions,
  undoRecurringAnswer,
  type RecurringStatus,
  type RecurringStream,
} from '../api/PlaidService'
import { frequencyLabel, recurringLabel } from '../api/plaidLabels'

/** The most streams the server sends. */
const STREAM_LIMIT = 50

/** What's recurring, what's waiting on an answer, and what the user ruled out, each on its own. */
type Tab = 'recurring' | 'possible' | 'dismissed'

const TABS: { id: Tab; label: string; empty: string }[] = [
  {
    id: 'recurring',
    label: 'Recurring',
    empty:
      'No recurring transactions found yet. Plaid can take up to a day to find them after you link a bank.',
  },
  { id: 'possible', label: 'Possibly recurring', empty: 'Nothing is waiting on your answer.' },
  { id: 'dismissed', label: 'Not recurring', empty: 'You haven’t ruled anything out.' },
]

const TAB_OF: Record<RecurringStatus, Tab> = {
  DETECTED: 'recurring',
  CONFIRMED: 'recurring',
  SUGGESTED: 'possible',
  DISMISSED: 'dismissed',
}

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  accountId?: string
  /** Shown under the title so it's clear which account the list covers. */
  accountLabel?: string
}>()
const emit = defineEmits<{
  /** Payees were answered while this was open, so other views of them should load again. */
  changed: []
}>()

const rows = ref<RecurringStream[]>([])
const tab = ref<Tab>('recurring')
const loading = ref(false)
const error = ref('')
const editing = ref<RecurringStream>()
const editorVisible = ref(false)
// The rows whose answers are being saved.
const answering = ref(new Set<string>())
const answerError = ref('')
// Whether anything was answered, so what's behind the dialog loads again once it closes rather
// than after every click.
let changed = false

const rowsByTab = computed(() => {
  const byTab: Record<Tab, RecurringStream[]> = { recurring: [], possible: [], dismissed: [] }
  for (const row of rows.value) byTab[TAB_OF[row.status]].push(row)
  for (const list of Object.values(byTab)) {
    list.sort((a, b) => recurringLabel(a).localeCompare(recurringLabel(b)))
  }
  return byTab
})
const shown = computed(() => rowsByTab.value[tab.value])

function edit(stream: RecurringStream) {
  editing.value = stream
  editorVisible.value = true
}

/** The editor can change a frequency, which only the server can work the next date out from. */
function onEdited() {
  changed = true
  void load(true)
}

/**
 * Saves whether a row's payee repeats, or with null forgets the answer. The row moves to its
 * new list straight away, and comes back if the save fails.
 */
async function answer(stream: RecurringStream, repeats: boolean | null) {
  if (answering.value.has(stream.stream_id) || !stream.kind || !stream.merchant_key) return
  const payee = { kind: stream.kind, merchant_key: stream.merchant_key }
  const previous = stream.status
  answering.value.add(stream.stream_id)
  answerError.value = ''
  setStatus(stream, answered(stream, repeats))
  try {
    if (repeats === null) await undoRecurringAnswer(payee)
    else await answerRecurringCandidate(payee, repeats)
    changed = true
  } catch {
    setStatus(stream, previous)
    answerError.value = `We couldn’t save your answer for ${recurringLabel(stream)}. Please try again.`
  } finally {
    answering.value.delete(stream.stream_id)
  }
}

/**
 * Where an answer leaves a row. With the answer forgotten, one of Plaid's streams is recurring
 * again and one of Jev's candidates is a guess again; only candidates carry a probability.
 */
function answered(stream: RecurringStream, repeats: boolean | null): RecurringStatus {
  const fromPlaid = !('probability' in stream)
  if (repeats === false) return 'DISMISSED'
  if (fromPlaid) return 'DETECTED'
  return repeats ? 'CONFIRMED' : 'SUGGESTED'
}

function setStatus(stream: RecurringStream, status: RecurringStatus) {
  rows.value = rows.value.map((row) =>
    row.stream_id === stream.stream_id ? { ...row, status } : row,
  )
}

// Only the newest request may fill the list, in case the account changes mid-flight.
let latestRequest = 0

watch(
  [visible, () => props.accountId],
  ([open]) => {
    if (open) {
      tab.value = 'recurring'
      void load()
    } else if (changed) {
      changed = false
      emit('changed')
    }
  },
  { immediate: true },
)

/**
 * Plaid's streams and Jev's candidates together, answered or not. A quiet load swaps the rows
 * in place, without the list giving way to a loading state.
 */
async function load(quiet = false) {
  const request = ++latestRequest
  loading.value = !quiet
  if (!quiet) error.value = ''
  try {
    const [streams, candidates] = await Promise.all([
      getRecurringTransactions(props.accountId, STREAM_LIMIT, true),
      // Candidates are only guesses, so Plaid's streams still show when they can't be loaded.
      getRecurringCandidates(props.accountId).catch(() => []),
    ])
    if (request !== latestRequest) return
    rows.value = [...streams, ...candidates]
  } catch {
    // A quiet load that fails leaves what's on screen; it was only catching up.
    if (request === latestRequest && !quiet) {
      error.value = 'We couldn’t load your recurring transactions.'
    }
  } finally {
    if (request === latestRequest) loading.value = false
  }
}

// Plaid dates are calendar days, so parse them locally instead of as UTC instants.
function formatDate(date: string) {
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(
    new Date(`${date}T00:00:00`),
  )
}

/** "Monthly · Next Oct 29 · Confirmed by you"; a ruled-out payee only says when it was last paid. */
function meta(stream: RecurringStream) {
  const last = stream.last_date ? `Last ${formatDate(stream.last_date)}` : ''
  if (stream.status === 'DISMISSED') return last
  return [
    frequencyLabel(stream.frequency) + (stream.status === 'SUGGESTED' ? '?' : ''),
    stream.next_date ? `Next ${formatDate(stream.next_date)}` : last,
    stream.status === 'CONFIRMED' ? 'Confirmed by you' : '',
  ]
    .filter(Boolean)
    .join(' · ')
}

function formatAmount(stream: RecurringStream) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: stream.iso_currency_code ?? 'USD',
    signDisplay: 'exceptZero',
  }).format(-stream.amount)
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    dismissable-mask
    block-scroll
    :draggable="false"
    :style="{ width: 'min(40rem, calc(100vw - 2rem))', height: '88dvh', maxHeight: '92dvh' }"
    :breakpoints="{ '640px': 'calc(100vw - 1.5rem)' }"
    :pt="{
      content: {
        style: { display: 'flex', flexDirection: 'column', flex: '1', minHeight: '0' },
      },
    }"
  >
    <template #header>
      <div class="dialog-heading">
        <h2 class="dialog-title">Recurring</h2>
        <p v-if="accountLabel" class="dialog-subtitle">{{ accountLabel }}</p>
      </div>
    </template>

    <div v-if="error" class="dialog-notice">
      <Message severity="error">{{ error }}</Message>
      <Button label="Try again" severity="secondary" @click="load()" />
    </div>

    <template v-else>
      <div class="tabs" role="tablist" aria-label="Recurring transactions">
        <button
          v-for="option in TABS"
          :id="`recurring-tab-${option.id}`"
          :key="option.id"
          type="button"
          role="tab"
          class="tab"
          :class="{ 'tab-selected': tab === option.id }"
          :aria-selected="tab === option.id"
          aria-controls="recurring-panel"
          @click="tab = option.id"
        >
          {{ option.label }}
          <span v-if="!loading" class="tab-count">{{ rowsByTab[option.id].length }}</span>
        </button>
      </div>

      <Message v-if="answerError" severity="error" class="answer-error">{{ answerError }}</Message>

      <div
        id="recurring-panel"
        class="panel-body"
        role="tabpanel"
        :aria-labelledby="`recurring-tab-${tab}`"
        :aria-busy="loading"
      >
        <div v-if="loading" class="rows-loading" role="status" aria-label="Loading recurring">
          <Skeleton v-for="row in 6" :key="row" height="2.75rem" />
        </div>

        <p v-else-if="!shown.length" class="rows-empty">
          {{ TABS.find((option) => option.id === tab)?.empty }}
        </p>

        <ul v-else class="rows">
          <li v-for="row in shown" :key="row.stream_id" class="row">
            <span class="logo" aria-hidden="true">{{ recurringLabel(row).charAt(0) }}</span>
            <span class="details">
              <button type="button" class="name" aria-haspopup="dialog" @click="edit(row)">
                {{ recurringLabel(row) }}
              </button>
              <span v-if="meta(row)" class="meta">{{ meta(row) }}</span>
            </span>
            <span class="amount" :class="{ 'amount-inflow': row.amount < 0 }">
              {{ formatAmount(row) }}
            </span>
            <span class="answers">
              <!-- Plaid named nobody, so there's no payee to keep an answer for. -->
              <template v-if="!row.kind || !row.merchant_key" />
              <template v-else-if="row.status === 'SUGGESTED'">
                <Button
                  label="Yes"
                  size="small"
                  severity="secondary"
                  :aria-label="`Yes, ${recurringLabel(row)} repeats`"
                  @click="answer(row, true)"
                />
                <Button
                  label="No"
                  size="small"
                  severity="secondary"
                  text
                  :aria-label="`No, ${recurringLabel(row)} doesn’t repeat`"
                  @click="answer(row, false)"
                />
              </template>
              <Button
                v-else-if="row.status === 'DISMISSED'"
                label="Undo"
                size="small"
                severity="secondary"
                text
                :aria-label="`Undo dismissing ${recurringLabel(row)}`"
                @click="answer(row, null)"
              />
              <Button
                v-else
                label="Not recurring"
                size="small"
                severity="secondary"
                text
                :aria-label="`${recurringLabel(row)} doesn’t repeat`"
                @click="answer(row, false)"
              />
            </span>
          </li>
        </ul>
      </div>
    </template>
    <RecurringEditor v-model:visible="editorVisible" :stream="editing" @changed="onEdited" />
  </Dialog>
</template>

<style scoped>
.dialog-heading {
  display: grid;
  gap: 0.25rem;
}

.dialog-title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 550;
  letter-spacing: -0.02em;
}

.dialog-subtitle {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

.dialog-notice {
  display: grid;
  justify-items: start;
  gap: 0.75rem;
}

/* A segmented control: the three lists side by side, one showing at a time. */
.tabs {
  display: flex;
  flex: none;
  gap: 0.125rem;
  padding: 0.1875rem;
  background: var(--app-inset);
  border-radius: var(--app-radius-control);
}

.tab {
  display: inline-flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 0.4rem;
  min-width: 0;
  padding: 0.4rem 0.5rem;
  color: var(--app-text-secondary);
  background: transparent;
  border: 0;
  border-radius: var(--app-radius-chip);
  font: inherit;
  font-size: 0.8125rem;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
}

.tab:hover {
  color: var(--app-text);
}

.tab-selected {
  color: var(--app-text);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-xs);
}

.tab-count {
  color: var(--app-text-subdued);
  font-variant-numeric: tabular-nums;
}

.answer-error {
  flex: none;
  margin-top: 0.75rem;
}

.panel-body {
  flex: 1;
  min-height: 0;
  margin-top: 0.5rem;
  /* Room for the scrollbar up front, so amounts don't shift when a list starts scrolling. */
  padding-right: 0.25rem;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
}

.rows-loading {
  display: grid;
  gap: 0.625rem;
  margin-top: 0.5rem;
}

.rows-empty {
  margin: 1.5rem 0 0;
  color: var(--app-text-secondary);
  line-height: 1.65;
  text-align: center;
}

.rows {
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-height: 3.5rem;
  padding: 0.5rem 0;
}

.row:not(:last-child)::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  left: 2.5rem;
  height: 1px;
  background: var(--app-divider);
}

.logo {
  display: grid;
  flex: none;
  width: 1.75rem;
  height: 1.75rem;
  place-items: center;
  color: var(--app-text-secondary);
  background: var(--app-inset);
  border-radius: 50%;
  font-size: 0.75rem;
  font-weight: 550;
  text-transform: uppercase;
}

.details {
  display: grid;
  flex: 1;
  gap: 0.0625rem;
  justify-items: start;
  min-width: 0;
}

/* The name opens the payee's editor. */
.name {
  max-width: 100%;
  padding: 0;
  overflow: hidden;
  color: var(--app-text);
  background: none;
  border: 0;
  font: inherit;
  font-size: 0.875rem;
  font-weight: 500;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.name:hover {
  text-decoration: underline;
  text-underline-offset: 2px;
}

.name:focus-visible {
  outline: 2px solid var(--app-text);
  outline-offset: 2px;
  border-radius: var(--app-radius-chip);
}

.meta {
  color: var(--app-text-secondary);
  font-size: 0.75rem;
}

.amount {
  flex: none;
  font-size: 0.875rem;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.amount-inflow {
  color: var(--app-success);
}

/* Every list's answers take the same width, so amounts line up down the list. */
.answers {
  display: flex;
  flex: none;
  justify-content: flex-end;
  gap: 0.25rem;
  min-width: 7rem;
}

@media (max-width: 640px) {
  .row {
    flex-wrap: wrap;
  }

  .answers {
    flex-basis: 100%;
    justify-content: flex-start;
    min-width: 0;
    padding-left: 2.5rem;
  }
}
</style>
