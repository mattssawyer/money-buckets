<script setup lang="ts">
import { ChevronRight, CircleMinus, Info, Plus, Repeat } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import {
  getRecurringCandidates,
  getRecurringTransactions,
  getSpendingByBucket,
  type RecurringStream,
} from '../api/PlaidService'
import { recurringLabel } from '../api/plaidLabels'
import { saveSpendingPlan } from '../api/SpendingPlanService'
import {
  estimateMonthlyTakeHome,
  planFromRecurring,
  recurringChanges,
  recurringItem,
  unplacedBills,
} from '../spendingPlan/fromRecurring'
import { formatPlanAmount, parseAmount } from '../spendingPlan/money'
import {
  DEFAULT_BUFFER_PERCENT,
  PLAN_TARGETS,
  PLAN_TITLES,
  defaultPlan,
  evaluatePlan,
  lineAmount,
  type BucketId,
  type PlanDraft,
  type PlanItemDraft,
  type PlanLineDraft,
} from '../spendingPlan/plan'
import { BUCKET_IDS, fromSaved, toSaveRequest, type SavedPlan } from '../spendingPlan/savedPlan'
import { withConfirmed } from '../spending/recurring'
import { historyRange, lineAverages, spendingHistory } from '../spendingPlan/spendingHistory'
import RecurringDialog from './RecurringDialog.vue'
import SpendingExplorer from './SpendingExplorer.vue'

const props = defineProps<{
  /** Edit this saved plan. Without it, the dialog runs a new setup from the tracked accounts' bills. */
  saved?: SavedPlan
  /** A new setup will replace an existing saved plan. */
  replacing?: boolean
}>()

const emit = defineEmits<{
  saved: [plan: SavedPlan]
}>()

interface PlanItem extends PlanItemDraft {
  id: string
}

interface PlanRow extends PlanLineDraft {
  id: string
  items: PlanItem[]
}

type PlanRows = Record<BucketId, PlanRow[]>

interface Bucket {
  id: BucketId
  title: string
  /** What one line in this bucket is called, for placeholders and labels. */
  lineNoun: string
  addLabel: string
  /** Whether lines can be marked as taken out of the paycheck. */
  paycheckOption?: boolean
  /** Whether the bucket gets the spreadsheet's miscellaneous buffer on top of its lines. */
  buffer?: boolean
}

const BUCKETS: Bucket[] = [
  {
    id: 'fixedCosts',
    title: PLAN_TITLES.fixedCosts,
    lineNoun: 'Cost',
    addLabel: 'Add a cost',
    buffer: true,
  },
  {
    id: 'investments',
    title: PLAN_TITLES.investments,
    lineNoun: 'Investment',
    addLabel: 'Add an investment',
    paycheckOption: true,
  },
  {
    id: 'savings',
    title: PLAN_TITLES.savings,
    lineNoun: 'Savings goal',
    addLabel: 'Add a savings goal',
  },
]

const editing = props.saved != null
const loadingEstimates = ref(!editing)
// A saved plan shows at once, but saving it or breaking a line down waits for the defaults: a
// blank line would otherwise be saved, or carried into its first item, as nothing.
const loadingDefaults = ref(editing)
const takeHome = ref<number | null>(null)
const bufferPercent = ref<number | null>(DEFAULT_BUFFER_PERCENT)
const plan = ref<PlanRows>({ fixedCosts: [], investments: [], savings: [] })
const expandedRows = ref(new Set<string>())
const saving = ref(false)
const saveError = ref('')
// Where there's no room for the plan and the user's spending side by side, one shows at a time.
const showSpending = ref(false)
// Payments Jev thinks repeat that the user hasn't confirmed or dismissed, so they aren't filled in.
const unansweredCandidates = ref(0)
// Recurring bills Jev put on no line, or hasn't judged yet, for the user to place or skip.
const unplaced = ref<RecurringStream[]>([])
// The row each unplaced bill will be added to, by stream id.
const placeInto = ref<Record<string, string>>({})
const recurringVisible = ref(false)
const recurringStartOn = ref<'recurring' | 'possible'>('recurring')
// The recurring payments the plan was last brought up to date with, so answers given in the
// recurring dialog can be told apart from bills the user skipped or took off a line.
let recurringBefore: Promise<RecurringStream[] | null> | null = null

// What the user spends in a month on each line, by its name, for blank lines' default amounts.
const lineDefaults = ref(new Map<string, number>())

/** How many whole months of spending a line's default amount is averaged over. */
const DEFAULT_MONTHS = 3

/**
 * The plan as it will be saved: a line the user left blank counts at its default amount, if it
 * has one.
 */
const filledPlan = computed<PlanDraft>(() => ({
  fixedCosts: plan.value.fixedCosts.map(filled),
  investments: plan.value.investments.map(filled),
  savings: plan.value.savings.map(filled),
}))

const evaluation = computed(() =>
  evaluatePlan(takeHome.value, filledPlan.value, bufferPercent.value),
)
const guiltFree = computed(() => evaluation.value.guiltFree)

if (props.saved) {
  takeHome.value = props.saved.takeHome
  bufferPercent.value = props.saved.bufferPercent
  setPlan(props.saved.plan)
}

onMounted(async () => {
  // A saved plan keeps its own amounts; only its blank lines get defaults.
  if (!editing) return loadEstimates()
  try {
    await loadDefaults()
  } finally {
    loadingDefaults.value = false
  }
})

async function save() {
  saving.value = true
  saveError.value = ''
  try {
    // The plan follows whichever accounts are tracked, so it isn't tied to one.
    const saved = await saveSpendingPlan(
      toSaveRequest(null, takeHome.value, bufferPercent.value ?? 0, filledPlan.value),
    )
    emit('saved', fromSaved(saved))
  } catch {
    saveError.value = 'We couldn’t save your plan. Try again.'
  } finally {
    saving.value = false
  }
}

/**
 * Fills in amounts from the recurring transactions in every tracked account: Plaid's, and the
 * ones the user confirmed. Plaid's alone still fill in when candidates can't be loaded. Lines
 * with no recurring bill default to what the user spends on them, when that can be loaded.
 */
async function loadEstimates() {
  loadingEstimates.value = true
  try {
    const loading = loadRecurring()
    recurringBefore = loading.catch(() => null)
    const [streams] = await Promise.all([loading, loadDefaults()])
    const estimated = estimateMonthlyTakeHome(streams)
    takeHome.value = estimated
    setPlan(planFromRecurring(streams))
    unplaced.value = unplacedBills(streams)
  } catch {
    takeHome.value = null
    setPlan(defaultPlan())
  } finally {
    loadingEstimates.value = false
  }
}

/**
 * Plaid's recurring payments in every tracked account and the ones the user confirmed. Plaid's
 * alone still load when candidates can't.
 */
async function loadRecurring() {
  const [found, candidates] = await Promise.all([
    getRecurringTransactions(undefined, 50),
    getRecurringCandidates().catch(() => []),
  ])
  unansweredCandidates.value = candidates.filter(
    (candidate) => candidate.status === 'SUGGESTED',
  ).length
  return withConfirmed(found, candidates)
}

function openRecurring(startOn: 'recurring' | 'possible') {
  // A saved plan didn't load the recurring payments, so what they were before any answers is
  // loaded now.
  recurringBefore ??= loadRecurring().catch(() => null)
  recurringStartOn.value = startOn
  recurringVisible.value = true
}

/**
 * Brings the plan up to date with what the user answered in the recurring dialog, keeping
 * their own edits: a payment that now repeats goes on its line, or on the list to place, and
 * one that doesn't comes off. Take-home pay follows only if the user left the estimate as it was.
 */
async function onRecurringChanged() {
  const before = await recurringBefore
  let after: RecurringStream[]
  try {
    after = await loadRecurring()
  } catch {
    return
  }
  recurringBefore = Promise.resolve(after)
  if (!before) return

  const changes = recurringChanges(before, after)
  const rows = BUCKETS.flatMap((bucket) => plan.value[bucket.id])
  for (const row of rows) {
    const hadItems = row.items.length > 0
    row.items = row.items.filter((item) => !item.streamId || !changes.removed.has(item.streamId))
    // With its last bill gone, a line falls back to what the user usually spends on it.
    if (hadItems && row.items.length === 0) row.amount = null
    for (const item of row.items) {
      const amount = item.streamId ? changes.amounts.get(item.streamId) : undefined
      // An amount the user typed over stays.
      if (amount && item.amount === amount.from) item.amount = amount.to
    }
  }
  unplaced.value = unplaced.value.filter((bill) => !changes.removed.has(bill.stream_id))

  const onPlan = new Set(rows.flatMap((row) => row.items.map((item) => item.streamId)))
  for (const bill of changes.added) {
    if (onPlan.has(bill.stream_id)) continue
    if (!bill.is_inflow && bill.plan_bucket && bill.plan_line) {
      addBill(BUCKET_IDS[bill.plan_bucket], bill.plan_line, bill)
    } else if (unplacedBills([bill]).length) {
      unplaced.value.push(bill)
    }
  }

  if (takeHome.value === estimateMonthlyTakeHome(before)) {
    takeHome.value = estimateMonthlyTakeHome(after)
  }
}

/** Adds a bill to the named line, adding the line if the plan doesn't have it. */
function addBill(bucket: BucketId, lineName: string, bill: RecurringStream) {
  let row = plan.value[bucket].find((candidate) => candidate.name === lineName)
  if (!row) {
    row = toRow({ name: lineName, amount: null, items: [], fromPaycheck: false })
    plan.value[bucket].push(row)
  }
  addToRow(row, bill)
}

/** What the user usually spends on each line; without it, blank lines simply stay blank. */
async function loadDefaults() {
  try {
    const spending = await getSpendingByBucket(undefined, historyRange(DEFAULT_MONTHS))
    lineDefaults.value = lineAverages(spendingHistory(spending))
  } catch {
    lineDefaults.value = new Map()
  }
}

function setPlan(draft: PlanDraft) {
  plan.value = {
    fixedCosts: draft.fixedCosts.map(toRow),
    investments: draft.investments.map(toRow),
    savings: draft.savings.map(toRow),
  }
  expandedRows.value.clear()
}

/**
 * What a blank line counts as: the user's average monthly spending on it. A line broken down
 * into items is their sum instead.
 */
function defaultAmount(row: PlanRow): number | null {
  if (row.items.length > 0) return null
  return lineDefaults.value.get(row.name) ?? null
}

function filled(row: PlanRow): PlanLineDraft {
  return { ...row, amount: row.amount ?? defaultAmount(row) }
}

function toRow(draft: PlanLineDraft): PlanRow {
  return {
    id: crypto.randomUUID(),
    name: draft.name,
    amount: draft.amount,
    items: draft.items.map(toItem),
    fromPaycheck: draft.fromPaycheck,
  }
}

function toItem(draft: PlanItemDraft): PlanItem {
  return {
    id: crypto.randomUUID(),
    name: draft.name,
    amount: draft.amount,
    streamId: draft.streamId,
  }
}

function onTakeHomeInput(event: Event) {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  takeHome.value = parseAmount(target.value)
}

function onNameInput(entry: PlanRow | PlanItem, event: Event) {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  entry.name = target.value
}

function onAmountInput(entry: PlanRow | PlanItem, event: Event) {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  entry.amount = parseAmount(target.value)
}

function addLine(bucket: BucketId) {
  plan.value[bucket].push(toRow({ name: '', amount: null, items: [], fromPaycheck: false }))
}

function removeLine(bucket: BucketId, id: string) {
  plan.value[bucket] = plan.value[bucket].filter((row) => row.id !== id)
  expandedRows.value.delete(id)
  // A bill waiting to go on the removed line has to be pointed at another one.
  for (const [streamId, rowId] of Object.entries(placeInto.value)) {
    if (rowId === id) delete placeInto.value[streamId]
  }
}

function toggleBreakdown(id: string) {
  if (!expandedRows.value.delete(id)) expandedRows.value.add(id)
}

function addItem(row: PlanRow) {
  // The line's amount, typed or default, becomes the first item so breaking a line down never
  // changes its total.
  const amount = row.amount ?? defaultAmount(row)
  const carried = row.items.length === 0 && amount != null
  row.items.push(
    toItem({
      name: carried ? row.name : '',
      amount: carried ? amount : null,
      streamId: null,
    }),
  )
  row.amount = null
}

function placeBill(bill: RecurringStream) {
  const rowId = placeInto.value[bill.stream_id]
  const row = BUCKETS.flatMap((bucket) => plan.value[bucket.id]).find((row) => row.id === rowId)
  if (!row) return
  addToRow(row, bill)
  skipBill(bill)
}

function addToRow(row: PlanRow, bill: RecurringStream) {
  // The line's own amount becomes an item first, so the bill adds to it rather than replacing it.
  if (row.items.length === 0 && filled(row).amount != null) addItem(row)
  row.items.push(toItem(recurringItem(bill)))
}

function skipBill(bill: RecurringStream) {
  unplaced.value = unplaced.value.filter((other) => other !== bill)
}

function onPlaceIntoChange(bill: RecurringStream, event: Event) {
  const target = event.target
  if (!(target instanceof HTMLSelectElement)) return
  placeInto.value[bill.stream_id] = target.value
}

function removeItem(row: PlanRow, id: string) {
  const removed = row.items.find((item) => item.id === id)
  row.items = row.items.filter((item) => item.id !== id)
  // Removing the last item hands its amount back to the line to edit directly.
  if (row.items.length === 0) row.amount = removed?.amount ?? null
}

function breakdownLabel(bucket: Bucket, row: PlanRow) {
  return `${row.name || bucket.lineNoun} breakdown`
}

function targetHint(target: { min: number; max: number }) {
  return target.min === target.max
    ? `Should be about ${target.min}% of income`
    : `Should be ${target.min}–${target.max}% of income`
}

function onBufferInput(event: Event) {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  const percent = parseAmount(target.value)
  if (percent != null && percent > 100) {
    bufferPercent.value = 100
    target.value = '100'
    return
  }
  bufferPercent.value = percent
}

function onFromPaycheckChange(row: PlanRow, event: Event) {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  row.fromPaycheck = target.checked
}

function amountValue(amount: number | null) {
  return amount == null ? '' : String(amount)
}
</script>

<template>
  <div class="plan-setup" :class="{ 'showing-spending': showSpending }">
    <div class="plan-body">
      <form class="plan-form" aria-label="Spending plan" @submit.prevent>
        <div class="plan-toolbar">
          <p v-if="!editing" class="autofill-note">
            <Info :size="14" :stroke-width="1.75" aria-hidden="true" />
            Amounts are filled in from your tracked accounts’ recurring transactions and what you
            usually spend.
            <RouterLink to="/accounts">Choose accounts</RouterLink>
          </p>
          <p v-if="unansweredCandidates" class="autofill-note">
            <Info :size="14" :stroke-width="1.75" aria-hidden="true" />
            {{
              unansweredCandidates === 1
                ? '1 payment might be recurring.'
                : `${unansweredCandidates} payments might be recurring.`
            }}
            Confirm them to fill them in.
            <button
              type="button"
              class="note-action"
              aria-haspopup="dialog"
              @click="openRecurring('possible')"
            >
              Review them
            </button>
          </p>
          <Button
            label="Recurring payments"
            severity="secondary"
            size="small"
            text
            class="recurring-button"
            aria-haspopup="dialog"
            @click="openRecurring('recurring')"
          >
            <template #icon>
              <Repeat :size="14" :stroke-width="1.75" aria-hidden="true" />
            </template>
          </Button>
        </div>

        <section
          v-if="!loadingEstimates && unplaced.length"
          class="unplaced"
          aria-labelledby="unplaced-heading"
        >
          <h2 id="unplaced-heading" class="unplaced-heading">Recurring payments to place</h2>
          <p class="block-hint">
            We couldn’t tell which line these belong on. Add each to a line, or skip it.
          </p>
          <div v-for="bill in unplaced" :key="bill.stream_id" class="sheet-row unplaced-row">
            <span class="row-label">{{ recurringLabel(bill) }}</span>
            <span class="unplaced-amount"
              >{{ formatPlanAmount(recurringItem(bill).amount ?? 0) }}/mo</span
            >
            <select
              class="line-select"
              :aria-label="`Line for ${recurringLabel(bill)}`"
              :value="placeInto[bill.stream_id] ?? ''"
              @change="onPlaceIntoChange(bill, $event)"
            >
              <option value="" disabled>Choose a line</option>
              <optgroup v-for="bucket in BUCKETS" :key="bucket.id" :label="bucket.title">
                <option v-for="row in plan[bucket.id]" :key="row.id" :value="row.id">
                  {{ row.name || bucket.lineNoun }}
                </option>
              </optgroup>
            </select>
            <button
              type="button"
              class="unplaced-action unplaced-add"
              :disabled="!placeInto[bill.stream_id]"
              :aria-label="`Add ${recurringLabel(bill)}`"
              @click="placeBill(bill)"
            >
              Add
            </button>
            <button
              type="button"
              class="unplaced-action"
              :aria-label="`Skip ${recurringLabel(bill)}`"
              @click="skipBill(bill)"
            >
              Skip
            </button>
          </div>
        </section>

        <section class="plan-block" aria-labelledby="plan-section-heading-income">
          <h2 id="plan-section-heading-income">Income</h2>
          <div v-if="loadingEstimates" class="sheet-row">
            <Skeleton width="11rem" height="1rem" />
            <Skeleton width="6.5rem" height="1.5rem" />
          </div>
          <template v-else>
            <div class="sheet-row">
              <label class="row-label" for="take-home-income">After taxes and deductions</label>
              <div class="amount-field">
                <span aria-hidden="true">$</span>
                <input
                  id="take-home-income"
                  :value="amountValue(takeHome)"
                  inputmode="decimal"
                  autocomplete="off"
                  @input="onTakeHomeInput"
                />
              </div>
            </div>
            <template v-if="evaluation.fromPaycheck > 0">
              <div class="sheet-row income-addback">
                <span class="row-label">Investments taken from your paycheck</span>
                <span class="addback-amount" aria-label="Investments taken from your paycheck">
                  +{{ formatPlanAmount(evaluation.fromPaycheck) }}
                </span>
              </div>
              <div v-if="evaluation.income != null" class="sheet-row total-row">
                <span class="row-label">Plan income</span>
                <div class="total-amount" aria-label="Plan income">
                  <span>{{ formatPlanAmount(evaluation.income) }}</span>
                </div>
              </div>
            </template>
          </template>
        </section>

        <section
          v-for="bucket in BUCKETS"
          :key="bucket.id"
          class="plan-block"
          :aria-labelledby="`plan-section-heading-${bucket.id}`"
        >
          <div class="block-heading">
            <h2 :id="`plan-section-heading-${bucket.id}`">{{ bucket.title }}</h2>
            <p class="block-hint">{{ targetHint(PLAN_TARGETS[bucket.id]) }}</p>
          </div>

          <template v-if="loadingEstimates">
            <div v-for="index in 3" :key="index" class="sheet-row">
              <Skeleton height="1.5rem" />
              <Skeleton width="6.5rem" height="1.5rem" />
            </div>
          </template>
          <template v-else>
            <div v-for="row in plan[bucket.id]" :key="row.id" class="cost-line">
              <div class="sheet-row" :class="{ 'has-paycheck-toggle': bucket.paycheckOption }">
                <button
                  type="button"
                  class="row-remove"
                  :aria-label="`Remove ${row.name || bucket.lineNoun.toLowerCase()}`"
                  @click="removeLine(bucket.id, row.id)"
                >
                  <CircleMinus :size="16" :stroke-width="1.75" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  class="row-toggle"
                  :class="{ 'row-toggle-open': expandedRows.has(row.id) }"
                  :aria-expanded="expandedRows.has(row.id)"
                  :aria-controls="`breakdown-${row.id}`"
                  :aria-label="`Show ${breakdownLabel(bucket, row)}`"
                  @click="toggleBreakdown(row.id)"
                >
                  <ChevronRight :size="15" :stroke-width="1.75" aria-hidden="true" />
                </button>
                <input
                  class="name-input"
                  :value="row.name"
                  :placeholder="bucket.lineNoun"
                  autocomplete="off"
                  :aria-label="row.name ? `${row.name} name` : `${bucket.lineNoun} name`"
                  @input="onNameInput(row, $event)"
                />
                <span v-if="row.items.length" class="item-count">{{ row.items.length }}</span>
                <div v-if="row.items.length" class="amount-field amount-derived">
                  <span aria-hidden="true">$</span>
                  <span
                    class="derived-value"
                    :aria-label="row.name ? `${row.name} amount` : `${bucket.lineNoun} amount`"
                  >
                    {{ lineAmount(row) }}
                  </span>
                </div>
                <div
                  v-else
                  class="amount-field"
                  :class="{ 'amount-default': row.amount == null && defaultAmount(row) != null }"
                >
                  <span aria-hidden="true">$</span>
                  <input
                    :value="amountValue(row.amount)"
                    :placeholder="amountValue(defaultAmount(row))"
                    inputmode="decimal"
                    autocomplete="off"
                    :aria-label="row.name ? `${row.name} amount` : `${bucket.lineNoun} amount`"
                    @input="onAmountInput(row, $event)"
                  />
                </div>
                <label
                  v-if="bucket.paycheckOption"
                  class="paycheck-toggle"
                  :class="{ 'paycheck-toggle-on': row.fromPaycheck }"
                >
                  <input
                    type="checkbox"
                    :checked="row.fromPaycheck"
                    :aria-label="`${row.name || bucket.lineNoun}: from paycheck`"
                    @change="onFromPaycheckChange(row, $event)"
                  />
                  From paycheck
                </label>
              </div>

              <div
                v-if="expandedRows.has(row.id)"
                :id="`breakdown-${row.id}`"
                class="breakdown"
                role="group"
                :aria-label="breakdownLabel(bucket, row)"
              >
                <div v-for="item in row.items" :key="item.id" class="sheet-row item-row">
                  <button
                    type="button"
                    class="row-remove"
                    :aria-label="`Remove ${item.name || 'item'}`"
                    @click="removeItem(row, item.id)"
                  >
                    <CircleMinus :size="15" :stroke-width="1.75" aria-hidden="true" />
                  </button>
                  <input
                    class="name-input"
                    :value="item.name"
                    placeholder="Item"
                    autocomplete="off"
                    :aria-label="item.name ? `${item.name} name` : 'Item name'"
                    @input="onNameInput(item, $event)"
                  />
                  <div class="amount-field">
                    <span aria-hidden="true">$</span>
                    <input
                      :value="amountValue(item.amount)"
                      inputmode="decimal"
                      autocomplete="off"
                      :aria-label="item.name ? `${item.name} amount` : 'Item amount'"
                      @input="onAmountInput(item, $event)"
                    />
                  </div>
                </div>
                <button
                  type="button"
                  class="add-cost add-item"
                  :disabled="loadingDefaults"
                  @click="addItem(row)"
                >
                  <Plus :size="13" :stroke-width="1.75" aria-hidden="true" />
                  Add an item
                </button>
              </div>
            </div>

            <button type="button" class="add-cost add-line" @click="addLine(bucket.id)">
              <Plus :size="14" :stroke-width="1.75" aria-hidden="true" />
              {{ bucket.addLabel }}
            </button>

            <div v-if="bucket.buffer" class="sheet-row buffer-row">
              <label class="row-label" for="fixed-cost-buffer">
                Miscellaneous buffer
                <span class="row-hint">For costs you forgot and prices that rise</span>
              </label>
              <div class="percent-field">
                <input
                  id="fixed-cost-buffer"
                  :value="amountValue(bufferPercent)"
                  inputmode="decimal"
                  autocomplete="off"
                  aria-describedby="fixed-cost-buffer-amount"
                  @input="onBufferInput"
                />
                <span aria-hidden="true">%</span>
              </div>
              <div class="amount-field amount-derived">
                <span aria-hidden="true">$</span>
                <span
                  id="fixed-cost-buffer-amount"
                  class="derived-value"
                  aria-label="Miscellaneous buffer amount"
                >
                  {{ evaluation.buffer }}
                </span>
              </div>
            </div>

            <div class="sheet-row total-row">
              <span class="row-label">Total</span>
              <div class="total-amount" :aria-label="`${bucket.title} total`">
                <span>{{ formatPlanAmount(evaluation.buckets[bucket.id].amount) }}</span>
                <span
                  v-if="evaluation.buckets[bucket.id].share != null"
                  class="total-share"
                  :class="{ 'total-share-over': evaluation.buckets[bucket.id].flagged }"
                >
                  {{ evaluation.buckets[bucket.id].share }}%
                </span>
              </div>
            </div>
          </template>
        </section>

        <section class="plan-block" aria-labelledby="plan-section-heading-guilt-free">
          <div class="block-heading">
            <h2 id="plan-section-heading-guilt-free">{{ PLAN_TITLES.guiltFree }}</h2>
            <p class="block-hint">{{ targetHint(PLAN_TARGETS.guiltFree) }}</p>
          </div>

          <div v-if="loadingEstimates" class="sheet-row">
            <Skeleton width="11rem" height="1rem" />
            <Skeleton width="6.5rem" height="1.5rem" />
          </div>
          <p v-else-if="guiltFree.amount == null" class="block-hint guilt-free-empty">
            Enter your take-home pay to see what’s left to spend.
          </p>
          <template v-else>
            <div class="sheet-row guilt-free-row">
              <span class="row-label">Left to spend</span>
              <div
                class="total-amount"
                :class="{ 'guilt-free-over': guiltFree.flagged }"
                aria-label="Guilt-free spending total"
              >
                <span>{{ formatPlanAmount(guiltFree.amount) }}</span>
                <span v-if="guiltFree.share != null" class="total-share">
                  {{ guiltFree.share }}%
                </span>
              </div>
            </div>
            <p v-if="guiltFree.flagged" class="guilt-free-warning">
              Your plan is {{ formatPlanAmount(-guiltFree.amount) }} more than your take-home pay.
            </p>
          </template>
        </section>
      </form>

      <SpendingExplorer class="plan-spending" />
    </div>

    <footer class="plan-footer">
      <button type="button" class="spending-toggle" @click="showSpending = !showSpending">
        {{ showSpending ? 'Back to your plan' : 'See your spending' }}
      </button>
      <p v-if="saveError" class="save-error" role="alert">{{ saveError }}</p>
      <p v-else-if="replacing" class="save-note">Saving replaces your current plan.</p>
      <Button
        label="Save plan"
        :loading="saving"
        :disabled="loadingEstimates || loadingDefaults || saving"
        @click="save"
      />
    </footer>

    <RecurringDialog
      v-model:visible="recurringVisible"
      :start-on="recurringStartOn"
      @changed="onRecurringChanged"
    />
  </div>
</template>

<style scoped>
.plan-setup {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.plan-footer {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: flex-end;
  gap: 1rem;
  padding: 0.875rem 2.25rem;
  border-top: 1px solid var(--app-divider);
}

.save-error,
.save-note {
  margin: 0 auto 0 0;
  font-size: 0.8125rem;
}

.save-error {
  color: var(--app-danger);
}

.save-note {
  color: var(--app-text-secondary);
}

/* The plan on the left, and the user's spending beside it to choose amounts from. */
.plan-body {
  display: flex;
  flex: 1;
  min-width: 0;
  min-height: 0;
  border-top: 1px solid var(--app-divider);
}

.plan-spending {
  flex: none;
  width: 23rem;
  border-left: 1px solid var(--app-divider);
}

.spending-toggle {
  display: none;
  margin-right: auto;
  padding: 0.25rem 0;
  color: var(--app-text);
  background: transparent;
  border: 0;
  font: inherit;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
}

.plan-form {
  display: grid;
  --section-gap: 2.25rem;

  align-content: start;
  flex: 1;
  gap: var(--section-gap);
  min-width: 0;
  min-height: 0;
  padding: 1.5rem 2.25rem 2rem;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.plan-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 1rem;
}

.autofill-note {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.35rem;
  margin: 0;
  color: var(--app-text-subdued);
  font-size: 0.8125rem;
}

.autofill-note svg {
  flex: none;
}

.autofill-note a,
.note-action {
  color: var(--app-text);
  font-weight: 500;
}

.note-action {
  padding: 0;
  font: inherit;
  text-decoration: underline;
  background: none;
  border: 0;
  cursor: pointer;
}

.recurring-button {
  margin-left: auto;
}

.unplaced {
  display: grid;
  gap: 0.25rem;
  max-width: 36.5rem;
  padding: 0.875rem 1rem;
  background: var(--app-inset);
  border-radius: var(--app-radius-chip);
}

.unplaced-heading {
  font-size: 0.9375rem;
}

.unplaced-row {
  flex-wrap: wrap;
  font-size: 0.875rem;
}

.unplaced-amount {
  flex: none;
  color: var(--app-text-secondary);
  font-variant-numeric: tabular-nums;
}

.line-select {
  flex: none;
  max-width: 11rem;
  appearance: none;
  padding: 0.3rem 1.6rem 0.3rem 0.55rem;
  color: var(--app-text);
  background-color: var(--app-surface);
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' fill='none' stroke='%23737373' stroke-linecap='round' stroke-linejoin='round' stroke-width='1.75' viewBox='0 0 24 24'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-position: right 0.35rem center;
  background-repeat: no-repeat;
  border: 1px solid var(--app-control-border);
  border-radius: var(--app-radius-chip);
  font: inherit;
  font-size: 0.8125rem;
}

.unplaced-action {
  flex: none;
  padding: 0.25rem 0.35rem;
  color: var(--app-text-secondary);
  background: transparent;
  border: 0;
  font: inherit;
  font-size: 0.8125rem;
  cursor: pointer;
}

.unplaced-action:hover:not(:disabled) {
  color: var(--app-text);
}

.unplaced-add {
  color: var(--app-text);
  font-weight: 500;
}

.unplaced-action:disabled {
  color: var(--app-text-subdued);
  cursor: default;
}

.plan-form {
  container-type: inline-size;
  counter-reset: plan-step;
}

@container (max-width: 45rem) {
  .has-paycheck-toggle {
    flex-wrap: wrap;
  }

  .paycheck-toggle {
    position: static;
    flex-basis: 100%;
    margin: -0.4rem 0 0.2rem 3.25rem;
    transform: none;
  }
}

/* Each section is a numbered step; a bar runs from its bubble down to the next one. */
.plan-block {
  --step-size: 1.5rem;
  --step-gutter: 2.5rem;

  position: relative;
  display: grid;
  gap: 0.35rem;
  max-width: calc(34rem + var(--step-gutter));
  padding-left: var(--step-gutter);
  counter-increment: plan-step;
}

.plan-block::before {
  content: counter(plan-step);
  position: absolute;
  top: -0.1rem;
  left: 0;
  display: grid;
  width: var(--step-size);
  height: var(--step-size);
  place-items: center;
  color: var(--app-text-secondary);
  background: var(--app-inset);
  border-radius: 50%;
  font-size: 0.75rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.plan-block:not(:last-child)::after {
  content: '';
  position: absolute;
  top: calc(var(--step-size) + 0.25rem);
  /* Reach past the gap between sections to just above the next bubble. */
  bottom: calc(0.35rem - var(--section-gap));
  left: calc(var(--step-size) / 2 - 1px);
  width: 2px;
  background: var(--app-inset);
  border-radius: 1px;
}

h2 {
  margin: 0;
  color: var(--app-text);
  font-size: 1.0625rem;
  font-weight: 600;
  line-height: 1.3;
  letter-spacing: -0.02em;
}

.plan-block > h2,
.block-heading {
  margin-bottom: 0.25rem;
}

.block-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
}

.block-hint {
  margin: 0;
  color: var(--app-text-subdued);
  font-size: 0.8125rem;
}

.sheet-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-height: 2.25rem;
}

.row-label,
.name-input {
  flex: 1;
  min-width: 0;
  font-weight: 500;
}

.name-input,
.amount-field,
.amount-field input,
.add-cost {
  font: inherit;
  color: inherit;
}

.name-input {
  padding: 0.35rem 0;
  background: transparent;
  border: 0;
  border-bottom: 1px solid transparent;
}

.name-input::placeholder {
  color: var(--app-text-subdued);
  font-weight: 400;
}

.name-input:hover,
.name-input:focus {
  border-bottom-color: var(--app-control-border);
  outline: none;
}

.name-input:focus {
  border-bottom-color: var(--app-text);
}

.amount-field {
  display: flex;
  flex: none;
  align-items: center;
  gap: 0.15rem;
  width: 6.5rem;
  padding: 0.35rem 0;
  border-bottom: 1px solid var(--app-divider);
}

.amount-field:focus-within {
  border-bottom-color: var(--app-text);
}

.amount-field input {
  width: 100%;
  min-width: 0;
  padding: 0;
  background: transparent;
  border: 0;
  font-variant-numeric: tabular-nums;
  text-align: right;
  outline: none;
}

.row-remove {
  display: grid;
  flex: none;
  width: 1.5rem;
  height: 1.5rem;
  margin-left: -0.2rem;
  place-items: center;
  color: var(--app-danger);
  background: transparent;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
}

.row-remove:hover {
  background: var(--app-danger-surface);
}

.row-toggle {
  display: grid;
  flex: none;
  width: 1.5rem;
  height: 1.5rem;
  margin-right: -0.25rem;
  place-items: center;
  color: var(--app-text-subdued);
  background: transparent;
  border: 0;
  border-radius: var(--app-radius-chip);
  cursor: pointer;
}

.row-toggle:hover,
.row-toggle:focus-visible {
  color: var(--app-text);
}

.row-toggle svg {
  transition: transform 150ms ease;
}

.row-toggle-open svg {
  transform: rotate(90deg);
}

.item-count {
  flex: none;
  min-width: 1.25rem;
  padding: 0 0.35rem;
  color: var(--app-text-secondary);
  background: var(--app-inset);
  border-radius: 999px;
  font-size: 0.6875rem;
  font-weight: 500;
  line-height: 1.25rem;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.amount-derived {
  color: var(--app-text-secondary);
  border-bottom-style: dashed;
}

/* A default amount reads like a filled-in one, but gives way to typing as soon as it's clicked. */
.amount-default {
  color: var(--app-text-secondary);
  border-bottom-style: dashed;
}

.amount-field input::placeholder {
  color: var(--app-text-secondary);
  opacity: 1;
}

.amount-field input:focus::placeholder {
  color: transparent;
}

.derived-value {
  flex: 1;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.breakdown {
  display: grid;
  margin: 0 0 0.35rem 3.3rem;
  padding-left: 0.75rem;
  border-left: 1px solid var(--app-divider);
}

.item-row {
  min-height: 2rem;
  font-size: 0.875rem;
}

.item-row .name-input {
  font-weight: 400;
}

.add-item {
  margin: 0.1rem 0 0 1.3rem;
  font-size: 0.8125rem;
}

.add-cost {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  width: fit-content;
  margin: 0.15rem 0 0 1.3rem;
  padding: 0.25rem 0;
  color: var(--app-text-secondary);
  background: transparent;
  border: 0;
  cursor: pointer;
}

.add-cost:hover:not(:disabled) {
  color: var(--app-text);
}

.add-cost:disabled {
  color: var(--app-text-subdued);
  cursor: default;
}

.buffer-row {
  margin-top: 0.35rem;
}

.row-hint {
  display: block;
  color: var(--app-text-subdued);
  font-size: 0.75rem;
  font-weight: 400;
}

.percent-field {
  display: flex;
  flex: none;
  align-items: center;
  gap: 0.15rem;
  width: 3.25rem;
  padding: 0.35rem 0;
  border-bottom: 1px solid var(--app-divider);
}

.percent-field:focus-within {
  border-bottom-color: var(--app-text);
}

.percent-field input {
  width: 100%;
  min-width: 0;
  padding: 0;
  color: inherit;
  background: transparent;
  border: 0;
  font: inherit;
  font-variant-numeric: tabular-nums;
  text-align: right;
  outline: none;
}

.total-row {
  margin-top: 0.15rem;
  border-top: 1px solid var(--app-divider);
}

.total-amount {
  display: flex;
  flex: none;
  align-items: baseline;
  gap: 0.6rem;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
}

.total-share {
  color: var(--app-text-secondary);
  font-size: 0.75rem;
  font-weight: 500;
}

.total-share-over {
  color: var(--app-over-target);
}

.income-addback {
  min-height: 2rem;
  color: var(--app-text-secondary);
  font-size: 0.875rem;
}

.income-addback .row-label {
  font-weight: 400;
}

.addback-amount {
  flex: none;
  font-variant-numeric: tabular-nums;
}

/*
 * The toggle hangs outside the row, to the right of the amount, so investment amounts stay in
 * the same column as every other section. Where there's no room beside the rows it drops onto
 * its own line under the row instead.
 */
.has-paycheck-toggle {
  position: relative;
}

.paycheck-toggle {
  position: absolute;
  top: 50%;
  left: calc(100% + 1rem);
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  color: var(--app-text-subdued);
  font-size: 0.75rem;
  white-space: nowrap;
  cursor: pointer;
}

.paycheck-toggle input {
  margin: 0;
  accent-color: var(--app-text);
  cursor: pointer;
}

.paycheck-toggle-on {
  color: var(--app-text-secondary);
}

.guilt-free-row {
  font-size: 1rem;
}

.guilt-free-over,
.guilt-free-over .total-share,
.guilt-free-warning {
  color: var(--app-over-target);
}

.guilt-free-empty {
  padding: 0.35rem 0;
}

.guilt-free-warning {
  margin: 0;
  font-size: 0.8125rem;
}

@media (max-width: 1000px) {
  .plan-spending {
    display: none;
  }

  .showing-spending .plan-form {
    display: none;
  }

  .showing-spending .plan-spending {
    display: flex;
    flex: 1;
    width: auto;
    border-left: 0;
  }

  .spending-toggle {
    display: block;
  }

  .save-error,
  .save-note {
    margin: 0;
  }
}

@media (max-width: 900px) {
  .plan-footer {
    padding-inline: 1.5rem;
  }

  .plan-form {
    padding: 1.25rem 1.5rem 1.5rem;
  }
}

@media (max-width: 640px) {
  .plan-footer {
    padding-inline: 1.25rem;
  }

  .plan-block {
    --step-gutter: 2.1rem;
  }

  .plan-form {
    padding: 1.15rem 1.25rem 1.5rem;
  }

  .plan-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .block-heading {
    flex-direction: column;
    align-items: flex-start;
    gap: 0.2rem;
  }

  .add-cost {
    margin-left: 0;
  }

  .breakdown {
    margin-left: 1.5rem;
  }
}
</style>
