<script setup lang="ts">
import { UserButton, useUser } from '@clerk/vue'
import { ChevronDown, Landmark, Plus } from '@lucide/vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import type { ChartOptions } from 'chart.js'
import Button from 'primevue/button'
import Chart from 'primevue/chart'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import AppSidebar from '../components/AppSidebar.vue'
import SameInstitutionNotice from '../components/SameInstitutionNotice.vue'
import TransactionsDialog from '../components/TransactionsDialog.vue'
import {
  createLinkToken,
  exchangePublicToken,
  getLinkedItemIds,
  getSpendingByBucket,
  getTransactions,
  answerRecurringCandidate,
  getRecurringCandidates,
  getRecurringTransactions,
  syncRecurringTransactions,
  undoRecurringAnswer,
  type PlaidItem,
  type PlaidTransaction,
  type RecurringCandidate,
  type RecurringStream,
  type SpendingByBucket,
} from '../api/PlaidService'
import { getSpendingPlan } from '../api/SpendingPlanService'
import {
  BUCKET_STYLES,
  categoryLabel,
  formatTransactionAmount,
  recurringLabel,
  transactionLabel,
  yourAmount,
} from '../api/plaidLabels'
import { accountLabel, useSelectedAccount } from '../accounts/useSelectedAccount'
import { isConfirmedCandidate, withConfirmed } from '../spending/recurring'

const RECENT_TRANSACTION_COUNT = 25
const RECURRING_STREAM_COUNT = 20
// New transactions are sorted in the background, so check back while any are still waiting.
const UNSORTED_RECHECK_MS = 4000
const UNSORTED_RECHECK_LIMIT = 15

const FREQUENCY_LABELS: Record<string, string> = {
  WEEKLY: 'Weekly',
  BIWEEKLY: 'Every 2 weeks',
  SEMI_MONTHLY: 'Twice a month',
  MONTHLY: 'Monthly',
  QUARTERLY: 'Every 3 months',
  SEMI_ANNUALLY: 'Twice a year',
  ANNUALLY: 'Yearly',
  UNKNOWN: 'Recurring',
}

const linking = ref(false)
const linkError = ref('')
const initialLoading = ref(true)
const loadingTransactions = ref(false)
const loadingRecurring = ref(false)
const syncingRecurring = ref(false)
// Whether the recurring error came from Sync, so trying again syncs rather than just reloading.
const recurringSyncFailed = ref(false)
const loadingSpending = ref(false)
const connectionError = ref('')
const transactionsError = ref('')
const recurringError = ref('')
const spendingError = ref('')
const itemIds = ref<string[]>([])
const transactions = ref<PlaidTransaction[]>([])
const allTransactionsVisible = ref(false)
const recurring = ref<RecurringStream[]>([])
const candidates = ref<RecurringCandidate[]>([])
const showDismissed = ref(false)
// The candidate whose answer is being saved; answers are saved one at a time.
const answeringKey = ref<string>()
const answerError = ref('')
const spending = ref<SpendingByBucket>()
// Unknown until loaded, so the plan prompt never flashes for someone who has a plan.
const hasSpendingPlan = ref<boolean>()
// Legend rows the user has flipped from how they start. Buckets start open and categories start
// closed. Category keys include the bucket, since the same category can appear under two buckets.
const toggledRows = ref(new Set<string>())
let unsortedRecheck: ReturnType<typeof setTimeout> | undefined
let unsortedRechecks = 0
const {
  accounts,
  selectedAccountId,
  selectedAccount,
  balance,
  loading: loadingAccounts,
  failed: accountsFailed,
  load: loadAccountList,
  select: selectAccount,
} = useSelectedAccount()
const balanceError = computed(() =>
  accountsFailed.value ? 'We couldn’t load your accounts. Please try again.' : '',
)
const pendingPublicToken = ref<string>()
const sameInstitution = ref<PlaidItem[]>([])
const { user } = useUser()
const hasConnections = computed(() => itemIds.value.length > 0)
const hasMultipleAccounts = computed(() => accounts.value.length > 1)
const greeting = computed(() => {
  const hour = new Date().getHours()
  const timeOfDay = hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening'
  const firstName = user.value?.firstName
  return firstName ? `Good ${timeOfDay}, ${firstName}` : `Good ${timeOfDay}`
})
const selectedAccountLabel = computed(() => {
  const account =
    selectedAccount.value ?? (hasMultipleAccounts.value ? undefined : accounts.value[0])
  if (account) return accountLabel(account)
  return accounts.value.length ? 'All tracked accounts' : ''
})
const recurringList = computed(() =>
  withConfirmed(recurring.value, candidates.value).slice(0, RECURRING_STREAM_COUNT),
)
const suggestedCandidates = computed(() =>
  candidates.value.filter((candidate) => candidate.status === 'SUGGESTED'),
)
const dismissedCandidates = computed(() =>
  candidates.value.filter((candidate) => candidate.status === 'DISMISSED'),
)
const spendingTotal = computed(() => spending.value?.total ?? 0)
const spendingMonth = computed(() =>
  spending.value
    ? new Intl.DateTimeFormat('en-US', { month: 'long' }).format(
        new Date(`${spending.value.start}T00:00:00`),
      )
    : '',
)
// Transactions are sorted into buckets with or without a plan, so spending is always charted by
// bucket, the same buckets the transactions list shows.
const spendingLegend = computed(() => {
  if (!spending.value) return []
  return spending.value.buckets.map((entry) => ({
    ...entry,
    ...BUCKET_STYLES[entry.bucket],
    key: entry.bucket,
    startsOpen: true,
    categories: entry.categories.map((category) => ({
      ...category,
      key: `${entry.bucket}/${category.category}`,
      label: categoryLabel(category.category),
      startsOpen: false,
    })),
  }))
})
const spendingChartData = computed(() => ({
  labels: spendingLegend.value.map((entry) => entry.label),
  datasets: [
    {
      data: spendingLegend.value.map((entry) => entry.amount),
      backgroundColor: spendingLegend.value.map((entry) => entry.color),
      hoverBackgroundColor: spendingLegend.value.map((entry) => entry.color),
      borderColor: '#ffffff',
      borderWidth: 3,
      hoverOffset: 0,
    },
  ],
}))
const spendingChartOptions: ChartOptions<'doughnut'> = {
  responsive: true,
  maintainAspectRatio: false,
  cutout: '76%',
  layout: { padding: 0 },
  plugins: {
    legend: { display: false },
    tooltip: {
      backgroundColor: '#171717',
      titleFont: { family: 'Geist Variable', weight: 500 },
      bodyFont: { family: 'Geist Variable' },
      padding: 10,
      cornerRadius: 8,
      displayColors: false,
      callbacks: { label: (context) => formatBalance(context.parsed) },
    },
  },
}
let handler: ReturnType<Window['Plaid']['create']> | undefined
let disposed = false

onMounted(loadConnections)
onUnmounted(() => {
  disposed = true
  clearTimeout(unsortedRecheck)
  handler?.destroy()
})

function isOpen(row: { key: string; startsOpen: boolean }) {
  return row.startsOpen !== toggledRows.value.has(row.key)
}

function toggle(row: { key: string }) {
  if (!toggledRows.value.delete(row.key)) toggledRows.value.add(row.key)
}

/** A spending row's date, noting the user's share when the transaction is from a shared account. */
function spendingDate(transaction: PlaidTransaction) {
  const date = formatTransactionDate(transaction.date)
  return transaction.share_percent < 100 ? `${date} · Your ${transaction.share_percent}%` : date
}

function formatBalance(amount: number | null) {
  if (amount === null) return '—'
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount)
}

function formatWholeDollars(amount: number) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0,
  }).format(amount)
}

// Plaid dates are calendar days, so parse them locally instead of as UTC instants.
function formatTransactionDate(date: string) {
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(
    new Date(`${date}T00:00:00`),
  )
}

function frequencyLabel(frequency: string) {
  return (
    FREQUENCY_LABELS[frequency] ??
    frequency
      .toLowerCase()
      .split('_')
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ')
  )
}

function recurringMeta(stream: RecurringStream) {
  const cadence = frequencyLabel(stream.frequency)
  if (stream.next_date) return `${cadence} · Next ${formatTransactionDate(stream.next_date)}`
  if (stream.last_date) return `${cadence} · Last ${formatTransactionDate(stream.last_date)}`
  return cadence
}

// A guess is only as good as the last charge it came from, so say when that was.
function candidateMeta(candidate: RecurringCandidate) {
  const cadence = frequencyLabel(candidate.frequency)
  return candidate.last_date
    ? `${cadence}? · Last ${formatTransactionDate(candidate.last_date)}`
    : `${cadence}?`
}

function formatRecurringAmount(stream: RecurringStream) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: stream.iso_currency_code ?? 'USD',
    signDisplay: 'exceptZero',
  }).format(-stream.amount)
}

/**
 * Asks for everything at once, for the account remembered from last time, rather than waiting
 * on the account list first. The dashboard shows once the connections and accounts are known;
 * each card fills in as its data arrives.
 */
async function loadConnections() {
  initialLoading.value = true
  connectionError.value = ''
  const requested = selectedAccountId.value
  const savedItemIds = getLinkedItemIds()
  void loadCards()
  // The plan check only decides whether to show a nudge, so the dashboard doesn't wait on it.
  void loadSpendingPlan()
  try {
    const [linked] = await Promise.all([savedItemIds, loadAccountList()])
    if (disposed) return
    itemIds.value = linked
    // The remembered account may be gone or no longer tracked; load what's shown instead.
    if (selectedAccountId.value !== requested) void loadCards()
  } catch {
    if (!disposed)
      connectionError.value = 'We couldn’t load your connected accounts. Please try again.'
  } finally {
    if (!disposed) initialLoading.value = false
  }
}

function loadCards() {
  return Promise.all([loadTransactions(), loadSpending(), loadRecurring()])
}

function onAccountChange(event: Event) {
  const target = event.target
  if (!(target instanceof HTMLSelectElement)) return
  if (!selectAccount(target.value || undefined)) return
  void loadCards()
}

async function loadSpendingPlan() {
  try {
    const plan = await getSpendingPlan()
    if (!disposed) hasSpendingPlan.value = plan !== null
  } catch {
    // The prompt is only a nudge, so leave it hidden when the plan can't be checked.
  }
}

async function loadSpending() {
  clearTimeout(unsortedRecheck)
  unsortedRechecks = 0
  loadingSpending.value = true
  spendingError.value = ''
  const accountId = selectedAccountId.value
  const current = () => !disposed && accountId === selectedAccountId.value
  try {
    const summary = await getSpendingByBucket(accountId)
    if (!current()) return
    spending.value = summary
    recheckUnsorted(accountId)
  } catch {
    if (current()) spendingError.value = 'We couldn’t load your spending breakdown.'
  } finally {
    if (current()) loadingSpending.value = false
  }
}

// Refreshes quietly in place, and gives up after a while in case sorting is switched off.
function recheckUnsorted(accountId: string | undefined) {
  const waiting = spending.value?.buckets.some((entry) => entry.bucket === 'UNSORTED')
  if (!waiting || unsortedRechecks >= UNSORTED_RECHECK_LIMIT) return
  unsortedRecheck = setTimeout(async () => {
    unsortedRechecks++
    try {
      const summary = await getSpendingByBucket(accountId)
      if (disposed || accountId !== selectedAccountId.value) return
      // New data rebuilds the chart and replays its animation, so only swap it in when it changed.
      if (JSON.stringify(summary) !== JSON.stringify(spending.value)) spending.value = summary
      recheckUnsorted(accountId)
    } catch {
      // Keep showing what loaded; the next visit tries again.
    }
  }, UNSORTED_RECHECK_MS)
}

async function loadTransactions() {
  loadingTransactions.value = true
  transactionsError.value = ''
  const accountId = selectedAccountId.value
  const current = () => !disposed && accountId === selectedAccountId.value
  try {
    const recent = await getTransactions(RECENT_TRANSACTION_COUNT, accountId)
    if (current()) transactions.value = recent
  } catch {
    if (current()) transactionsError.value = 'We couldn’t load your recent transactions.'
  } finally {
    if (current()) loadingTransactions.value = false
  }
}

async function loadRecurring() {
  loadingRecurring.value = true
  recurringError.value = ''
  recurringSyncFailed.value = false
  answerError.value = ''
  const accountId = selectedAccountId.value
  const current = () => !disposed && accountId === selectedAccountId.value
  try {
    // Candidates are only guesses, so Plaid's streams still show when they can't be loaded.
    const [streams, found] = await Promise.all([
      getRecurringTransactions(accountId, RECURRING_STREAM_COUNT),
      getRecurringCandidates(accountId).catch(() => []),
    ])
    if (!current()) return
    recurring.value = streams.slice(0, RECURRING_STREAM_COUNT)
    candidates.value = found
  } catch {
    if (current()) recurringError.value = 'We couldn’t load your recurring transactions.'
  } finally {
    if (current()) loadingRecurring.value = false
  }
}

/**
 * Saves the user's yes or no, or with null forgets it. The list changes straight away and goes
 * back if the save fails.
 */
async function answerCandidate(candidate: RecurringCandidate, confirmed: boolean | null) {
  if (answeringKey.value) return
  const previous = candidate.status
  answeringKey.value = candidate.stream_id
  answerError.value = ''
  setStatus(candidate, confirmed === null ? 'SUGGESTED' : confirmed ? 'CONFIRMED' : 'DISMISSED')
  try {
    if (confirmed === null) await undoRecurringAnswer(candidate)
    else await answerRecurringCandidate(candidate, confirmed)
  } catch {
    if (disposed) return
    setStatus(candidate, previous)
    answerError.value = 'We couldn’t save your answer. Please try again.'
  } finally {
    if (!disposed) answeringKey.value = undefined
  }
}

function setStatus(candidate: RecurringCandidate, status: RecurringCandidate['status']) {
  candidates.value = candidates.value.map((entry) =>
    entry.stream_id === candidate.stream_id ? { ...entry, status } : entry,
  )
}

async function syncRecurring() {
  syncingRecurring.value = true
  recurringError.value = ''
  recurringSyncFailed.value = false
  try {
    await syncRecurringTransactions()
    if (disposed) return
    await loadRecurring()
  } catch {
    if (disposed) return
    recurringError.value = 'We couldn’t sync your recurring transactions.'
    recurringSyncFailed.value = true
  } finally {
    if (!disposed) syncingRecurring.value = false
  }
}

async function finishLink(publicToken: string) {
  linking.value = true
  linkError.value = ''
  pendingPublicToken.value = publicToken
  try {
    const { item_id: itemId, same_institution } = await exchangePublicToken(publicToken)
    if (disposed) return
    pendingPublicToken.value = undefined
    sameInstitution.value = same_institution
    if (!itemIds.value.includes(itemId)) itemIds.value.push(itemId)
    await loadAccountList()
    if (disposed) return
    void loadSpendingPlan()
    await loadCards()
  } catch {
    if (!disposed) linkError.value = 'Your account connection could not be saved. Please try again.'
  } finally {
    if (!disposed) linking.value = false
  }
}

function onOlderRemoved() {
  sameInstitution.value = []
  void loadConnections()
}

function retryLink() {
  if (pendingPublicToken.value) void finishLink(pendingPublicToken.value)
  else void openPlaidLink()
}

async function openPlaidLink() {
  if (linking.value) return
  linking.value = true
  linkError.value = ''

  try {
    if (!window.Plaid) throw new Error('Plaid Link unavailable')
    const token = await createLinkToken()
    if (disposed) return
    handler?.destroy()
    handler = window.Plaid.create({
      token,
      onSuccess(publicToken) {
        if (disposed) return
        void finishLink(publicToken)
      },
      onExit(error) {
        if (disposed) return
        linking.value = false
        if (error) linkError.value = 'Unable to connect your account. Please try again.'
      },
    })
    handler.open()
  } catch {
    if (disposed) return
    linkError.value = 'Unable to open account connection. Please try again.'
    linking.value = false
  }
}
</script>

<template>
  <div class="app-shell">
    <AppSidebar />
    <div class="home-page">
      <main class="page-content" aria-label="Dashboard">
        <div class="overview-heading">
          <div>
            <h1>{{ greeting }}</h1>
            <p>Here's what's happening with your finances</p>
          </div>
          <div class="overview-actions">
            <Button
              v-if="hasConnections"
              :label="linking ? 'Connecting…' : 'Add an account'"
              :loading="linking"
              :disabled="linking"
              :aria-busy="linking"
              class="add-account-button"
              @click="openPlaidLink"
            >
              <template #icon v-if="!linking">
                <Plus :size="16" :stroke-width="1.75" aria-hidden="true" />
              </template>
            </Button>
            <UserButton />
          </div>
        </div>

        <SameInstitutionNotice
          v-if="sameInstitution.length"
          class="same-institution-notice"
          :items="sameInstitution"
          @removed="onOlderRemoved"
          @dismiss="sameInstitution = []"
        />

        <section
          v-if="initialLoading"
          class="panel balance-card"
          role="status"
          aria-label="Loading your accounts"
          aria-busy="true"
        >
          <p class="card-label">Balance</p>
          <Skeleton width="min(100%, 20rem)" height="4.5rem" />
        </section>

        <div v-else-if="connectionError" class="account-notice">
          <Message severity="error">{{ connectionError }}</Message>
          <Button
            label="Try again"
            severity="secondary"
            class="retry-button"
            @click="loadConnections"
          />
        </div>

        <div v-else-if="hasConnections" class="dashboard-grid">
          <div class="dashboard-main">
            <section
              class="panel balance-card"
              role="region"
              aria-labelledby="balance-heading"
              :aria-busy="loadingAccounts"
            >
              <div class="balance-heading">
                <h2 id="balance-heading" class="card-label">Balance</h2>
                <select
                  v-if="hasMultipleAccounts"
                  class="account-select"
                  aria-label="Account"
                  :value="selectedAccountId ?? ''"
                  :disabled="loadingAccounts"
                  @change="onAccountChange($event)"
                >
                  <option value="">All tracked accounts</option>
                  <option
                    v-for="account in accounts"
                    :key="account.account_id"
                    :value="account.account_id"
                  >
                    {{ accountLabel(account) }}
                  </option>
                </select>
              </div>
              <div v-if="loadingAccounts" role="status" aria-label="Loading balances">
                <Skeleton width="min(100%, 20rem)" height="4.5rem" />
              </div>
              <template v-else>
                <p
                  class="balance-amount"
                  aria-live="polite"
                  aria-atomic="true"
                  :aria-label="balance == null ? 'Balance unavailable' : undefined"
                >
                  {{ formatBalance(balance) }}
                </p>
                <p v-if="!hasMultipleAccounts && selectedAccountLabel" class="balance-account">
                  {{ selectedAccountLabel }}
                </p>
              </template>
            </section>

            <div
              v-if="!loadingAccounts && (balanceError || !accounts.length)"
              class="account-notice"
            >
              <Message :severity="balanceError ? 'error' : 'secondary'">
                <template v-if="balanceError">{{ balanceError }}</template>
                <template v-else>
                  You’re not tracking spending from any account yet.
                  <RouterLink to="/accounts">Choose accounts</RouterLink>
                </template>
              </Message>
              <Button
                v-if="balanceError"
                label="Try again"
                severity="secondary"
                class="retry-button"
                @click="loadAccountList"
              />
            </div>

            <section
              class="panel transactions-card"
              role="region"
              aria-labelledby="recent-transactions-heading"
              :aria-busy="loadingTransactions"
            >
              <div class="card-heading">
                <h2 id="recent-transactions-heading" class="card-label">Recent transactions</h2>
                <Button
                  v-if="transactions.length"
                  label="View all"
                  severity="secondary"
                  size="small"
                  text
                  aria-haspopup="dialog"
                  class="view-all-button"
                  @click="allTransactionsVisible = true"
                />
              </div>
              <div
                v-if="loadingTransactions"
                class="transactions-loading"
                role="status"
                aria-label="Loading recent transactions"
              >
                <Skeleton v-for="row in 4" :key="row" height="2.75rem" />
              </div>

              <div v-else-if="transactionsError" class="account-notice">
                <Message severity="error">{{ transactionsError }}</Message>
                <Button
                  label="Try again"
                  severity="secondary"
                  class="retry-button"
                  @click="loadTransactions"
                />
              </div>

              <p v-else-if="!transactions.length" class="transactions-empty">
                No transactions yet. They’ll appear here once your bank sends them.
              </p>

              <ul v-else class="transactions-list" tabindex="0">
                <li
                  v-for="transaction in transactions"
                  :key="transaction.transaction_id"
                  class="transaction-row"
                >
                  <img
                    v-if="transaction.logo_url"
                    class="transaction-logo"
                    :src="transaction.logo_url"
                    alt=""
                  />
                  <span
                    v-else
                    class="transaction-logo transaction-logo-fallback"
                    aria-hidden="true"
                  >
                    {{ transactionLabel(transaction).charAt(0) }}
                  </span>
                  <span class="transaction-details">
                    <span class="transaction-name">{{ transactionLabel(transaction) }}</span>
                    <span class="transaction-meta">
                      {{ formatTransactionDate(transaction.date) }}
                      <template v-if="transaction.pending"> · Pending</template>
                      <template v-if="transaction.share_percent < 100">
                        · Shared, yours {{ transaction.share_percent }}%
                      </template>
                    </span>
                  </span>
                  <span
                    class="transaction-amount"
                    :class="{ 'transaction-amount-inflow': transaction.amount < 0 }"
                  >
                    {{ formatTransactionAmount(transaction) }}
                  </span>
                </li>
              </ul>
            </section>

            <section
              class="panel recurring-card"
              role="region"
              aria-labelledby="recurring-heading"
              :aria-busy="loadingRecurring"
            >
              <div class="card-heading">
                <h2 id="recurring-heading" class="card-label">Recurring</h2>
                <Button
                  :label="syncingRecurring ? 'Syncing…' : 'Sync'"
                  severity="secondary"
                  size="small"
                  text
                  class="view-all-button"
                  :disabled="syncingRecurring || loadingRecurring"
                  @click="syncRecurring"
                />
              </div>
              <div
                v-if="loadingRecurring"
                class="transactions-loading"
                role="status"
                aria-label="Loading recurring transactions"
              >
                <Skeleton v-for="row in 3" :key="row" height="2.75rem" />
              </div>

              <div v-else-if="recurringError" class="account-notice">
                <Message severity="error">{{ recurringError }}</Message>
                <Button
                  label="Try again"
                  severity="secondary"
                  class="retry-button"
                  @click="recurringSyncFailed ? syncRecurring() : loadRecurring()"
                />
              </div>

              <template v-else>
                <p
                  v-if="!recurringList.length && !suggestedCandidates.length"
                  class="transactions-empty"
                >
                  No recurring transactions found yet. Plaid can take up to a day to find them after
                  you link a bank.
                </p>

                <ul v-if="recurringList.length" class="transactions-list" tabindex="0">
                  <li
                    v-for="stream in recurringList"
                    :key="stream.stream_id"
                    class="transaction-row"
                  >
                    <span class="transaction-logo transaction-logo-fallback" aria-hidden="true">
                      {{ recurringLabel(stream).charAt(0) }}
                    </span>
                    <span class="transaction-details">
                      <span class="transaction-name">{{ recurringLabel(stream) }}</span>
                      <span class="transaction-meta">
                        {{ recurringMeta(stream) }}
                        <template v-if="isConfirmedCandidate(stream)">
                          · Confirmed by you ·
                          <button
                            type="button"
                            class="candidate-undo"
                            :disabled="answeringKey !== undefined"
                            :aria-label="`Undo confirming ${recurringLabel(stream)}`"
                            @click="answerCandidate(stream, null)"
                          >
                            Undo
                          </button>
                        </template>
                      </span>
                    </span>
                    <span
                      class="transaction-amount"
                      :class="{ 'transaction-amount-inflow': stream.amount < 0 }"
                    >
                      {{ formatRecurringAmount(stream) }}
                    </span>
                  </li>
                </ul>

                <Message v-if="answerError" severity="error" class="candidate-error">
                  {{ answerError }}
                </Message>

                <div
                  v-if="suggestedCandidates.length"
                  class="candidates"
                  role="group"
                  aria-labelledby="candidates-heading"
                >
                  <h3 id="candidates-heading" class="candidates-heading">Possibly recurring</h3>
                  <p class="candidates-hint">
                    Plaid hasn’t seen these enough times to be sure. Do they repeat?
                  </p>
                  <ul class="transactions-list candidates-list" tabindex="0">
                    <li
                      v-for="candidate in suggestedCandidates"
                      :key="candidate.stream_id"
                      class="transaction-row"
                    >
                      <span class="transaction-logo transaction-logo-fallback" aria-hidden="true">
                        {{ recurringLabel(candidate).charAt(0) }}
                      </span>
                      <span class="transaction-details">
                        <span class="transaction-name">{{ recurringLabel(candidate) }}</span>
                        <span class="transaction-meta">
                          {{ candidateMeta(candidate) }} · {{ formatRecurringAmount(candidate) }}
                        </span>
                      </span>
                      <span class="candidate-actions">
                        <Button
                          label="Yes"
                          size="small"
                          severity="secondary"
                          :disabled="answeringKey !== undefined"
                          :aria-label="`Yes, ${recurringLabel(candidate)} repeats`"
                          @click="answerCandidate(candidate, true)"
                        />
                        <Button
                          label="No"
                          size="small"
                          severity="secondary"
                          text
                          :disabled="answeringKey !== undefined"
                          :aria-label="`No, ${recurringLabel(candidate)} doesn’t repeat`"
                          @click="answerCandidate(candidate, false)"
                        />
                      </span>
                    </li>
                  </ul>
                </div>

                <template v-if="dismissedCandidates.length">
                  <Button
                    :label="
                      showDismissed
                        ? 'Hide dismissed'
                        : `Show dismissed (${dismissedCandidates.length})`
                    "
                    severity="secondary"
                    size="small"
                    text
                    class="dismissed-toggle"
                    :aria-expanded="showDismissed"
                    aria-controls="dismissed-candidates"
                    @click="showDismissed = !showDismissed"
                  />
                  <ul
                    v-if="showDismissed"
                    id="dismissed-candidates"
                    class="transactions-list candidates-list"
                    tabindex="0"
                  >
                    <li
                      v-for="candidate in dismissedCandidates"
                      :key="candidate.stream_id"
                      class="transaction-row"
                    >
                      <span class="transaction-logo transaction-logo-fallback" aria-hidden="true">
                        {{ recurringLabel(candidate).charAt(0) }}
                      </span>
                      <span class="transaction-details">
                        <span class="transaction-name">{{ recurringLabel(candidate) }}</span>
                        <span class="transaction-meta">Not recurring</span>
                      </span>
                      <Button
                        label="Undo"
                        size="small"
                        severity="secondary"
                        text
                        class="candidate-actions"
                        :disabled="answeringKey !== undefined"
                        :aria-label="`Undo dismissing ${recurringLabel(candidate)}`"
                        @click="answerCandidate(candidate, null)"
                      />
                    </li>
                  </ul>
                </template>
              </template>
            </section>
          </div>

          <section
            class="panel spending-card"
            role="region"
            aria-labelledby="spending-heading"
            :aria-busy="loadingSpending"
          >
            <h2 id="spending-heading" class="card-label">
              Spending<template v-if="spendingMonth"> · {{ spendingMonth }}</template>
            </h2>
            <div
              v-if="loadingSpending"
              class="spending-chart"
              role="status"
              aria-label="Loading your spending breakdown"
            >
              <Skeleton width="100%" height="100%" border-radius="50%" />
            </div>

            <div v-else-if="spendingError" class="account-notice">
              <Message severity="error">{{ spendingError }}</Message>
              <Button
                label="Try again"
                severity="secondary"
                class="retry-button"
                @click="loadSpending"
              />
            </div>

            <p v-else-if="!spendingLegend.length" class="spending-empty">
              No spending recorded this month yet.
            </p>

            <div v-else class="spending-body">
              <p v-if="hasSpendingPlan === false" class="spending-plan-prompt">
                <RouterLink to="/spending-plan">Create your spending plan</RouterLink>
                to set a target for each bucket.
              </p>
              <div class="spending-chart">
                <Chart
                  type="doughnut"
                  :data="spendingChartData"
                  :options="spendingChartOptions"
                  class="spending-chart-canvas"
                  :aria-label="`Spending by bucket for ${spendingMonth}`"
                />
                <div class="spending-total" aria-hidden="true">
                  <span class="spending-total-amount">{{ formatWholeDollars(spendingTotal) }}</span>
                  <span class="spending-total-label">this month</span>
                </div>
              </div>
              <ul class="spending-legend">
                <li
                  v-for="entry in spendingLegend"
                  :key="entry.key"
                  :class="{ 'spending-open': isOpen(entry) }"
                >
                  <button
                    type="button"
                    class="spending-legend-row"
                    :aria-expanded="isOpen(entry)"
                    :aria-controls="`spending-row-${entry.key}`"
                    @click="toggle(entry)"
                  >
                    <span
                      class="spending-swatch"
                      :style="{ backgroundColor: entry.color }"
                      aria-hidden="true"
                    />
                    <span class="spending-legend-label">{{ entry.label }}</span>
                    <span class="spending-legend-amount">{{ formatBalance(entry.amount) }}</span>
                    <ChevronDown
                      class="spending-chevron"
                      :size="14"
                      :stroke-width="1.75"
                      aria-hidden="true"
                    />
                  </button>
                  <div
                    :id="`spending-row-${entry.key}`"
                    class="spending-panel"
                    :inert="!isOpen(entry) || undefined"
                  >
                    <ul class="spending-categories" :aria-label="`${entry.label} by category`">
                      <li
                        v-for="category in entry.categories"
                        :key="category.category"
                        :class="{ 'spending-open': isOpen(category) }"
                      >
                        <button
                          type="button"
                          class="spending-legend-row spending-category-row"
                          :aria-expanded="isOpen(category)"
                          :aria-controls="`spending-category-${entry.key}-${category.category}`"
                          @click="toggle(category)"
                        >
                          <span class="spending-legend-label">{{ category.label }}</span>
                          <span class="spending-legend-amount">
                            {{ formatBalance(category.amount) }}
                          </span>
                          <ChevronDown
                            class="spending-chevron"
                            :size="14"
                            :stroke-width="1.75"
                            aria-hidden="true"
                          />
                        </button>
                        <div
                          :id="`spending-category-${entry.key}-${category.category}`"
                          class="spending-panel"
                          :inert="!isOpen(category) || undefined"
                        >
                          <ul
                            class="spending-transactions"
                            :aria-label="`${category.label} transactions`"
                          >
                            <li
                              v-for="transaction in category.transactions"
                              :key="transaction.transaction_id"
                              class="spending-transaction-row"
                            >
                              <span class="spending-transaction-name">
                                {{ transactionLabel(transaction) }}
                              </span>
                              <span class="spending-transaction-date">
                                {{ spendingDate(transaction) }}
                              </span>
                              <span class="spending-legend-amount">
                                {{ formatBalance(yourAmount(transaction)) }}
                              </span>
                            </li>
                          </ul>
                        </div>
                      </li>
                    </ul>
                  </div>
                </li>
              </ul>
            </div>
          </section>
        </div>

        <section v-else class="panel account-prompt" aria-labelledby="account-prompt-heading">
          <div class="account-prompt-content">
            <div class="account-icon" aria-hidden="true">
              <Landmark :size="22" :stroke-width="1.5" />
            </div>
            <h2 id="account-prompt-heading">Start with an account.</h2>
            <p>Connect a bank or credit card account to bring your finances into view.</p>
            <Button
              :label="linking ? 'Connecting…' : 'Add an account'"
              :loading="linking"
              :disabled="linking"
              :aria-busy="linking"
              @click="openPlaidLink"
            >
              <template #icon v-if="!linking">
                <Plus :size="16" :stroke-width="1.75" aria-hidden="true" />
              </template>
            </Button>
            <span class="connection-note">Connect through Plaid</span>
          </div>
        </section>
        <div v-if="linkError" class="account-notice">
          <Message severity="error">{{ linkError }}</Message>
          <Button
            label="Try again"
            severity="secondary"
            class="retry-button"
            :loading="linking"
            :disabled="linking"
            @click="retryLink"
          />
        </div>
      </main>
    </div>
    <TransactionsDialog
      v-model:visible="allTransactionsVisible"
      :account-id="selectedAccountId"
      :account-label="selectedAccountLabel"
    />
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  align-items: stretch;
  height: 100svh;
}

.home-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

.page-content {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: 100%;
  min-height: 0;
  padding: 1.25rem 1.5rem 1.5rem 0;
}

.overview-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-bottom: 1.125rem;
}

h1 {
  font-size: 1.5rem;
  font-weight: 550;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.same-institution-notice {
  margin-bottom: 1.125rem;
}

.overview-heading p {
  margin-top: 0.35rem;
  color: var(--app-text-secondary);
}

.overview-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 0.75rem;
}

.overview-actions :deep(.cl-userButtonTrigger),
.overview-actions :deep(.cl-avatarBox) {
  width: 2rem;
  height: 2rem;
}

.dashboard-grid {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(0, 1.2fr) minmax(18rem, 0.8fr);
  align-items: stretch;
  gap: 1rem;
  min-height: 0;
}

.dashboard-main {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  min-width: 0;
  min-height: 0;
}

.dashboard-main > .account-notice {
  margin-top: 0;
}

.balance-card,
.transactions-card,
.recurring-card,
.spending-card,
.account-prompt {
  padding: 1rem 1.25rem 1.125rem;
}

.balance-card {
  flex: none;
  padding: 1.125rem 1.25rem 1.25rem;
}

.transactions-card,
.recurring-card {
  display: flex;
  flex-direction: column;
  flex: none;
}

.spending-card {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 0;
  min-height: 0;
  container-type: inline-size;
}

.spending-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  /* Anchored to the top, so opening a bucket adds rows below without moving the chart. */
  justify-content: flex-start;
  gap: 1.25rem;
  min-height: 0;
}

.spending-chart {
  position: relative;
  flex: none;
  width: min(22rem, 85cqi, 100%);
  aspect-ratio: 1;
  height: auto;
  margin: 0.5rem auto 0;
}

.spending-chart-canvas,
.spending-chart-canvas :deep(canvas) {
  display: block;
  width: 100%;
  height: 100%;
}

.spending-total {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  pointer-events: none;
}

.spending-total-amount {
  font-size: 1.75rem;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
}

.spending-total-label {
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

/* Opened buckets scroll within the card instead of stretching it. */
.spending-legend {
  display: grid;
  flex: 0 1 auto;
  align-content: start;
  gap: 0.125rem;
  min-height: 0;
  margin: 0;
  padding: 0 0.25rem 0 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  list-style: none;
  /* Room for the scrollbar up front, so amounts don't shift when the list starts scrolling. */
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: rgb(11 11 11 / 28%) transparent;
}

.spending-legend-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  width: 100%;
  min-height: 1.75rem;
  padding: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--app-radius-chip);
}

.spending-legend-row:focus-visible {
  outline: 2px solid var(--app-text);
  outline-offset: 2px;
}

.spending-chevron {
  flex: none;
  color: var(--app-text-secondary);
  transition: transform 120ms cubic-bezier(0.2, 0, 0, 1);
}

.spending-open > .spending-legend-row .spending-chevron {
  transform: rotate(180deg);
}

/*
 * Opening only fades and slides the rows, which the browser can do without laying out the card
 * again each frame; animating the height made it lag. Closing is instant. Child selectors keep
 * an open bucket from opening its categories too.
 */
.spending-panel {
  display: none;
}

.spending-open > .spending-panel {
  display: block;
}

.spending-open > .spending-panel > ul {
  animation: spending-reveal 120ms cubic-bezier(0.2, 0, 0, 1);
}

@keyframes spending-reveal {
  from {
    opacity: 0;
    transform: translateY(-0.25rem);
  }
}

@media (prefers-reduced-motion: reduce) {
  .spending-chevron {
    transition: none;
  }

  .spending-open > .spending-panel > ul {
    animation: none;
  }
}

.spending-categories {
  display: grid;
  gap: 0.125rem;
  margin: 0;
  /* Lines category names up with the bucket name, past the swatch. */
  padding: 0 0 0.375rem 1.125rem;
  list-style: none;
}

.spending-category-row {
  min-height: 1.5rem;
}

.spending-category-row .spending-legend-label,
.spending-category-row .spending-legend-amount {
  color: var(--app-text-secondary);
  font-weight: 400;
}

.spending-transactions {
  display: grid;
  margin: 0;
  /* Amounts line up with the category amounts, left of the chevron column. */
  padding: 0 1.5rem 0.25rem 0.75rem;
  list-style: none;
}

/* Without buckets, transactions sit right under a top row, so indent past its swatch too. */
.spending-legend > li > .spending-panel > .spending-transactions {
  padding-left: 1.875rem;
}

.spending-transaction-row {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  min-height: 1.375rem;
  font-size: 0.75rem;
}

.spending-transaction-name {
  min-width: 0;
  overflow: hidden;
  color: var(--app-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spending-transaction-date {
  flex: none;
  color: var(--app-text-subdued);
}

.spending-transaction-row .spending-legend-amount {
  color: var(--app-text-secondary);
  font-size: 0.75rem;
  font-weight: 400;
}

.spending-swatch {
  flex: none;
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 50%;
}

.spending-legend-label {
  min-width: 0;
  overflow: hidden;
  color: var(--app-text);
  font-size: 0.8125rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spending-legend-amount {
  margin-left: auto;
  padding-left: 0.5rem;
  color: var(--app-text);
  font-size: 0.8125rem;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.spending-empty {
  color: var(--app-text-secondary);
  line-height: 1.65;
}

.spending-plan-prompt {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 0.875rem;
  line-height: 1.5;
}

.spending-plan-prompt a {
  color: var(--app-text);
  font-weight: 500;
}

.card-label {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  font-weight: 500;
  letter-spacing: -0.005em;
}

.card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  min-height: 1.75rem;
}

.view-all-button {
  margin-right: -0.5rem;
}

.balance-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.account-select {
  min-width: 11rem;
  max-width: 16rem;
  appearance: none;
  padding: 0.375rem 1.75rem 0.375rem 0.625rem;
  color: var(--app-text);
  background-color: var(--app-surface);
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' fill='none' stroke='%23737373' stroke-linecap='round' stroke-linejoin='round' stroke-width='1.75' viewBox='0 0 24 24'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-position: right 0.4rem center;
  background-repeat: no-repeat;
  border: 1px solid var(--app-control-border);
  border-radius: var(--app-radius-chip);
  box-shadow: var(--app-shadow-xs);
  font: inherit;
  font-size: 0.8125rem;
  font-weight: 500;
}

.transactions-loading {
  display: grid;
  gap: 0.625rem;
  margin-top: 0.75rem;
}

.transactions-empty {
  margin-top: 0.75rem;
  color: var(--app-text-secondary);
  line-height: 1.65;
}

.transactions-list {
  display: grid;
  align-content: start;
  margin: 1rem 0 0;
  padding: 0 0.25rem 0.25rem 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  list-style: none;
  scrollbar-width: thin;
  scrollbar-color: rgb(11 11 11 / 28%) transparent;
}

.transactions-card .transactions-list {
  max-height: 18rem;
}

.recurring-card .transactions-list {
  max-height: 13.5rem;
}

.candidates {
  margin-top: 1rem;
  padding-top: 0.875rem;
  border-top: 1px solid var(--app-divider);
}

.candidates-heading {
  margin: 0;
  color: var(--app-text);
  font-size: 0.8125rem;
  font-weight: 550;
}

.candidates-hint {
  margin: 0.25rem 0 0;
  color: var(--app-text-secondary);
  font-size: 0.75rem;
  line-height: 1.5;
}

.recurring-card .candidates-list {
  max-height: 11rem;
  margin-top: 0.25rem;
}

.candidate-actions {
  display: flex;
  flex: none;
  gap: 0.25rem;
  margin-left: auto;
}

.candidate-undo {
  padding: 0;
  color: var(--app-text);
  font: inherit;
  text-decoration: underline;
  text-underline-offset: 2px;
  background: none;
  border: 0;
  cursor: pointer;
}

.candidate-undo:disabled {
  cursor: default;
  opacity: 0.5;
}

.candidate-error {
  margin-top: 0.75rem;
}

.dismissed-toggle {
  align-self: flex-start;
  margin: 0.5rem 0 0 -0.5rem;
}

.transactions-list::-webkit-scrollbar {
  width: 0.375rem;
}

.transactions-list::-webkit-scrollbar-thumb {
  background: rgb(11 11 11 / 28%);
  border-radius: 999px;
}

.transaction-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-height: 3.25rem;
  padding: 0.875rem 0;
}

.transaction-row:not(:last-child)::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  left: 2.375rem;
  height: 1px;
  background: var(--app-divider);
}

.transaction-logo {
  flex: none;
  display: grid;
  width: 1.75rem;
  height: 1.75rem;
  place-items: center;
  object-fit: cover;
  background: var(--app-inset);
  border-radius: 50%;
}

.transaction-logo-fallback {
  color: var(--app-text-secondary);
  font-size: 0.75rem;
  font-weight: 550;
  text-transform: uppercase;
}

.transaction-details {
  display: grid;
  gap: 0.25rem;
  min-width: 0;
}

.transaction-name {
  overflow: hidden;
  color: var(--app-text);
  font-size: 0.875rem;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.transaction-meta {
  color: var(--app-text-secondary);
  font-size: 0.75rem;
}

.transaction-amount {
  margin-left: auto;
  padding-left: 0.5rem;
  color: var(--app-text);
  font-size: 0.875rem;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.transaction-amount-inflow {
  color: var(--app-success);
}

.balance-amount {
  overflow-wrap: anywhere;
  color: var(--app-text);
  font-size: 2.75rem;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  letter-spacing: -0.05em;
}

.balance-account {
  margin-top: 0.375rem;
  color: var(--app-text-secondary);
  font-size: 0.875rem;
}

.retry-button {
  margin-top: 1rem;
}

.account-prompt {
  display: grid;
  flex: 1;
  min-height: 20rem;
  place-items: center;
  padding: 3rem 1.5rem;
}

.account-prompt-content {
  width: min(100%, 22rem);
  text-align: center;
}

.account-icon {
  display: grid;
  width: 2.75rem;
  height: 2.75rem;
  place-items: center;
  margin: 0 auto 1.25rem;
  background: var(--app-inset);
  color: var(--app-text-secondary);
  border-radius: var(--app-radius-control);
}

.account-prompt h2 {
  font-size: 1.25rem;
  font-weight: 550;
  letter-spacing: -0.03em;
}

.account-prompt p {
  margin: 0.5rem 0 1.25rem;
  color: var(--app-text-secondary);
  line-height: 1.6;
}

.connection-note {
  display: block;
  margin-top: 0.75rem;
  font-size: 0.75rem;
  color: var(--app-text-subdued);
}

.account-notice {
  margin-top: 1rem;
  text-align: left;
}

@media (max-width: 900px) {
  .app-shell {
    height: auto;
    min-height: 100svh;
  }

  .dashboard-grid {
    grid-template-columns: 1fr;
    align-items: start;
  }

  .transactions-card,
  .recurring-card,
  .spending-card,
  .account-prompt {
    flex: none;
    overflow: visible;
  }

  .page-content {
    padding: 1.25rem 1.25rem calc(var(--app-tabbar-height) + env(safe-area-inset-bottom) + 1.5rem);
  }

  .overview-heading {
    margin-bottom: 1.25rem;
  }

  h1 {
    font-size: 1.375rem;
  }

  .balance-amount {
    font-size: 2.25rem;
  }
}

@media (max-width: 640px) {
  .balance-card,
  .transactions-card,
  .recurring-card,
  .spending-card {
    padding: 1.125rem 1.125rem 1.25rem;
  }

  .spending-chart {
    width: min(100%, 18rem);
  }

  .account-prompt {
    min-height: 18rem;
    padding: 2rem 1rem;
  }
}
</style>
