<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
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

const STATUS_LABELS: Record<RecurringStatus, string> = {
  DETECTED: 'Recurring',
  CONFIRMED: 'Confirmed by you',
  SUGGESTED: 'Possibly recurring',
  DISMISSED: 'Not recurring',
}

// What counts as recurring first, then what's waiting on an answer, then what the user ruled out.
const STATUS_ORDER: RecurringStatus[] = ['DETECTED', 'CONFIRMED', 'SUGGESTED', 'DISMISSED']

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  accountId?: string
  /** Shown under the title so it's clear which account the table covers. */
  accountLabel?: string
}>()
const emit = defineEmits<{
  /** A payee was answered, so other views of it should load again. */
  changed: []
}>()

const rows = ref<RecurringStream[]>([])
const loading = ref(false)
const error = ref('')
const editing = ref<RecurringStream>()
const editorVisible = ref(false)

const counted = computed(
  () => rows.value.filter((row) => row.status === 'DETECTED' || row.status === 'CONFIRMED').length,
)

function edit(stream: RecurringStream) {
  editing.value = stream
  editorVisible.value = true
}

function onEdited() {
  void load()
  emit('changed')
}

// The row whose answer is being saved; answers are saved one at a time.
const answering = ref<string>()
const answerError = ref('')

/**
 * Saves whether a row's payee repeats straight from the table, or with null forgets the answer.
 * Setting how often it's paid is left to the editor.
 */
async function answer(stream: RecurringStream, repeats: boolean | null) {
  if (answering.value || !stream.kind || !stream.merchant_key) return
  const payee = { kind: stream.kind, merchant_key: stream.merchant_key }
  answering.value = stream.stream_id
  answerError.value = ''
  try {
    if (repeats === null) await undoRecurringAnswer(payee)
    else await answerRecurringCandidate(payee, repeats)
    onEdited()
  } catch {
    answerError.value = 'We couldn’t save your answer. Please try again.'
  } finally {
    answering.value = undefined
  }
}

// Only the newest request may fill the table, in case the account changes mid-flight.
let latestRequest = 0

watch(
  [visible, () => props.accountId],
  ([open]) => {
    if (open) void load()
  },
  { immediate: true },
)

/** Plaid's streams and Jev's candidates together, answered or not. */
async function load() {
  const request = ++latestRequest
  loading.value = true
  error.value = ''
  try {
    const [streams, candidates] = await Promise.all([
      getRecurringTransactions(props.accountId, STREAM_LIMIT, true),
      // Candidates are only guesses, so Plaid's streams still show when they can't be loaded.
      getRecurringCandidates(props.accountId).catch(() => []),
    ])
    if (request !== latestRequest) return
    rows.value = [...streams, ...candidates].sort(
      (a, b) =>
        STATUS_ORDER.indexOf(a.status) - STATUS_ORDER.indexOf(b.status) ||
        recurringLabel(a).localeCompare(recurringLabel(b)),
    )
  } catch {
    if (request === latestRequest) error.value = 'We couldn’t load your recurring transactions.'
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

function when(stream: RecurringStream) {
  if (stream.status === 'DISMISSED') return ''
  if (stream.next_date) return `Next ${formatDate(stream.next_date)}`
  if (stream.last_date) return `Last ${formatDate(stream.last_date)}`
  return ''
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
    :style="{ width: 'min(56rem, calc(100vw - 2rem))', height: '88dvh', maxHeight: '92dvh' }"
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
        <p v-if="accountLabel || counted" class="dialog-subtitle">
          <template v-if="accountLabel">{{ accountLabel }}</template>
          <template v-if="accountLabel && counted"> · </template>
          <template v-if="counted">{{ counted }} recurring</template>
        </p>
      </div>
    </template>

    <div v-if="error" class="dialog-notice">
      <Message severity="error">{{ error }}</Message>
      <Button label="Try again" severity="secondary" @click="load" />
    </div>

    <Message v-if="answerError && !error" severity="error" class="answer-error">
      {{ answerError }}
    </Message>

    <DataTable
      v-if="!error"
      :value="rows"
      data-key="stream_id"
      :loading="loading"
      scrollable
      scroll-height="flex"
      class="recurring-table"
      aria-label="All recurring transactions"
    >
      <template #empty>
        <p class="table-empty">
          No recurring transactions found yet. Plaid can take up to a day to find them after you
          link a bank.
        </p>
      </template>

      <Column header="Name">
        <template #body="{ data }: { data: RecurringStream }">
          <span class="description">
            <span class="logo logo-fallback" aria-hidden="true">
              {{ recurringLabel(data).charAt(0) }}
            </span>
            <button
              type="button"
              class="name name-button"
              aria-haspopup="dialog"
              @click="edit(data)"
            >
              {{ recurringLabel(data) }}
            </button>
          </span>
        </template>
      </Column>

      <Column header="How often">
        <template #body="{ data }: { data: RecurringStream }">
          <span class="secondary">
            {{ data.status === 'DISMISSED' ? '' : frequencyLabel(data.frequency) }}
          </span>
        </template>
      </Column>

      <Column header="When" class="date-column">
        <template #body="{ data }: { data: RecurringStream }">
          <span class="secondary">{{ when(data) }}</span>
        </template>
      </Column>

      <Column header="Status">
        <template #body="{ data }: { data: RecurringStream }">
          <span :class="{ secondary: data.status === 'DISMISSED' }">
            {{ STATUS_LABELS[data.status] }}
          </span>
        </template>
      </Column>

      <Column header="Amount" class="amount-column">
        <template #body="{ data }: { data: RecurringStream }">
          <span class="amount" :class="{ 'amount-inflow': data.amount < 0 }">
            {{ formatAmount(data) }}
          </span>
        </template>
      </Column>

      <Column header="Recurring?" class="answer-column">
        <template #body="{ data }: { data: RecurringStream }">
          <span v-if="data.kind && data.merchant_key" class="answers">
            <template v-if="data.status === 'SUGGESTED'">
              <Button
                label="Yes"
                size="small"
                severity="secondary"
                :disabled="answering !== undefined"
                :aria-label="`Yes, ${recurringLabel(data)} repeats`"
                @click="answer(data, true)"
              />
              <Button
                label="No"
                size="small"
                severity="secondary"
                text
                :disabled="answering !== undefined"
                :aria-label="`No, ${recurringLabel(data)} doesn’t repeat`"
                @click="answer(data, false)"
              />
            </template>
            <Button
              v-else-if="data.status === 'DISMISSED'"
              label="Undo"
              size="small"
              severity="secondary"
              text
              :disabled="answering !== undefined"
              :aria-label="`Undo dismissing ${recurringLabel(data)}`"
              @click="answer(data, null)"
            />
            <Button
              v-else
              label="Not recurring"
              size="small"
              severity="secondary"
              text
              :disabled="answering !== undefined"
              :aria-label="`${recurringLabel(data)} doesn’t repeat`"
              @click="answer(data, false)"
            />
          </span>
        </template>
      </Column>
    </DataTable>
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

.recurring-table {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.recurring-table :deep(th) {
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  font-weight: 500;
}

.recurring-table :deep(td) {
  font-size: 0.875rem;
}

.recurring-table :deep(.date-column) {
  white-space: nowrap;
}

.recurring-table :deep(.amount-column) {
  text-align: right;
}

.recurring-table :deep(th.amount-column .p-datatable-column-header-content) {
  justify-content: flex-end;
}

.answer-error {
  margin-bottom: 0.75rem;
}

.recurring-table :deep(.answer-column) {
  white-space: nowrap;
}

.answers {
  display: inline-flex;
  gap: 0.25rem;
}

.table-empty {
  margin: 0;
  color: var(--app-text-secondary);
}

.description {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  min-width: 0;
}

.logo {
  flex: none;
  display: grid;
  width: 1.5rem;
  height: 1.5rem;
  place-items: center;
  background: var(--app-inset);
  border-radius: 50%;
}

.logo-fallback {
  color: var(--app-text-secondary);
  font-size: 0.6875rem;
  font-weight: 550;
  text-transform: uppercase;
}

.name {
  overflow: hidden;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* The name opens the payee's editor. */
.name-button {
  min-width: 0;
  padding: 0;
  color: inherit;
  font: inherit;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
}

.name-button:hover {
  text-decoration: underline;
  text-underline-offset: 2px;
}

.name-button:focus-visible {
  outline: 2px solid var(--app-text);
  outline-offset: 2px;
  border-radius: var(--app-radius-chip);
}

.secondary {
  color: var(--app-text-secondary);
}

.amount {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.amount-inflow {
  color: var(--app-success);
}
</style>
