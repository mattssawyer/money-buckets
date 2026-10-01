<script setup lang="ts">
import { ChevronRight, Search } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import Skeleton from 'primevue/skeleton'
import { getSpendingByBucket } from '../api/PlaidService'
import { formatPlanAmount } from '../spendingPlan/money'
import {
  HISTORY_MONTHS,
  historyRange,
  searchHistory,
  spendingHistory,
  type CategoryGroup,
  type CategoryHistory,
  type HistoryMonths,
  type SpendingHistory,
} from '../spendingPlan/spendingHistory'

const period = ref<HistoryMonths>(3)
const loading = ref(true)
const loadError = ref(false)
const history = ref<SpendingHistory | null>(null)
const query = ref('')
const openCategories = ref(new Set<string>())
let latestLoad = 0

const groups = computed(() => searchHistory(history.value?.groups ?? [], query.value))
const searching = computed(() => query.value.trim() !== '')

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
  void load()
}

function onQueryInput(event: Event) {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  query.value = target.value
}

function toggleOpen(category: CategoryHistory) {
  if (!openCategories.value.delete(category.key)) openCategories.value.add(category.key)
}

/** A search shows the payees it found without having to open each category. */
function isOpen(category: CategoryHistory) {
  return searching.value || openCategories.value.has(category.key)
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
        choose each amount.
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
                <span class="pick-name">{{ category.label }}</span>
                <span class="pick-amount">{{ amount(category.average) }}</span>
              </div>
              <ul v-if="isOpen(category)" class="payees" :aria-label="`${category.label} payees`">
                <li v-for="payee in category.payees" :key="payee.key" class="pick-row payee-row">
                  <span class="pick-name">{{ payee.name }}</span>
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

.pick-name {
  flex: 1;
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
