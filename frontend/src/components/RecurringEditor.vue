<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import {
  answerRecurringCandidate,
  undoRecurringAnswer,
  type PayeeRef,
  type RecurringStream,
} from '../api/PlaidService'
import { FREQUENCIES, frequencyLabel, recurringLabel } from '../api/plaidLabels'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{ stream?: RecurringStream }>()
const emit = defineEmits<{
  /** The payee's answer changed, so what's on screen should load again. */
  changed: []
}>()

const frequency = ref('')
const saving = ref(false)
const error = ref('')

const name = computed(() => (props.stream ? recurringLabel(props.stream) : ''))
const isPay = computed(() => props.stream?.kind === 'PAYCHECK')
const payee = computed<PayeeRef | undefined>(() =>
  props.stream?.kind && props.stream.merchant_key
    ? { kind: props.stream.kind, merchant_key: props.stream.merchant_key }
    : undefined,
)
const frequencies = computed(() => {
  const current = props.stream?.frequency
  // Keep whatever Plaid said on the list, even a frequency the user can't pick, like unknown.
  return current && !FREQUENCIES.includes(current) ? [current, ...FREQUENCIES] : FREQUENCIES
})

// Starts from the payee each time the editor opens, so a pick left unsaved last time is gone.
watch(
  [() => props.stream, visible],
  ([stream, open]) => {
    if (!open) return
    frequency.value = stream?.frequency ?? ''
    error.value = ''
  },
  { immediate: true },
)

/** Says whether the payee repeats; a no also forgets any frequency the user set. */
async function answer(repeats: boolean) {
  const target = payee.value
  if (target) await run(() => answerRecurringCandidate(target, repeats))
}

async function undo() {
  const target = payee.value
  if (target) await run(() => undoRecurringAnswer(target))
}

/** Setting how often a payee is paid says it repeats, too. */
async function saveFrequency() {
  const target = payee.value
  if (target) await run(() => answerRecurringCandidate(target, true, frequency.value))
}

/**
 * Hands the schedule back to Plaid or the charge dates. One of Plaid's streams needs no answer
 * for that; a payee the user confirmed keeps its yes.
 */
async function automaticFrequency() {
  const target = payee.value
  if (!target) return
  await run(() =>
    props.stream?.status === 'DETECTED'
      ? undoRecurringAnswer(target)
      : answerRecurringCandidate(target, true),
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
    :draggable="false"
    :style="{ width: 'min(26rem, calc(100vw - 2rem))' }"
  >
    <template #header>
      <div v-if="stream" class="editor-heading">
        <h2 class="editor-title">{{ name }}</h2>
        <p class="editor-subtitle">
          {{ formatAmount(stream) }} · {{ frequencyLabel(stream.frequency) }}
        </p>
      </div>
    </template>

    <div v-if="stream" class="editor">
      <p v-if="!payee" class="hint">
        Plaid didn’t say who this is paid to, so it can’t be changed here.
      </p>

      <template v-else>
        <section class="editor-section" aria-labelledby="recurring-editor-repeats">
          <h3 id="recurring-editor-repeats" class="section-title">
            {{ isPay ? 'Is this your pay?' : 'Does this repeat?' }}
          </h3>
          <template v-if="stream.status === 'DISMISSED'">
            <p class="hint">You said this doesn’t repeat.</p>
            <div class="actions">
              <Button
                label="Undo"
                size="small"
                severity="secondary"
                :disabled="saving"
                @click="undo"
              />
            </div>
          </template>
          <template v-else-if="stream.status === 'SUGGESTED'">
            <p class="hint">Jev thinks this repeats.</p>
            <div class="actions">
              <Button
                :label="isPay ? 'Yes, it’s my pay' : 'This repeats'"
                size="small"
                severity="secondary"
                :disabled="saving"
                @click="answer(true)"
              />
              <Button
                :label="isPay ? 'No' : 'It doesn’t'"
                size="small"
                severity="secondary"
                text
                :disabled="saving"
                @click="answer(false)"
              />
            </div>
          </template>
          <template v-else>
            <p class="hint">
              {{
                stream.status === 'DETECTED'
                  ? 'Plaid detects this as recurring.'
                  : 'You said this repeats.'
              }}
              It counts toward your recurring payments and fills in your spending plan.
            </p>
            <div class="actions">
              <Button
                :label="isPay ? 'It’s not my pay' : 'It doesn’t repeat'"
                size="small"
                severity="secondary"
                :disabled="saving"
                @click="answer(false)"
              />
              <Button
                v-if="stream.status === 'CONFIRMED'"
                label="Undo"
                size="small"
                severity="secondary"
                text
                :disabled="saving"
                @click="undo"
              />
            </div>
          </template>
        </section>

        <section
          v-if="stream.status !== 'DISMISSED'"
          class="editor-section"
          aria-labelledby="recurring-editor-frequency"
        >
          <h3 id="recurring-editor-frequency" class="section-title">How often</h3>
          <label class="field">
            <span class="field-label">Frequency</span>
            <select
              v-model="frequency"
              class="field-select"
              :disabled="saving"
              aria-label="Frequency"
            >
              <option
                v-for="option in frequencies"
                :key="option"
                :value="option"
                :disabled="!FREQUENCIES.includes(option)"
              >
                {{ frequencyLabel(option) }}
              </option>
            </select>
          </label>
          <p class="hint">
            <template v-if="stream.frequency_set">You’ve set this yourself.</template>
            <template v-else>Worked out from when {{ name }} has been paid.</template>
            Your spending plan uses it to turn the amount into a monthly one.
          </p>
          <div class="actions">
            <Button
              label="Save"
              size="small"
              :loading="saving"
              :disabled="saving || frequency === stream.frequency"
              @click="saveFrequency"
            />
            <Button
              v-if="stream.frequency_set"
              label="Go back to automatic"
              size="small"
              severity="secondary"
              text
              :disabled="saving"
              @click="automaticFrequency"
            />
          </div>
        </section>
      </template>

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
