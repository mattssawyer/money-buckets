<script setup lang="ts">
import { UserButton } from '@clerk/vue'
import { Landmark, Plus, TrendingDown, TrendingUp, X } from '@lucide/vue'
import { computed, onMounted, onUnmounted, ref, useTemplateRef } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import AppSidebar from '../components/AppSidebar.vue'
import BalanceChart from '../components/BalanceChart.vue'
import SameInstitutionNotice from '../components/SameInstitutionNotice.vue'
import {
  addInvestments,
  createLinkToken,
  exchangePublicToken,
  getAccounts,
  getLinkedItems,
  updateAccountTracking,
  type PlaidAccount,
  type PlaidItem,
} from '../api/PlaidService'
import {
  getBalanceHistory,
  type BalanceHistory,
  type BalancePoint,
} from '../api/InvestmentsService'
import { accountLabel } from '../accounts/useSelectedAccount'
import {
  RANGES,
  changeOver,
  formatChange,
  formatDay,
  formatMoney,
  latestValue,
  rangeStart,
  type Change,
  type Range,
} from '../investments/history'
import { closePlaidLink, openPlaidLink } from '../plaid/plaidLink'

const NET_WORTH_COLOR = '#171717'

/** What net worth is made of, in the order it's listed: what's invested, what's in the bank, what's owed. */
type Group = 'investments' | 'bank' | 'debts'

const GROUPS: { id: Group; heading: string; color: string }[] = [
  { id: 'investments', heading: 'Investment accounts', color: '#eb6834' },
  { id: 'bank', heading: 'Bank accounts', color: '#2a78d6' },
  { id: 'debts', heading: 'Credit cards and loans', color: '#737373' },
]

// "brokerage" is Plaid's legacy name for the investment type.
const GROUP_OF: Record<string, Group> = {
  investment: 'investments',
  brokerage: 'investments',
  depository: 'bank',
  credit: 'debts',
  loan: 'debts',
}

const SUBTYPE_LABELS: Record<string, string> = {
  '401k': '401(k)',
  '403B': '403(b)',
  '457b': '457(b)',
  '529': '529',
  brokerage: 'Brokerage',
  hsa: 'HSA',
  ira: 'IRA',
  roth: 'Roth IRA',
  'roth 401k': 'Roth 401(k)',
  'sep ira': 'SEP IRA',
  'simple ira': 'SIMPLE IRA',
  crypto: 'Crypto',
  'credit card': 'Credit card',
  'cash management': 'Cash management',
  'money market': 'Money market',
  cd: 'CD',
}

const GROUP_KIND: Record<Group, string> = {
  investments: 'Investment',
  bank: 'Bank account',
  debts: 'Loan',
}

const range = ref<Range>('3M')
const loading = ref(true)
const loadError = ref(false)
const historyLoading = ref(false)
const historyError = ref(false)
const items = ref<PlaidItem[]>([])
const accounts = ref<PlaidAccount[]>([])
const history = ref<BalanceHistory>()
const linking = ref(false)
const linkError = ref('')
const sameInstitution = ref<PlaidItem[]>([])
const addingItemId = ref<string>()
const addError = ref('')
// Outlives the connection it's about, which leaves the list once it's known not to offer investments.
const addNote = ref('')
// The account the big chart shows instead of net worth, if the user picked one.
const focusedId = ref<string>()
const figure = useTemplateRef<HTMLElement>('figure')
// The account being left out of net worth or added back; one is saved at a time.
const savingId = ref<string>()
const leaveOutError = ref('')
let disposed = false
let latestHistoryRequest = 0

const netWorth = computed(() => history.value?.net_worth ?? [])
const netWorthValue = computed(() => latestValue(netWorth.value))
const netWorthChange = computed(() => changeOver(netWorth.value))
const leftOutCount = computed(() => history.value?.left_out_of_net_worth.length ?? 0)
const accountChanges = computed(() =>
  [
    ...(history.value?.accounts_added ?? []).map((change) => ({ ...change, verb: 'added' })),
    ...(history.value?.accounts_dropped ?? []).map((change) => ({ ...change, verb: 'removed' })),
  ].sort((a, b) => a.date.localeCompare(b.date)),
)

/**
 * A card for every account that makes up net worth, as it counts toward it: a debt below zero,
 * and a shared account at the user's share. Accounts the user left out have no card; they're
 * listed under the grid to add back.
 */
const cards = computed(() =>
  (history.value?.accounts ?? []).flatMap((series) => {
    const account = accounts.value.find((candidate) => candidate.account_id === series.account_id)
    const group = account ? GROUP_OF[account.type] : undefined
    if (!account || !group || !account.counts_in_net_worth) return []
    const points = towardNetWorth(series.points, account, group)
    return [
      {
        id: series.account_id,
        group,
        name: accountLabel(account),
        kind: kind(account, group),
        color: GROUPS.find((candidate) => candidate.id === group)!.color,
        points,
        value: latestValue(points),
        change: changeOver(points),
      },
    ]
  }),
)

const cardGroups = computed(() =>
  GROUPS.map((group) => ({
    ...group,
    cards: cards.value.filter((card) => card.group === group.id),
  })),
)

/** Accounts of a kind net worth counts that the user chose to leave out of it. */
const leftOut = computed(() =>
  accounts.value
    .filter((account) => GROUP_OF[account.type] && !account.counts_in_net_worth)
    .map((account) => ({
      id: account.account_id,
      name: accountLabel(account),
      kind: kind(account, GROUP_OF[account.type]!),
    })),
)

// An account that's since been dropped or left out of net worth has no card to focus.
const focused = computed(() => cards.value.find((card) => card.id === focusedId.value))

/** What the big chart shows: net worth, or the account in focus. */
const shown = computed(() =>
  focused.value
    ? {
        label: focused.value.name,
        detail: focused.value.kind,
        color: focused.value.color,
        points: focused.value.points,
        value: focused.value.value,
        change: focused.value.change,
      }
    : {
        label: 'Net worth',
        detail: '',
        color: NET_WORTH_COLOR,
        points: netWorth.value,
        value: netWorthValue.value,
        change: netWorthChange.value,
      },
)

function towardNetWorth(points: BalancePoint[], account: PlaidAccount, group: Group) {
  const sign = group === 'debts' ? -1 : 1
  return points.map((point) => ({
    date: point.date,
    value: (sign * Math.round(point.value * account.share_percent)) / 100,
  }))
}

function kind(account: PlaidAccount, group: Group) {
  const subtype = account.subtype
  const label = subtype
    ? (SUBTYPE_LABELS[subtype] ?? subtype.charAt(0).toUpperCase() + subtype.slice(1))
    : GROUP_KIND[group]
  return account.share_percent < 100 ? `${label} · Your ${account.share_percent}%` : label
}

/** Puts an account on the big chart, or with the one already there, goes back to net worth. */
function focus(id: string) {
  focusedId.value = focusedId.value === id ? undefined : id
  // The big chart may be scrolled out of view above the cards.
  if (focusedId.value) figure.value?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' })
}

// Unknown availability still shows; trying finds out, and banks without investments drop off.
const itemsWithoutInvestments = computed(() =>
  items.value.filter((item) => !item.investments && item.investments_available !== false),
)

onMounted(load)
onUnmounted(() => {
  disposed = true
  closePlaidLink()
})

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const [linked, stored, balances] = await Promise.all([
      getLinkedItems(),
      getAccounts(),
      getBalanceHistory(rangeStart(range.value, new Date())),
    ])
    if (disposed) return
    items.value = linked
    accounts.value = stored
    history.value = balances
  } catch {
    if (!disposed) loadError.value = true
  } finally {
    if (!disposed) loading.value = false
  }
}

function selectRange(next: Range) {
  if (next === range.value) return
  range.value = next
  return reloadHistory()
}

/**
 * Loads the chosen range again and ignores results or errors from requests that a newer one has
 * replaced: for an earlier range, or from before an account was left out or added back.
 */
async function reloadHistory() {
  const request = ++latestHistoryRequest
  const current = () => !disposed && request === latestHistoryRequest
  historyLoading.value = true
  historyError.value = false
  try {
    const balances = await getBalanceHistory(rangeStart(range.value, new Date()))
    if (current()) history.value = balances
  } catch {
    if (current()) historyError.value = true
  } finally {
    // A newer request still loading owns these flags.
    if (current()) historyLoading.value = false
  }
}

/**
 * Leaves an account out of net worth, or adds it back: the same choice as on the Accounts page.
 * Its card goes or comes back at once, and net worth is worked out again without it.
 */
async function countInNetWorth(accountId: string, counts: boolean) {
  const account = accounts.value.find((candidate) => candidate.account_id === accountId)
  if (!account || savingId.value) return
  savingId.value = accountId
  leaveOutError.value = ''
  try {
    const saved = await updateAccountTracking(accountId, {
      tracks_spending: account.tracks_spending,
      counts_in_net_worth: counts,
      share_percent: account.share_percent,
    })
    if (disposed) return
    accounts.value = accounts.value.map((candidate) =>
      candidate.account_id === accountId ? saved : candidate,
    )
    if (focusedId.value === accountId) focusedId.value = undefined
    void reloadHistory()
  } catch {
    if (!disposed) {
      leaveOutError.value = `We couldn’t ${counts ? 'add' : 'leave out'} ${accountLabel(account)}. Please try again.`
    }
  } finally {
    if (!disposed) savingId.value = undefined
  }
}

async function linkInvestmentAccount() {
  if (linking.value) return
  linking.value = true
  linkError.value = ''
  try {
    const publicToken = await openPlaidLink(await createLinkToken({ investments: true }))
    if (disposed || !publicToken) return
    const result = await exchangePublicToken(publicToken)
    if (disposed) return
    sameInstitution.value = result.same_institution
    await load()
  } catch {
    if (!disposed) linkError.value = 'We couldn’t connect that account. Please try again.'
  } finally {
    if (!disposed) linking.value = false
  }
}

/**
 * Retries with Link update mode if the holdings check fails, then reloads page data. A bank that
 * doesn't offer investments leaves the list with a note saying so.
 */
async function addInvestmentsTo(item: PlaidItem) {
  if (addingItemId.value) return
  addingItemId.value = item.item_id
  addError.value = ''
  addNote.value = ''
  const institution = item.institution_name ?? 'that institution'
  try {
    let result = await addInvestments(item.item_id)
    if (result.outcome === 'needs_consent' && result.link_token) {
      // Plaid needs the user's consent first; update mode asks for it on the same connection.
      const finished = await openPlaidLink(result.link_token)
      if (disposed || !finished) return
      result = await addInvestments(item.item_id)
    }
    if (disposed) return
    if (result.outcome === 'not_offered') {
      addNote.value = `${item.institution_name ?? 'That institution'} doesn’t offer investment accounts through Plaid.`
    } else if (result.outcome !== 'added') {
      addError.value = `${item.institution_name ?? 'That institution'} didn’t share any investment accounts.`
      return
    }
    await load()
  } catch {
    if (!disposed)
      addError.value = `We couldn’t add investments from ${institution}. Please try again.`
  } finally {
    if (!disposed) addingItemId.value = undefined
  }
}

function onOlderRemoved() {
  sameInstitution.value = []
  void load()
}

function changeIcon(change: Change) {
  return change.amount < 0 ? TrendingDown : TrendingUp
}
</script>

<template>
  <div class="app-shell">
    <AppSidebar />
    <main class="investments-page" aria-labelledby="investments-heading">
      <header class="page-heading">
        <h1 id="investments-heading">Investments</h1>
        <div class="heading-actions">
          <Button
            v-if="items.length"
            :label="linking ? 'Connecting…' : 'Link investment account'"
            :loading="linking"
            :disabled="linking"
            @click="linkInvestmentAccount"
          >
            <template #icon v-if="!linking">
              <Plus :size="16" :stroke-width="1.75" aria-hidden="true" />
            </template>
          </Button>
          <UserButton />
        </div>
      </header>

      <SameInstitutionNotice
        v-if="sameInstitution.length"
        :items="sameInstitution"
        @removed="onOlderRemoved"
        @dismiss="sameInstitution = []"
      />
      <Message v-if="linkError" severity="error">{{ linkError }}</Message>

      <div v-if="loading" class="page-loading" aria-label="Loading your investments">
        <Skeleton height="22rem" />
        <div class="account-grid">
          <Skeleton height="14rem" />
          <Skeleton height="14rem" />
        </div>
      </div>

      <section v-else-if="loadError" class="panel prompt" aria-labelledby="load-error-heading">
        <div class="prompt-content">
          <h2 id="load-error-heading">We couldn’t load your investments.</h2>
          <p>Check your connection and try again.</p>
          <Button label="Try again" severity="secondary" @click="load" />
        </div>
      </section>

      <section v-else-if="!items.length" class="panel prompt" aria-labelledby="empty-heading">
        <div class="prompt-content">
          <div class="prompt-icon" aria-hidden="true">
            <TrendingUp :size="24" :stroke-width="1.5" />
          </div>
          <h2 id="empty-heading">Track your investments</h2>
          <p>
            Link an IRA, 401(k) or brokerage account to follow its balance, alongside your net worth
            across everything you’ve connected.
          </p>
          <Button
            :label="linking ? 'Connecting…' : 'Link investment account'"
            :loading="linking"
            :disabled="linking"
            @click="linkInvestmentAccount"
          />
        </div>
      </section>

      <template v-else>
        <div class="range-row">
          <div class="range-picker" role="radiogroup" aria-label="Time range">
            <button
              v-for="option in RANGES"
              :key="option"
              type="button"
              role="radio"
              class="range-option"
              :aria-checked="range === option"
              @click="selectRange(option)"
            >
              {{ option }}
            </button>
          </div>
          <Message v-if="historyError" severity="error" size="small">
            We couldn’t load that range.
          </Message>
        </div>

        <section
          ref="figure"
          class="panel net-worth"
          :class="{ refreshing: historyLoading }"
          aria-labelledby="net-worth-heading"
        >
          <div class="figure-heading">
            <div class="figure-title">
              <h2 id="net-worth-heading" class="figure-label">
                {{ shown.label }}
                <span v-if="shown.detail" class="figure-detail">· {{ shown.detail }}</span>
              </h2>
              <Button
                v-if="focused"
                label="Back to net worth"
                size="small"
                severity="secondary"
                text
                class="figure-back"
                @click="focusedId = undefined"
              />
            </div>
            <p v-if="shown.value != null" class="hero-figure">{{ formatMoney(shown.value) }}</p>
            <p v-if="shown.change" class="change">
              <component
                :is="changeIcon(shown.change)"
                :size="16"
                :stroke-width="1.75"
                :class="shown.change.amount < 0 ? 'down' : 'up'"
                aria-hidden="true"
              />
              {{ formatChange(shown.change) }}
              <span class="change-range">{{ range === 'All' ? 'all time' : range }}</span>
            </p>
          </div>

          <!-- One day of history still draws, as a flat line at that day's value. -->
          <BalanceChart
            v-if="shown.points.length"
            :points="shown.points"
            :label="shown.label"
            :color="shown.color"
            height="16rem"
            :added="focused ? undefined : history?.accounts_added"
            :dropped="focused ? undefined : history?.accounts_dropped"
          />
          <p v-else class="history-note">
            Your first balances are recorded the next time your accounts sync.
          </p>

          <template v-if="!focused">
            <ul
              v-if="accountChanges.length"
              class="account-changes"
              aria-label="Accounts added and removed"
            >
              <li v-for="change in accountChanges" :key="`${change.verb}-${change.account_id}`">
                {{ formatDay(change.date, true) }} · {{ change.name }} {{ change.verb }}
              </li>
            </ul>
            <p v-if="leftOutCount" class="footnote">
              Net worth leaves out {{ leftOutCount }}
              {{ leftOutCount === 1 ? 'account that isn’t' : 'accounts that aren’t' }} in US
              dollars.
            </p>
          </template>
        </section>

        <Message v-if="leaveOutError" severity="error">{{ leaveOutError }}</Message>

        <template v-for="group in cardGroups" :key="group.id">
          <section
            v-if="group.cards.length || group.id === 'investments'"
            class="accounts-section"
            :aria-labelledby="`accounts-heading-${group.id}`"
          >
            <h2 :id="`accounts-heading-${group.id}`" class="section-heading">
              {{ group.heading }}
            </h2>
            <div v-if="group.cards.length" class="account-grid">
              <article
                v-for="card in group.cards"
                :key="card.id"
                class="panel account-card"
                :class="{
                  refreshing: historyLoading,
                  'account-card-focused': focusedId === card.id,
                }"
                :aria-label="card.name"
                @click="focus(card.id)"
              >
                <button
                  type="button"
                  class="account-leave-out"
                  :disabled="savingId !== undefined"
                  :aria-label="`Leave ${card.name} out of net worth`"
                  title="Leave out of net worth"
                  @click.stop="countInNetWorth(card.id, false)"
                >
                  <X :size="14" :stroke-width="1.75" aria-hidden="true" />
                </button>
                <div class="account-card-heading">
                  <div>
                    <h3>
                      <!-- The whole card takes the click; the name is what a keyboard reaches. -->
                      <button
                        type="button"
                        class="account-focus"
                        :aria-pressed="focusedId === card.id"
                        :aria-label="`Show ${card.name} on the big chart`"
                        @click.stop="focus(card.id)"
                      >
                        {{ card.name }}
                      </button>
                    </h3>
                    <p class="account-kind">{{ card.kind }}</p>
                  </div>
                  <div class="account-figures">
                    <p v-if="card.value != null" class="account-value">
                      {{ formatMoney(card.value) }}
                    </p>
                    <p v-if="card.change" class="change small">
                      <component
                        :is="changeIcon(card.change)"
                        :size="14"
                        :stroke-width="1.75"
                        :class="card.change.amount < 0 ? 'down' : 'up'"
                        aria-hidden="true"
                      />
                      {{ formatChange(card.change) }}
                    </p>
                  </div>
                </div>
                <BalanceChart
                  v-if="card.points.length"
                  :points="card.points"
                  :label="card.name"
                  :color="card.color"
                  height="9rem"
                />
                <p v-else class="history-note">No balance recorded yet.</p>
              </article>
            </div>
            <div v-else class="panel prompt compact">
              <div class="prompt-content">
                <h3>No investment accounts yet</h3>
                <p>
                  Link a new one, or add investments from an institution you’ve already connected.
                </p>
              </div>
            </div>
          </section>
        </template>

        <section v-if="leftOut.length" class="left-out" aria-labelledby="left-out-heading">
          <h2 id="left-out-heading" class="section-heading">Left out of net worth</h2>
          <ul class="left-out-list">
            <li v-for="account in leftOut" :key="account.id" class="left-out-row">
              <span class="left-out-name">
                {{ account.name }}
                <span class="left-out-kind">· {{ account.kind }}</span>
              </span>
              <Button
                label="Add back"
                size="small"
                severity="secondary"
                outlined
                :disabled="savingId !== undefined"
                :aria-label="`Add ${account.name} back to net worth`"
                @click="countInNetWorth(account.id, true)"
              />
            </li>
          </ul>
        </section>

        <Message v-if="addNote && !itemsWithoutInvestments.length" severity="secondary">
          {{ addNote }}
        </Message>

        <section
          v-if="itemsWithoutInvestments.length"
          class="panel connections"
          aria-labelledby="connections-heading"
        >
          <h2 id="connections-heading" class="section-heading">Already connected</h2>
          <p class="connections-intro">
            Add investments to a connection you already have instead of linking it again. Linking
            the same institution twice counts its accounts twice.
          </p>
          <Message v-if="addError" severity="error" size="small">{{ addError }}</Message>
          <Message v-else-if="addNote" severity="secondary" size="small">{{ addNote }}</Message>
          <ul class="connection-list">
            <li v-for="item in itemsWithoutInvestments" :key="item.item_id" class="connection">
              <span class="connection-name">
                <Landmark :size="16" :stroke-width="1.75" aria-hidden="true" />
                {{ item.institution_name ?? 'Connected institution' }}
              </span>
              <Button
                label="Add investments"
                size="small"
                severity="secondary"
                :loading="addingItemId === item.item_id"
                :disabled="addingItemId != null"
                @click="addInvestmentsTo(item)"
              />
            </li>
          </ul>
        </section>
      </template>
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  align-items: stretch;
  height: 100svh;
}

.investments-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 1.25rem;
  min-width: 0;
  min-height: 0;
  padding: 1.25rem 1.5rem 1.5rem 0;
  overflow: auto;
}

.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.heading-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

h1 {
  font-size: 1.5rem;
  font-weight: 550;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.page-heading :deep(.cl-userButtonTrigger),
.page-heading :deep(.cl-avatarBox) {
  width: 2rem;
  height: 2rem;
}

.page-loading {
  display: grid;
  gap: 1.25rem;
}

.prompt {
  display: grid;
  min-height: 24rem;
  padding: 3rem 1.5rem;
  place-items: center;
}

.prompt.compact {
  min-height: 0;
  padding: 2rem 1.5rem;
}

.prompt-content {
  width: min(100%, 27rem);
  text-align: center;
}

.prompt-icon {
  display: grid;
  width: 3rem;
  height: 3rem;
  margin: 0 auto 1.25rem;
  place-items: center;
  color: var(--app-text-secondary);
  background: var(--app-inset);
  border-radius: var(--app-radius-control);
}

.prompt h2 {
  font-size: 1.5rem;
  font-weight: 550;
  line-height: 1.25;
  letter-spacing: -0.035em;
}

.prompt h3 {
  font-size: 1rem;
  font-weight: 550;
}

.prompt p {
  margin: 0.75rem 0 1.5rem;
  color: var(--app-text-secondary);
  line-height: 1.7;
}

.prompt.compact p {
  margin-bottom: 0;
}

.range-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
}

.range-picker {
  display: inline-flex;
  gap: 0.125rem;
  padding: 0.1875rem;
  background: var(--app-inset);
  border-radius: var(--app-radius-control);
}

.range-option {
  min-width: 2.75rem;
  padding: 0.375rem 0.625rem;
  color: var(--app-text-secondary);
  font: inherit;
  font-weight: 500;
  background: transparent;
  border: 0;
  border-radius: var(--app-radius-chip);
  cursor: pointer;
}

.range-option:hover {
  color: var(--app-text);
}

.range-option[aria-checked='true'] {
  color: var(--app-text);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-border);
}

.net-worth {
  display: grid;
  gap: 1.25rem;
  padding: 1.25rem 1.5rem 1.5rem;
}

.refreshing {
  opacity: 0.6;
  transition: opacity 150ms;
}

.figure-label,
.section-heading {
  color: var(--app-text-secondary);
  font-size: 0.875rem;
  font-weight: 500;
  letter-spacing: 0;
}

.figure-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  min-height: 1.75rem;
}

.figure-detail {
  color: var(--app-text-subdued);
  font-weight: 400;
}

.figure-back {
  flex: none;
  margin-right: -0.5rem;
}

.hero-figure {
  margin-top: 0.25rem;
  font-size: 3rem;
  font-weight: 550;
  line-height: 1.1;
  letter-spacing: -0.045em;
}

.change {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  margin-top: 0.5rem;
  font-variant-numeric: tabular-nums;
}

.change.small {
  justify-content: flex-end;
  margin-top: 0.125rem;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

.change .up {
  color: var(--app-success);
}

.change .down {
  color: var(--app-danger);
}

.change-range {
  color: var(--app-text-secondary);
}

.history-note {
  padding: 2.5rem 1rem;
  color: var(--app-text-secondary);
  text-align: center;
  background: var(--app-canvas);
  border-radius: var(--app-radius-control);
}

.account-changes {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 1.25rem;
  padding: 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  list-style: none;
}

.footnote {
  color: var(--app-text-subdued);
  font-size: 0.8125rem;
}

.accounts-section {
  display: grid;
  gap: 0.75rem;
}

.account-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 20rem), 1fr));
  gap: 1.25rem;
}

.account-card {
  display: grid;
  gap: 1rem;
  padding: 1.125rem 1.25rem 1.25rem;
}

/* A card puts its account on the big chart, so it's a control: it lifts on hover and keeps a ring while in focus. */
.account-card {
  cursor: pointer;
  transition: box-shadow 150ms ease;
}

.account-card:hover {
  box-shadow: var(--app-shadow-border-sm);
}

/* Drawn inside the card's edge: the page scrolls, so anything outside the first column is cut off by the sidebar. */
.account-card-focused,
.account-card-focused:hover {
  box-shadow:
    var(--app-shadow-sm),
    inset 0 0 0 1.5px var(--app-text);
}

/* Tucked into the corner and shown when the card is pointed at, so the grid stays quiet. */
.account-card {
  position: relative;
}

.account-leave-out {
  position: absolute;
  top: 0.3rem;
  right: 0.3rem;
  display: grid;
  width: 1.4rem;
  height: 1.4rem;
  place-items: center;
  color: var(--app-text-subdued);
  background: transparent;
  border: 0;
  border-radius: 50%;
  opacity: 0;
  transition: opacity 120ms ease;
  cursor: pointer;
}

.account-card:hover .account-leave-out,
.account-leave-out:focus-visible {
  opacity: 1;
}

.account-leave-out:hover {
  color: var(--app-text);
  background: var(--app-inset);
}

/* Nothing to point with on a touch screen, so it's always there. */
@media (hover: none) {
  .account-leave-out {
    opacity: 1;
  }
}

.left-out {
  display: grid;
  gap: 0.5rem;
}

.left-out-list {
  display: grid;
  padding: 0;
  list-style: none;
}

.left-out-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  max-width: 34rem;
  padding: 0.5rem 0;
  border-top: 1px solid var(--app-divider);
}

.left-out-name {
  min-width: 0;
  font-weight: 500;
  overflow-wrap: anywhere;
}

.left-out-kind {
  color: var(--app-text-secondary);
  font-weight: 400;
}

.account-focus {
  padding: 0;
  color: inherit;
  background: none;
  border: 0;
  font: inherit;
  text-align: left;
  overflow-wrap: anywhere;
  cursor: pointer;
}

.account-focus:focus-visible {
  outline: 2px solid var(--app-text);
  outline-offset: 2px;
  border-radius: var(--app-radius-chip);
}

.account-card .history-note {
  padding: 2rem 1rem;
}

.account-card-heading {
  /* Clear of the corner button. */
  padding-right: 0.75rem;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.account-card h3 {
  font-size: 0.9375rem;
  font-weight: 550;
  overflow-wrap: anywhere;
}

.account-kind {
  margin-top: 0.125rem;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

.account-figures {
  text-align: right;
}

.account-value {
  font-size: 1.125rem;
  font-weight: 550;
  letter-spacing: -0.02em;
  white-space: nowrap;
}

.connections {
  display: grid;
  gap: 0.75rem;
  padding: 1.125rem 1.25rem;
}

.connections-intro {
  color: var(--app-text-secondary);
  line-height: 1.6;
}

.connection-list {
  display: grid;
  padding: 0;
  list-style: none;
}

.connection {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.625rem 0;
  border-top: 1px solid var(--app-divider);
}

.connection-name {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-weight: 500;
}

@media (max-width: 900px) {
  .investments-page {
    padding: 1.25rem 1.25rem calc(var(--app-tabbar-height) + env(safe-area-inset-bottom) + 1.5rem);
  }

  h1 {
    font-size: 1.375rem;
  }
}

@media (max-width: 640px) {
  .page-heading {
    flex-wrap: wrap;
  }

  .net-worth {
    padding: 1.125rem 1rem 1.25rem;
  }

  .hero-figure {
    font-size: 2.25rem;
  }

  .prompt {
    min-height: 20rem;
    padding: 2rem 1.25rem;
  }
}
</style>
