<script setup lang="ts">
import { ChevronRight, Search } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import Skeleton from 'primevue/skeleton'
import { getSpendingByBucket } from '../api/PlaidService'
import { formatPlanAmount } from '../spendingPlan/money'
import {
  HISTORY_MONTHS,
  historyRange,
  monthLabel,
  searchHistory,
  selectionHistory,
  spendingHistory,
  type CategoryGroup,
  type CategoryHistory,
  type HistoryMonths,
  type PayeeHistory,
  type SpendingHistory,
} from '../spendingPlan/spendingHistory'

/** How many picked names the summary spells out before counting the rest. */
const NAMED_PICKS = 2

const period = ref<HistoryMonths>(3)
const loading = ref(true)
const loadError = ref(false)
const history = ref<SpendingHistory | null>(null)
const query = ref('')
const pickedCategories = ref(new Set<string>())
const pickedPayees = ref(new Set<string>())
const openCategories = ref(new Set<string>())
let latestLoad = 0

const groups = computed(() => searchHistory(history.value?.groups ?? [], query.value))
const searching = computed(() => query.value.trim() !== '')

const summary = computed(() =>
  history.value
    ? selectionHistory(history.value, {
        categories: pickedCategories.value,
        payees: pickedPayees.value,
      })
    : null,
)

const pickedNames = computed(() => {
  const names: string[] = []
  for (const group of history.value?.groups ?? []) {
    for (const category of group.categories) {
      if (pickedCategories.value.has(category.key)) {
        names.push(category.label)
        continue
      }
      for (const payee of category.payees) {
        if (pickedPayees.value.has(payee.key)) names.push(payee.name)
      }
    }
  }
  return names
})

const summaryTitle = computed(() => {
  const names = pickedNames.value
  if (names.length === 0) return 'All spending'
  const more = names.length - NAMED_PICKS
  return more > 0 ? `${names.slice(0, NAMED_PICKS).join(', ')} and ${more} more` : names.join(', ')
})

const coverage = computed(() => {
  const months = history.value?.months ?? []
  const first = months[0]
  const last = months[months.length - 1]
  if (!first || !last) return ''
  return first === last ? monthLabel(first) : `${monthLabel(first)}–${monthLabel(last)}`
})

/**
 * A bucket's plan lines, then its spending on no line by category. The second part is only
 * headed where there are lines above it; guilt-free spending has none.
 */
function parts(group: CategoryGroup) {
  const lines = group.categories.filter((category) => category.onLine)
  const others = group.categories.filter((category) => !category.onLine)
  return [
    { heading: null, categories: lines },
    { heading: lines.length ? 'On no line' : null, categories: others },
  ].filter((part) => part.categories.length > 0)
}

onMounted(load)

/** Spending from every tracked account, as the plan draws on all of them. */
async function load() {
  // Changing the period again before an answer arrives starts a newer load; only the newest
  // one's answer is shown, whichever order they come back in.
  const request = ++latestLoad
  loading.value = true
  loadError.value = false
  try {
    const spending = await getSpendingByBucket(undefined, historyRange(period.value))
    if (request !== latestLoad) return
    history.value = spendingHistory(spending)
  } catch {
    if (request !== latestLoad) return
    history.value = null
    loadError.value = true
  } finally {
    if (request === latestLoad) loading.value = false
  }
}

function onPeriodChange(event: Event) {
  const target = event.target
  if (!(target instanceof HTMLSelectElement)) return
  period.value = Number(target.value) as HistoryMonths
  // A longer stretch can hold categories the shorter one didn't, and the other way around.
  clearPicks()
  void load()
}

function onQueryInput(event: Event) {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  query.value = target.value
}

function toggleCategory(category: CategoryHistory) {
  if (!pickedCategories.value.delete(category.key)) pickedCategories.value.add(category.key)
  // The whole category now says it all; its payees no longer need their own picks.
  for (const payee of category.payees) pickedPayees.value.delete(payee.key)
}

function togglePayee(payee: PayeeHistory) {
  if (!pickedPayees.value.delete(payee.key)) pickedPayees.value.add(payee.key)
}

function toggleOpen(category: CategoryHistory) {
  if (!openCategories.value.delete(category.key)) openCategories.value.add(category.key)
}

/** A search shows the payees it found without having to open each category. */
function isOpen(category: CategoryHistory) {
  return searching.value || openCategories.value.has(category.key)
}

function clearPicks() {
  pickedCategories.value.clear()
  pickedPayees.value.clear()
}

function amount(value: number) {
  return formatPlanAmount(Math.round(value))
}
</script>

<template>
  <aside class="spending-explorer" aria-labelledby="spending-explorer-heading">
    <header class="explorer-header">
      <div class="explorer-title">
        <h2 id="spending-explorer-heading">Your spending</h2>
        <select
          class="period-select"
          aria-label="How far back"
          :value="period"
          @change="onPeriodChange"
        >
          <option v-for="months in HISTORY_MONTHS" :key="months" :value="months">
            Last {{ months }} months
          </option>
        </select>
      </div>
      <p class="explorer-hint">
        What you spend in a month on average, by the plan line each payee belongs on, to help you
        choose each amount. Pick lines or payees to add them up.
      </p>
    </header>

    <div v-if="loading" class="explorer-loading" aria-label="Loading your spending">
      <Skeleton height="5.5rem" />
      <Skeleton v-for="index in 6" :key="index" height="1.5rem" />
    </div>

    <div v-else-if="loadError" class="explorer-empty" role="alert">
      <p>We couldn’t load your spending.</p>
      <button type="button" class="text-action" @click="load">Try again</button>
    </div>

    <p v-else-if="!history || history.groups.length === 0" class="explorer-empty">
      No spending in your tracked accounts over these months yet.
    </p>

    <template v-else>
      <section v-if="summary" class="summary" aria-live="polite" aria-label="Selected spending">
        <div class="summary-head">
          <span class="summary-title">{{ summaryTitle }}</span>
          <button v-if="pickedNames.length" type="button" class="text-action" @click="clearPicks">
            Clear
          </button>
        </div>
        <p class="summary-average">
          <strong>{{ amount(summary.average) }}</strong> a month on average
        </p>
        <ul class="summary-months" :aria-label="`Each month, ${coverage}`">
          <li v-for="(month, index) in history.months" :key="month">
            <span>{{ monthLabel(month) }}</span>
            <span class="month-amount">{{ amount(summary.byMonth[index] ?? 0) }}</span>
          </li>
        </ul>
      </section>

      <label class="search">
        <Search :size="14" :stroke-width="1.75" aria-hidden="true" />
        <input
          type="search"
          :value="query"
          placeholder="Search lines or payees"
          aria-label="Search lines or payees"
          autocomplete="off"
          @input="onQueryInput"
        />
      </label>

      <p v-if="groups.length === 0" class="explorer-empty">Nothing matches “{{ query.trim() }}”.</p>

      <section v-for="group in groups" :key="group.key" class="group" :aria-label="group.label">
        <h3 class="group-heading">
          <span>{{ group.label }}</span>
          <span v-if="!searching" class="group-amount">{{ amount(group.average) }}</span>
        </h3>
        <template v-for="part in parts(group)" :key="part.heading ?? 'lines'">
          <p v-if="part.heading" class="part-heading">{{ part.heading }}</p>
          <ul class="categories">
            <li v-for="category in part.categories" :key="category.key">
              <div class="pick-row">
                <button
                  type="button"
                  class="row-toggle"
                  :class="{ 'row-toggle-open': isOpen(category) }"
                  :aria-expanded="isOpen(category)"
                  :aria-label="`Show ${category.label} payees`"
                  @click="toggleOpen(category)"
                >
                  <ChevronRight :size="14" :stroke-width="1.75" aria-hidden="true" />
                </button>
                <label class="pick">
                  <input
                    type="checkbox"
                    :checked="pickedCategories.has(category.key)"
                    @change="toggleCategory(category)"
                  />
                  <span class="pick-name">{{ category.label }}</span>
                </label>
                <span class="pick-amount">{{ amount(category.average) }}</span>
              </div>
              <ul v-if="isOpen(category)" class="payees" :aria-label="`${category.label} payees`">
                <li v-for="payee in category.payees" :key="payee.key" class="pick-row payee-row">
                  <label class="pick">
                    <input
                      type="checkbox"
                      :checked="pickedCategories.has(category.key) || pickedPayees.has(payee.key)"
                      :disabled="pickedCategories.has(category.key)"
                      @change="togglePayee(payee)"
                    />
                    <span class="pick-name">{{ payee.name }}</span>
                  </label>
                  <span class="pick-amount">{{ amount(payee.average) }}</span>
                </li>
              </ul>
            </li>
          </ul>
        </template>
      </section>
    </template>
  </aside>
</template>

<style scoped>
.spending-explorer {
  display: flex;
  flex-direction: column;
  gap: 0.875rem;
  min-width: 0;
  min-height: 0;
  padding: 1.5rem 1.5rem 2rem;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: var(--app-canvas);
  font-size: 0.875rem;
}

.explorer-header {
  display: grid;
  gap: 0.4rem;
}

.explorer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}

h2 {
  margin: 0;
  font-size: 1.0625rem;
  font-weight: 600;
  line-height: 1.3;
  letter-spacing: -0.02em;
}

.explorer-hint,
.explorer-empty {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  line-height: 1.5;
}

.explorer-loading {
  display: grid;
  gap: 0.6rem;
}

.period-select {
  flex: none;
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

.text-action {
  flex: none;
  padding: 0;
  color: var(--app-text);
  background: transparent;
  border: 0;
  font: inherit;
  font-size: 0.8125rem;
  font-weight: 500;
  cursor: pointer;
}

.text-action:hover {
  text-decoration: underline;
}

/* Stays in view while the list scrolls, so picking far down still shows what it adds up to. */
.summary {
  position: sticky;
  top: -1.5rem;
  z-index: 1;
  display: grid;
  gap: 0.35rem;
  padding: 0.875rem 1rem;
  background: var(--app-surface);
  border-radius: var(--app-radius-control);
  box-shadow: var(--app-shadow-border);
}

.summary-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.75rem;
}

.summary-title {
  min-width: 0;
  overflow: hidden;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-average {
  margin: 0;
  color: var(--app-text-secondary);
}

.summary-average strong {
  margin-right: 0.15rem;
  color: var(--app-text);
  font-size: 1.375rem;
  font-weight: 600;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
}

.summary-months {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.875rem;
  margin: 0.15rem 0 0;
  padding: 0;
  color: var(--app-text-subdued);
  font-size: 0.75rem;
  list-style: none;
}

.summary-months li {
  display: flex;
  gap: 0.3rem;
}

.month-amount {
  color: var(--app-text-secondary);
  font-variant-numeric: tabular-nums;
}

.search {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.4rem 0.6rem;
  color: var(--app-text-subdued);
  background: var(--app-surface);
  border: 1px solid var(--app-control-border);
  border-radius: var(--app-radius-chip);
}

.search:focus-within {
  border-color: var(--app-text);
}

.search input {
  flex: 1;
  min-width: 0;
  padding: 0;
  color: var(--app-text);
  background: transparent;
  border: 0;
  font: inherit;
  font-size: 0.8125rem;
  outline: none;
}

/* One column that can't grow past the panel, so a long name is cut short instead of pushing amounts out of view. */
.group {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 0.15rem;
}

.group-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 0.35rem 0 0.1rem;
  padding-bottom: 0.3rem;
  border-bottom: 1px solid var(--app-divider);
  font-size: 0.875rem;
  font-weight: 600;
}

.group-amount {
  color: var(--app-text-secondary);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.part-heading {
  margin: 0.5rem 0 0;
  color: var(--app-text-subdued);
  font-size: 0.75rem;
  font-weight: 500;
}

.categories,
.payees {
  margin: 0;
  padding: 0;
  list-style: none;
}

.payees {
  margin: 0 0 0.25rem 1.75rem;
  padding-left: 0.6rem;
  border-left: 1px solid var(--app-divider);
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

.pick-row {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  min-height: 1.9rem;
}

.payee-row {
  min-height: 1.7rem;
}

.row-toggle {
  display: grid;
  flex: none;
  width: 1.4rem;
  height: 1.4rem;
  margin-left: -0.3rem;
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

.pick {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 0.5rem;
  min-width: 0;
  cursor: pointer;
}

.pick input {
  flex: none;
  margin: 0;
  accent-color: var(--app-text);
  cursor: pointer;
}

.pick-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pick-amount {
  flex: none;
  font-variant-numeric: tabular-nums;
}
</style>
