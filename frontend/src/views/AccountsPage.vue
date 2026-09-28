<script setup lang="ts">
import { UserButton } from '@clerk/vue'
import { Landmark } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import AppSidebar from '../components/AppSidebar.vue'
import {
  getAccounts,
  updateAccountTracking,
  type AccountTracking,
  type PlaidAccount,
} from '../api/PlaidService'
import { accountLabel } from '../accounts/useSelectedAccount'
import { formatMoney } from '../investments/history'

interface AccountGroup {
  id: string
  title: string
  accounts: PlaidAccount[]
}

const GROUPS: Array<{ id: string; title: string; type: string }> = [
  { id: 'bank', title: 'Bank accounts', type: 'depository' },
  { id: 'credit', title: 'Credit cards', type: 'credit' },
  { id: 'investment', title: 'Investments', type: 'investment' },
  { id: 'loan', title: 'Loans', type: 'loan' },
]

/** Plaid subtypes that don't read well with just a capital letter. */
const KIND_LABELS: Record<string, string> = {
  cd: 'CD',
  hsa: 'HSA',
  ira: 'IRA',
  roth: 'Roth IRA',
  '401k': '401(k)',
  '403b': '403(b)',
  '457b': '457(b)',
  '529': '529 plan',
  'roth 401k': 'Roth 401(k)',
}

const accounts = ref<PlaidAccount[]>([])
const loading = ref(true)
const loadError = ref(false)
const saving = ref(new Set<string>())
/** The account whose last change couldn't be saved. */
const failedAccountId = ref<string>()

const groups = computed<AccountGroup[]>(() => {
  const listed = GROUPS.map((group) => ({
    id: group.id,
    title: group.title,
    accounts: accounts.value.filter((account) => account.type === group.type),
  }))
  const known = new Set(GROUPS.map((group) => group.type))
  listed.push({
    id: 'other',
    title: 'Other accounts',
    accounts: accounts.value.filter((account) => !known.has(account.type)),
  })
  return listed.filter((group) => group.accounts.length)
})
const failedAccount = computed(() =>
  accounts.value.find((account) => account.account_id === failedAccountId.value),
)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = false
  try {
    accounts.value = await getAccounts()
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function kindLabel(account: PlaidAccount) {
  const kind = account.subtype ?? account.type
  return KIND_LABELS[kind] ?? kind.charAt(0).toUpperCase() + kind.slice(1)
}

/** Cards and loans show what's owed, which Plaid reports as a positive balance. */
function isOwed(account: PlaidAccount) {
  return account.type === 'credit' || account.type === 'loan'
}

function tracking(account: PlaidAccount): AccountTracking {
  return {
    tracks_spending: account.tracks_spending,
    counts_in_net_worth: account.counts_in_net_worth,
    share_percent: account.share_percent,
  }
}

/** Saves right away, showing the change first and putting it back if the save fails. */
async function save(account: PlaidAccount, changes: Partial<AccountTracking>) {
  const accountId = account.account_id
  const before = tracking(account)
  const after = { ...before, ...changes }
  replace(accountId, after)
  saving.value.add(accountId)
  failedAccountId.value = undefined
  try {
    replace(accountId, await updateAccountTracking(accountId, after))
  } catch {
    replace(accountId, before)
    failedAccountId.value = accountId
  } finally {
    saving.value.delete(accountId)
  }
}

function replace(accountId: string, changes: Partial<PlaidAccount>) {
  accounts.value = accounts.value.map((account) =>
    account.account_id === accountId ? { ...account, ...changes } : account,
  )
}

function onSwitch(account: PlaidAccount, key: 'tracks_spending' | 'counts_in_net_worth', event: Event) {
  if (!(event.target instanceof HTMLInputElement)) return
  void save(account, { [key]: event.target.checked })
}

function onShareChange(account: PlaidAccount, event: Event) {
  const input = event.target
  if (!(input instanceof HTMLInputElement)) return
  const share = Number(input.value)
  if (!Number.isInteger(share) || share < 1 || share > 100) {
    input.value = String(account.share_percent)
    return
  }
  if (share !== account.share_percent) void save(account, { share_percent: share })
}
</script>

<template>
  <div class="app-shell">
    <AppSidebar />
    <main class="accounts-page" aria-labelledby="accounts-heading">
      <header class="page-heading">
        <h1 id="accounts-heading">Accounts</h1>
        <UserButton />
      </header>

      <div class="accounts-content">
        <div v-if="loading" class="page-loading" aria-label="Loading your accounts">
          <Skeleton height="14rem" />
          <Skeleton height="8rem" />
        </div>

        <section v-else-if="loadError" class="panel prompt" aria-labelledby="load-error-heading">
          <div class="prompt-content">
            <h2 id="load-error-heading">We couldn’t load your accounts.</h2>
            <p>Check your connection and try again.</p>
            <Button label="Try again" severity="secondary" @click="load" />
          </div>
        </section>

        <section v-else-if="!accounts.length" class="panel prompt" aria-labelledby="empty-heading">
          <div class="prompt-content">
            <div class="prompt-icon" aria-hidden="true">
              <Landmark :size="24" :stroke-width="1.5" />
            </div>
            <h2 id="empty-heading">No accounts yet</h2>
            <p>Connect your bank on Home, and its accounts will show up here.</p>
            <RouterLink to="/" class="prompt-link">Go to Home</RouterLink>
          </div>
        </section>

        <template v-else>
          <p class="page-intro">
            Choose which accounts count toward your spending and your net worth. For an account
            you split with someone, set your share, and only your part counts.
          </p>

          <Message v-if="failedAccount" severity="error" class="save-error">
            We couldn’t save the change to {{ accountLabel(failedAccount) }}. Try again.
          </Message>

          <section
            v-for="group in groups"
            :key="group.id"
            class="panel account-group"
            :aria-labelledby="`account-group-${group.id}`"
          >
            <table class="account-table">
              <caption :id="`account-group-${group.id}`" class="card-label">
                {{ group.title }}
              </caption>
              <thead>
                <tr>
                  <th scope="col" class="column-account">Account</th>
                  <th scope="col" class="column-balance">Balance</th>
                  <th scope="col" class="column-setting">Spending</th>
                  <th scope="col" class="column-setting">Net worth</th>
                  <th scope="col" class="column-setting">Your share</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="account in group.accounts"
                  :key="account.account_id"
                  :aria-busy="saving.has(account.account_id)"
                  :class="{ saving: saving.has(account.account_id) }"
                >
                  <th scope="row" class="column-account">
                    <span class="account-name">{{ accountLabel(account) }}</span>
                    <span class="account-kind">{{ kindLabel(account) }}</span>
                  </th>
                  <td class="column-balance" data-label="Balance">
                    <template v-if="account.balances.current == null">—</template>
                    <template v-else>
                      {{ formatMoney(account.balances.current) }}
                      <span v-if="isOwed(account)" class="owed">owed</span>
                    </template>
                  </td>
                  <td class="column-setting" data-label="Spending">
                    <input
                      v-if="account.trackable"
                      type="checkbox"
                      role="switch"
                      class="switch"
                      :checked="account.tracks_spending"
                      :aria-label="`Track spending from ${accountLabel(account)}`"
                      @change="onSwitch(account, 'tracks_spending', $event)"
                    />
                    <span v-else class="not-applicable" title="Spending isn’t tracked from this kind of account">
                      —
                    </span>
                  </td>
                  <td class="column-setting" data-label="Net worth">
                    <input
                      type="checkbox"
                      role="switch"
                      class="switch"
                      :checked="account.counts_in_net_worth"
                      :aria-label="`Count ${accountLabel(account)} in net worth`"
                      @change="onSwitch(account, 'counts_in_net_worth', $event)"
                    />
                  </td>
                  <td class="column-setting" data-label="Your share">
                    <span class="share-field">
                      <input
                        type="number"
                        min="1"
                        max="100"
                        step="1"
                        inputmode="numeric"
                        :value="account.share_percent"
                        :aria-label="`Your share of ${accountLabel(account)}, in percent`"
                        @change="onShareChange(account, $event)"
                      />
                      <span aria-hidden="true">%</span>
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </section>
        </template>
      </div>
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  align-items: stretch;
  height: 100svh;
}

.accounts-page {
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

/* Wide screens would spread the settings far from the account they belong to. */
.accounts-content {
  display: grid;
  gap: 1.25rem;
  width: 100%;
  max-width: 60rem;
}

.page-intro {
  max-width: 40rem;
  margin: 0;
  color: var(--app-text-secondary);
  line-height: 1.6;
}

.page-loading {
  display: grid;
  gap: 1.25rem;
}

.save-error {
  margin: 0;
}

.prompt {
  display: grid;
  min-height: 20rem;
  padding: 3rem 1.5rem;
  place-items: center;
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

.prompt p {
  margin: 0.75rem 0 1.5rem;
  color: var(--app-text-secondary);
  line-height: 1.7;
}

.prompt-link {
  color: var(--app-text);
  font-weight: 500;
}

.account-group {
  padding: 1rem 1.25rem 0.25rem;
}

.account-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.card-label {
  padding-bottom: 0.5rem;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  font-weight: 500;
  letter-spacing: -0.005em;
  text-align: left;
}

.account-table thead th {
  padding: 0 0 0.5rem;
  color: var(--app-text-subdued);
  font-size: 0.75rem;
  font-weight: 500;
  text-align: left;
  border-bottom: 1px solid var(--app-divider);
}

.column-balance {
  width: 9.5rem;
}

.column-setting {
  width: 6.5rem;
}

.account-table thead .column-balance,
.account-table td.column-balance {
  padding-right: 1.5rem;
  text-align: right;
}

.account-table thead .column-setting,
.account-table td.column-setting {
  text-align: center;
}

.account-table tbody th,
.account-table td {
  padding: 0.75rem 0;
  vertical-align: middle;
  border-bottom: 1px solid var(--app-divider);
}

.account-table tbody tr:last-child th,
.account-table tbody tr:last-child td {
  border-bottom: 0;
}

.account-table tbody th {
  font-weight: inherit;
  text-align: left;
}

.account-table tbody tr.saving {
  opacity: 0.6;
}

.account-name,
.account-kind {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-name {
  font-weight: 500;
}

.account-kind {
  margin-top: 0.125rem;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

.column-balance {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.owed {
  display: block;
  color: var(--app-text-secondary);
  font-size: 0.75rem;
  font-weight: 400;
}

.not-applicable {
  color: var(--app-text-subdued);
}

/* A native checkbox drawn as a switch, so it stays keyboard and screen reader friendly. */
.switch {
  position: relative;
  width: 2.25rem;
  height: 1.25rem;
  margin: 0;
  vertical-align: middle;
  appearance: none;
  background: var(--app-inset-hover);
  border-radius: 999px;
  cursor: pointer;
  transition: background-color 150ms ease;
}

.switch::after {
  position: absolute;
  top: 0.125rem;
  left: 0.125rem;
  width: 1rem;
  height: 1rem;
  content: '';
  background: var(--app-surface);
  border-radius: 50%;
  box-shadow: var(--app-shadow-xs);
  transition: transform 150ms ease;
}

.switch:checked {
  background: var(--app-text);
}

.switch:checked::after {
  transform: translateX(1rem);
}

.switch:focus-visible,
.share-field input:focus-visible {
  outline: 2px solid var(--app-text);
  outline-offset: 2px;
}

.share-field {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.875rem;
}

.share-field input {
  width: 3.25rem;
  padding: 0.25rem 0.375rem;
  color: var(--app-text);
  font: inherit;
  font-variant-numeric: tabular-nums;
  text-align: right;
  background: var(--app-surface);
  border: 1px solid var(--app-control-border);
  border-radius: var(--app-radius-chip);
}

@media (prefers-reduced-motion: reduce) {
  .switch,
  .switch::after {
    transition: none;
  }
}

@media (max-width: 900px) {
  .accounts-page {
    padding: 1.25rem 1.25rem calc(var(--app-tabbar-height) + env(safe-area-inset-bottom) + 1.5rem);
  }

  h1 {
    font-size: 1.375rem;
  }
}

/* On narrow screens each account becomes a small card with its settings labelled. */
@media (max-width: 40rem) {
  .account-table,
  .account-table tbody,
  .account-table tr,
  .account-table th,
  .account-table td {
    display: block;
    width: auto;
  }

  .account-table thead {
    display: none;
  }

  .account-table tbody tr {
    padding: 0.75rem 0;
    border-bottom: 1px solid var(--app-divider);
  }

  .account-table tbody tr:last-child {
    border-bottom: 0;
  }

  .account-table tbody th,
  .account-table td {
    padding: 0;
    border-bottom: 0;
  }

  .account-table tbody th {
    margin-bottom: 0.5rem;
  }

  .account-table td {
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 2.25rem;
  }

  .account-table td.column-balance,
  .account-table td.column-setting {
    padding-right: 0;
    text-align: right;
  }

  .account-table td::before {
    color: var(--app-text-secondary);
    font-size: 0.875rem;
    content: attr(data-label);
  }

  .owed {
    display: inline;
    margin-left: 0.25rem;
  }
}
</style>
