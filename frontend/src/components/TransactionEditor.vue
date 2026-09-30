<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import {
  answerRecurringCandidate,
  correctPayee,
  undoPayeeCorrection,
  undoRecurringAnswer,
  type PayeeRef,
  type PlaidTransaction,
  type SpendingBucket,
} from '../api/PlaidService'
import {
  BUCKET_STYLES,
  SPENDING_CATEGORIES,
  categoryLabel,
  formatTransactionAmount,
  transactionLabel,
} from '../api/plaidLabels'

/** The buckets spending can be moved to, in the plan's order. */
const BUCKETS: SpendingBucket[] = [
  'FIXED_COSTS',
  'GUILT_FREE',
  'SAVINGS',
  'INVESTMENTS',
  'NOT_COUNTED',
]

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{ transaction?: PlaidTransaction }>()
const emit = defineEmits<{
  /** Something about the payee changed, so what's on screen should load again. */
  changed: []
}>()

const bucket = ref<SpendingBucket | ''>('')
const category = ref('')
const saving = ref(false)
const error = ref('')

// Spending has a bucket and category; pay coming in only has whether it repeats.
const isSpending = computed(
  () => props.transaction?.payee_kind === 'BILL' && props.transaction.amount > 0,
)
const name = computed(() => (props.transaction ? transactionLabel(props.transaction) : ''))
const payee = computed<PayeeRef | undefined>(() =>
  props.transaction?.payee_key && props.transaction.payee_kind
    ? { kind: props.transaction.payee_kind, merchant_key: props.transaction.payee_key }
    : undefined,
)
const corrected = computed(
  () =>
    !!props.transaction &&
    (props.transaction.bucket_corrected || props.transaction.category_corrected),
)
const changed = computed(
  () =>
    !!props.transaction &&
    (bucket.value !== (props.transaction.bucket ?? '') ||
      category.value !== (props.transaction.category ?? '')),
)
const categories = computed(() => {
  const current = props.transaction?.category
  // Keep whatever Plaid said on the list, even a category it added after this list was written.
  return current && !SPENDING_CATEGORIES.includes(current)
    ? [current, ...SPENDING_CATEGORIES]
    : SPENDING_CATEGORIES
})

// Starts from the transaction each time the editor opens, so picks left unsaved last time are gone.
watch(
  [() => props.transaction, visible],
  ([transaction, open]) => {
    if (!open) return
    bucket.value = transaction?.bucket ?? ''
    category.value = transaction?.category ?? ''
    error.value = ''
  },
  { immediate: true },
)

/**
 * Saves the bucket and category for every charge from this payee. A part the user didn't touch
 * and hadn't corrected before stays automatic, so sorting and Plaid keep deciding it.
 */
async function save() {
  const transaction = props.transaction
  if (!transaction?.payee_key) return
  const bucketSet = bucket.value !== (transaction.bucket ?? '') || transaction.bucket_corrected
  const categorySet =
    category.value !== (transaction.category ?? '') || transaction.category_corrected
  await run(() =>
    correctPayee(
      transaction.payee_key!,
      bucketSet && bucket.value ? bucket.value : null,
      categorySet && category.value ? category.value : null,
    ),
  )
}

async function resetToAutomatic() {
  const payeeKey = props.transaction?.payee_key
  if (payeeKey) await run(() => undoPayeeCorrection(payeeKey))
}

async function answer(repeats: boolean | null) {
  const target = payee.value
  if (!target) return
  await run(() =>
    repeats === null ? undoRecurringAnswer(target) : answerRecurringCandidate(target, repeats),
  )
}

async function run(change: () => Promise<void>) {
  saving.value = true
  error.value = ''
  try {
    await change()
    emit('changed')
    visible.value = false
  } catch {
    error.value = 'We couldn’t save that. Please try again.'
  } finally {
    saving.value = false
  }
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
    :draggable="false"
    :style="{ width: 'min(26rem, calc(100vw - 2rem))' }"
  >
    <template #header>
      <div v-if="transaction" class="editor-heading">
        <h2 class="editor-title">{{ name }}</h2>
        <p class="editor-subtitle">
          {{ formatDate(transaction.date) }} · {{ formatTransactionAmount(transaction) }}
        </p>
      </div>
    </template>

    <div v-if="transaction" class="editor">
      <section
        v-if="isSpending && transaction.payee_key"
        class="editor-section"
        aria-labelledby="editor-classify"
      >
        <h3 id="editor-classify" class="section-title">How it counts</h3>
        <label class="field">
          <span class="field-label">Bucket</span>
          <select v-model="bucket" class="field-select" :disabled="saving" aria-label="Bucket">
            <option v-if="!transaction.bucket" value="" disabled>Not sorted yet</option>
            <option v-for="option in BUCKETS" :key="option" :value="option">
              {{ BUCKET_STYLES[option].label }}
            </option>
          </select>
        </label>
        <label class="field">
          <span class="field-label">Category</span>
          <select v-model="category" class="field-select" :disabled="saving" aria-label="Category">
            <option v-if="!transaction.category" value="" disabled>Uncategorized</option>
            <option v-for="option in categories" :key="option" :value="option">
              {{ categoryLabel(option) }}
            </option>
          </select>
        </label>
        <p class="hint">
          Applies to every charge from {{ name }}, including future ones.
          <template v-if="corrected">You’ve set this yourself, so it won’t be re-sorted.</template>
        </p>
        <div class="actions">
          <Button
            label="Save"
            size="small"
            :loading="saving"
            :disabled="saving || (!changed && !corrected)"
            @click="save"
          />
          <Button
            v-if="corrected"
            label="Go back to automatic"
            size="small"
            severity="secondary"
            text
            :disabled="saving"
            @click="resetToAutomatic"
          />
        </div>
      </section>

      <section v-if="payee" class="editor-section" aria-labelledby="editor-repeats">
        <h3 id="editor-repeats" class="section-title">
          {{ transaction.payee_kind === 'PAYCHECK' ? 'Is this your pay?' : 'Does this repeat?' }}
        </h3>
        <p v-if="transaction.recurring === 'DETECTED'" class="hint">
          Plaid already detects this as recurring.
        </p>
        <template
          v-else-if="transaction.recurring === 'CONFIRMED' || transaction.recurring === 'DISMISSED'"
        >
          <p class="hint">
            {{
              transaction.recurring === 'CONFIRMED'
                ? 'You said this repeats; it counts toward your recurring payments.'
                : 'You said this doesn’t repeat.'
            }}
          </p>
          <div class="actions">
            <Button
              label="Undo"
              size="small"
              severity="secondary"
              text
              :disabled="saving"
              @click="answer(null)"
            />
          </div>
        </template>
        <template v-else>
          <p v-if="transaction.recurring === 'SUGGESTED'" class="hint">Jev thinks this repeats.</p>
          <div class="actions">
            <Button
              :label="transaction.payee_kind === 'PAYCHECK' ? 'Yes, it’s my pay' : 'This repeats'"
              size="small"
              severity="secondary"
              :disabled="saving"
              @click="answer(true)"
            />
            <Button
              :label="transaction.payee_kind === 'PAYCHECK' ? 'No' : 'It doesn’t'"
              size="small"
              severity="secondary"
              text
              :disabled="saving"
              @click="answer(false)"
            />
          </div>
        </template>
      </section>

      <Message v-if="error" severity="error">{{ error }}</Message>
    </div>
  </Dialog>
</template>

<style scoped>
.editor-heading {
  display: grid;
  gap: 0.25rem;
  min-width: 0;
}

.editor-title {
  margin: 0;
  overflow: hidden;
  font-size: 1.0625rem;
  font-weight: 550;
  letter-spacing: -0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-subtitle {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  font-variant-numeric: tabular-nums;
}

.editor {
  display: grid;
  gap: 1.25rem;
}

.editor-section {
  display: grid;
  gap: 0.625rem;
}

.editor-section + .editor-section {
  padding-top: 1.25rem;
  border-top: 1px solid var(--app-divider);
}

.section-title {
  margin: 0;
  color: var(--app-text);
  font-size: 0.875rem;
  font-weight: 550;
}

.field {
  display: grid;
  gap: 0.3rem;
}

.field-label {
  color: var(--app-text-secondary);
  font-size: 0.75rem;
  font-weight: 500;
}

.field-select {
  width: 100%;
  appearance: none;
  padding: 0.45rem 1.75rem 0.45rem 0.625rem;
  color: var(--app-text);
  background-color: var(--app-surface);
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' fill='none' stroke='%23737373' stroke-linecap='round' stroke-linejoin='round' stroke-width='1.75' viewBox='0 0 24 24'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-position: right 0.45rem center;
  background-repeat: no-repeat;
  border: 1px solid var(--app-control-border);
  border-radius: var(--app-radius-chip);
  font: inherit;
  font-size: 0.875rem;
}

.hint {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  line-height: 1.5;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}
</style>
