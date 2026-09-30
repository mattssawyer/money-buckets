<script setup lang="ts">
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable, { type DataTablePageEvent } from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import TransactionEditor from './TransactionEditor.vue'
import { getTransactionPage, type PlaidTransaction } from '../api/PlaidService'
import {
  bucketStyle,
  categoryLabel,
  formatTransactionAmount,
  transactionLabel,
} from '../api/plaidLabels'

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
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    dismissable-mask
    block-scroll
    :draggable="false"
    :style="{ width: 'min(64rem, calc(100vw - 2rem))', height: '88dvh', maxHeight: '92dvh' }"
    :breakpoints="{ '640px': 'calc(100vw - 1.5rem)' }"
    :pt="{
      content: {
        style: { display: 'flex', flexDirection: 'column', flex: '1', minHeight: '0' },
      },
    }"
  >
    <template #header>
      <div class="dialog-heading">
        <h2 class="dialog-title">Transactions</h2>
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
      aria-label="All transactions"
      @page="onPage"
    >
      <template #empty>
        <p class="table-empty">
          No transactions yet. They’ll appear here once your bank sends them.
        </p>
      </template>

      <Column header="Date" class="date-column">
        <template #body="{ data }: { data: PlaidTransaction }">
          {{ formatDate(data.date) }}
        </template>
      </Column>

      <Column header="Description">
        <template #body="{ data }: { data: PlaidTransaction }">
          <span class="description">
            <img v-if="data.logo_url" class="logo" :src="data.logo_url" alt="" />
            <span v-else class="logo logo-fallback" aria-hidden="true">
              {{ transactionLabel(data).charAt(0) }}
            </span>
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

      <Column header="Category">
        <template #body="{ data }: { data: PlaidTransaction }">
          <span class="secondary">{{ categoryLabel(data.category ?? 'UNCATEGORIZED') }}</span>
        </template>
      </Column>

      <Column header="Bucket">
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

      <Column header="Amount" class="amount-column">
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

.transactions-table {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.transactions-table :deep(th) {
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  font-weight: 500;
}

.transactions-table :deep(td) {
  font-size: 0.875rem;
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
  flex: none;
  display: grid;
  width: 1.5rem;
  height: 1.5rem;
  place-items: center;
  object-fit: cover;
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
  font-size: 0.6875rem;
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

.table-empty {
  margin: 0;
  padding: 1rem 0;
  color: var(--app-text-secondary);
}
</style>
