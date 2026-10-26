<script setup lang="ts">
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable, { type DataTablePageEvent } from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import PayeeIcon from './PayeeIcon.vue'
import TransactionEditor from './TransactionEditor.vue'
import { getTransactionPage, type PlaidTransaction } from '../api/PlaidService'
import {
  bucketStyle,
  categoryLabel,
  formatTransactionAmount,
  transactionLabel,
} from '../api/plaidLabels'
import { PHONE_QUERY, useMediaQuery } from '../useMediaQuery'

const PAGE_SIZE = 50

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  accountId?: string
  /** Shown under the title so it's clear which account the table covers. */
  accountLabel?: string
}>()
const emit = defineEmits<{
  /** A payee was corrected or answered, so other views of it should load again. */
  changed: []
}>()

const transactions = ref<PlaidTransaction[]>([])
const total = ref(0)
const first = ref(0)
const loading = ref(false)
const error = ref('')
const editing = ref<PlaidTransaction>()
const editorVisible = ref(false)
// Phones get a list grouped by day instead of a five-column table.
const compact = useMediaQuery(PHONE_QUERY)

function edit(transaction: PlaidTransaction) {
  editing.value = transaction
  editorVisible.value = true
}

function onEdited() {
  void loadPage(first.value / PAGE_SIZE)
  emit('changed')
}

// Only the newest request may fill the table, in case pages or accounts change mid-flight.
let latestRequest = 0

watch(
  [visible, () => props.accountId],
  ([open]) => {
    if (open) void loadPage(0)
  },
  { immediate: true },
)

async function loadPage(page: number) {
  const request = ++latestRequest
  loading.value = true
  error.value = ''
  try {
    const result = await getTransactionPage(page, PAGE_SIZE, props.accountId)
    if (request !== latestRequest) return
    transactions.value = result.transactions
    total.value = result.total
    first.value = page * PAGE_SIZE
  } catch {
    if (request === latestRequest) error.value = 'We couldn’t load your transactions.'
  } finally {
    if (request === latestRequest) loading.value = false
  }
}

function onPage(event: DataTablePageEvent) {
  void loadPage(event.page)
}

// Plaid dates are calendar days, so parse them locally instead of as UTC instants.
function formatDate(date: string) {
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(`${date}T00:00:00`))
}

/** A day's heading in the phone list, with the year only when it isn't this year. */
function formatDay(date: string) {
  const day = new Date(`${date}T00:00:00`)
  return new Intl.DateTimeFormat('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    year: day.getFullYear() === new Date().getFullYear() ? undefined : 'numeric',
  }).format(day)
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    dismissable-mask
    block-scroll
    :draggable="false"
    class="app-sheet"
    :style="{ width: 'min(64rem, calc(100vw - 2rem))', height: '88dvh', maxHeight: '92dvh' }"
    :breakpoints="{ '640px': '100vw' }"
    :pt="{
      content: {
        style: { display: 'flex', flexDirection: 'column', flex: '1', minHeight: '0' },
      },
    }"
  >
    <template #header>
      <div class="dialog-heading">
        <!-- Opening focuses the title rather than ringing the close button. -->
        <h2 class="dialog-title" tabindex="-1" autofocus>Transactions</h2>
        <p v-if="accountLabel || total" class="dialog-subtitle">
          <template v-if="accountLabel">{{ accountLabel }}</template>
          <template v-if="accountLabel && total"> · </template>
          <template v-if="total">{{ total.toLocaleString('en-US') }} total</template>
        </p>
      </div>
    </template>

    <div v-if="error" class="dialog-notice">
      <Message severity="error">{{ error }}</Message>
      <Button label="Try again" severity="secondary" @click="loadPage(first / PAGE_SIZE)" />
    </div>

    <DataTable
      v-else
      :value="transactions"
      data-key="transaction_id"
      lazy
      paginator
      :rows="PAGE_SIZE"
      :first="first"
      :total-records="total"
      :loading="loading"
      scrollable
      scroll-height="flex"
      class="transactions-table"
      :class="{ 'transactions-compact': compact }"
      :show-headers="!compact"
      :row-group-mode="compact ? 'subheader' : undefined"
      group-rows-by="date"
      :paginator-template="compact ? 'PrevPageLink CurrentPageReport NextPageLink' : undefined"
      current-page-report-template="Page {currentPage} of {totalPages}"
      aria-label="All transactions"
      @page="onPage"
    >
      <template #empty>
        <p class="table-empty">
          No transactions yet. They’ll appear here once your bank sends them.
        </p>
      </template>

      <template v-if="compact" #groupheader="{ data }: { data: PlaidTransaction }">
        <span class="day">{{ formatDay(data.date) }}</span>
      </template>

      <Column v-if="compact" class="compact-column">
        <template #body="{ data }: { data: PlaidTransaction }">
          <button type="button" class="compact-row" aria-haspopup="dialog" @click="edit(data)">
            <PayeeIcon :logo-url="data.logo_url" :category="data.category" />
            <span class="compact-details">
              <span class="compact-name">
                <span class="name">{{ transactionLabel(data) }}</span>
                <span v-if="data.pending" class="pending">Pending</span>
              </span>
              <span class="compact-meta">
                <span
                  class="swatch"
                  :style="{ backgroundColor: bucketStyle(data).color }"
                  aria-hidden="true"
                />
                <span class="compact-meta-text">
                  {{ bucketStyle(data).label }} ·
                  {{ categoryLabel(data.category ?? 'UNCATEGORIZED') }}
                </span>
              </span>
            </span>
            <span class="amount" :class="{ 'amount-inflow': data.amount < 0 }">
              {{ formatTransactionAmount(data) }}
            </span>
          </button>
        </template>
      </Column>

      <Column v-if="!compact" header="Date" class="date-column">
        <template #body="{ data }: { data: PlaidTransaction }">
          {{ formatDate(data.date) }}
        </template>
      </Column>

      <Column v-if="!compact" header="Description">
        <template #body="{ data }: { data: PlaidTransaction }">
          <span class="description">
            <PayeeIcon class="logo" :logo-url="data.logo_url" :category="data.category" />
            <button
              type="button"
              class="name name-button"
              aria-haspopup="dialog"
              @click="edit(data)"
            >
              {{ transactionLabel(data) }}
            </button>
            <span v-if="data.pending" class="pending">Pending</span>
          </span>
        </template>
      </Column>

      <Column v-if="!compact" header="Category">
        <template #body="{ data }: { data: PlaidTransaction }">
          <span class="secondary">{{ categoryLabel(data.category ?? 'UNCATEGORIZED') }}</span>
        </template>
      </Column>

      <Column v-if="!compact" header="Bucket">
        <template #body="{ data }: { data: PlaidTransaction }">
          <span class="bucket">
            <span
              class="swatch"
              :style="{ backgroundColor: bucketStyle(data).color }"
              aria-hidden="true"
            />
            {{ bucketStyle(data).label }}
          </span>
        </template>
      </Column>

      <Column v-if="!compact" header="Amount" class="amount-column">
        <template #body="{ data }: { data: PlaidTransaction }">
          <span class="amount" :class="{ 'amount-inflow': data.amount < 0 }">
            {{ formatTransactionAmount(data) }}
          </span>
        </template>
      </Column>
    </DataTable>
    <TransactionEditor v-model:visible="editorVisible" :transaction="editing" @changed="onEdited" />
  </Dialog>
</template>

<style scoped>
.dialog-heading {
  display: grid;
  gap: 0.25rem;
}

.dialog-title {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: 550;
  letter-spacing: -0.02em;
}

.dialog-title:focus {
  outline: none;
}

.dialog-subtitle {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--text-sm);
}

.dialog-notice {
  display: grid;
  justify-items: start;
  gap: 0.75rem;
}

.transactions-table {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.transactions-table :deep(th) {
  color: var(--app-text-secondary);
  font-size: var(--text-sm);
  font-weight: 500;
}

.transactions-table :deep(td) {
  font-size: var(--text-base);
}

.transactions-table :deep(.date-column) {
  white-space: nowrap;
}

.transactions-table :deep(.amount-column) {
  text-align: right;
}

.transactions-table :deep(th.amount-column .p-datatable-column-header-content) {
  justify-content: flex-end;
}

.description {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  min-width: 0;
}

.logo {
  width: 1.75rem;
  height: 1.75rem;
}

.name {
  overflow: hidden;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* The name opens the transaction's editor. */
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

.pending {
  flex: none;
  padding: 0.125rem 0.375rem;
  color: var(--app-text-secondary);
  font-size: var(--text-2xs);
  font-weight: 500;
  background: var(--app-inset);
  border-radius: var(--app-radius-chip);
}

.secondary {
  color: var(--app-text-secondary);
}

.bucket {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  white-space: nowrap;
}

.swatch {
  flex: none;
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 50%;
}

.amount {
  font-weight: 550;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.amount-inflow {
  color: var(--app-success);
}

/* The phone list: each day under its own heading, each transaction one tappable row. */
.transactions-compact :deep(.p-datatable-row-group-header td) {
  padding: 1rem 0 0.375rem;
  background: var(--app-surface);
  border: 0;
}

.day {
  color: var(--app-text-secondary);
  font-size: var(--text-xs);
  font-weight: 550;
  letter-spacing: 0.01em;
}

.transactions-compact :deep(td.compact-column) {
  padding: 0;
}

/* The last row of a day already sits above the next day's heading, so it needs no rule. */
.transactions-compact :deep(tr:has(+ .p-datatable-row-group-header) td) {
  border-bottom: 0;
}

.compact-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  min-height: 3.5rem;
  padding: 0.625rem 0;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
}

/* A tap shows where it landed, as on Home's lists. */
.compact-row:active {
  background: var(--app-inset);
  border-radius: var(--app-radius-chip);
  box-shadow: 0 0 0 0.375rem var(--app-inset);
}

.compact-row:focus-visible {
  outline-offset: -2px;
  border-radius: var(--app-radius-chip);
}

.compact-details {
  display: grid;
  flex: 1;
  gap: 0.125rem;
  min-width: 0;
}

.compact-name {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  min-width: 0;
}

.compact-meta {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  min-width: 0;
  color: var(--app-text-secondary);
  font-size: var(--text-xs);
}

.compact-meta-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.transactions-compact :deep(.p-paginator) {
  padding: 0.5rem 0;
}

.transactions-compact :deep(.p-paginator-content) {
  justify-content: space-between;
  width: 100%;
}

.transactions-compact :deep(.p-paginator-current) {
  color: var(--app-text-secondary);
  font-size: var(--text-sm);
}

.table-empty {
  margin: 0;
  padding: 1rem 0;
  color: var(--app-text-secondary);
}
</style>
