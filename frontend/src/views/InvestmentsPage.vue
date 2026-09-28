<script setup lang="ts">
import { UserButton } from '@clerk/vue'
import { Landmark, Plus, TrendingDown, TrendingUp } from '@lucide/vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
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
  type PlaidAccount,
  type PlaidItem,
} from '../api/PlaidService'
import { getBalanceHistory, type BalanceHistory } from '../api/InvestmentsService'
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
const INVESTMENT_COLOR = '#eb6834'

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
let disposed = false

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

const investmentCards = computed(() =>
  (history.value?.accounts ?? []).flatMap((series) => {
    const account = accounts.value.find((candidate) => candidate.account_id === series.account_id)
    if (!account) return []
    return [
      {
        id: series.account_id,
        name: accountLabel(account),
        kind: account.subtype ? (SUBTYPE_LABELS[account.subtype] ?? account.subtype) : 'Investment',
        points: series.points,
        value: latestValue(series.points),
        change: changeOver(series.points),
      },
    ]
  }),
)

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

/** Loads the chosen range and ignores results or errors from requests for earlier selections. */
async function selectRange(next: Range) {
  if (next === range.value) return
  range.value = next
  historyLoading.value = true
  historyError.value = false
  try {
    const balances = await getBalanceHistory(rangeStart(next, new Date()))
    if (!disposed && range.value === next) history.value = balances
  } catch {
    if (!disposed && range.value === next) historyError.value = true
  } finally {
    // A newer range still loading owns these flags.
    if (!disposed && range.value === next) historyLoading.value = false
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
          class="panel net-worth"
          :class="{ refreshing: historyLoading }"
          aria-labelledby="net-worth-heading"
        >
          <div class="figure-heading">
            <h2 id="net-worth-heading" class="figure-label">Net worth</h2>
            <p v-if="netWorthValue != null" class="hero-figure">{{ formatMoney(netWorthValue) }}</p>
            <p v-if="netWorthChange" class="change">
              <component
                :is="changeIcon(netWorthChange)"
                :size="16"
                :stroke-width="1.75"
                :class="netWorthChange.amount < 0 ? 'down' : 'up'"
                aria-hidden="true"
              />
              {{ formatChange(netWorthChange) }}
              <span class="change-range">{{ range === 'All' ? 'all time' : range }}</span>
            </p>
          </div>

          <BalanceChart
            v-if="netWorth.length >= 2"
            :points="netWorth"
            label="Net worth"
            :color="NET_WORTH_COLOR"
            height="16rem"
            :added="history?.accounts_added"
            :dropped="history?.accounts_dropped"
          />
          <p v-else-if="netWorth.length === 1" class="history-note">
            History starts today. Money Buckets records your balances each day from here on.
          </p>
          <p v-else class="history-note">
            Your first balances are recorded the next time your accounts sync.
          </p>

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
            {{ leftOutCount === 1 ? 'account that isn’t' : 'accounts that aren’t' }} in US dollars.
          </p>
        </section>

        <section class="accounts-section" aria-labelledby="accounts-heading">
          <h2 id="accounts-heading" class="section-heading">Investment accounts</h2>
          <div v-if="investmentCards.length" class="account-grid">
            <article
              v-for="card in investmentCards"
              :key="card.id"
              class="panel account-card"
              :class="{ refreshing: historyLoading }"
              :aria-label="card.name"
            >
              <div class="account-card-heading">
                <div>
                  <h3>{{ card.name }}</h3>
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
                v-if="card.points.length >= 2"
                :points="card.points"
                :label="card.name"
                :color="INVESTMENT_COLOR"
                height="9rem"
              />
              <p v-else class="history-note">History starts today.</p>
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

.account-card .history-note {
  padding: 2rem 1rem;
}

.account-card-heading {
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
