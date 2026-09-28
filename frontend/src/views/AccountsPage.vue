<script setup lang="ts">
import { UserButton } from '@clerk/vue'
import { Landmark } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import AppSidebar from '../components/AppSidebar.vue'
import { getAccounts, updateAccountTracking, type PlaidAccount } from '../api/PlaidService'
import { accountLabel } from '../accounts/useSelectedAccount'
import { formatMoney } from '../investments/history'

/** Marking an account as shared starts at an even split. */
const DEFAULT_SHARED_PERCENT = 50

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

function isShared(account: PlaidAccount) {
  return account.share_percent < 100
}

function kindLabel(account: PlaidAccount) {
  const kind = account.subtype ?? account.type
  return kind.charAt(0).toUpperCase() + kind.slice(1)
}

// Plaid reports what's owed on a card or loan as a positive balance.
function balanceText(account: PlaidAccount) {
  const current = account.balances.current
  if (current == null) return 'Balance unavailable'
  const owed = account.type === 'credit' || account.type === 'loan'
  return owed ? `${formatMoney(current)} owed` : formatMoney(current)
}

/** Saves right away, showing the change first and putting it back if the save fails. */
async function save(account: PlaidAccount, tracksSpending: boolean, sharePercent: number) {
  const accountId = account.account_id
  const before = { tracks_spending: account.tracks_spending, share_percent: account.share_percent }
  replace(accountId, { tracks_spending: tracksSpending, share_percent: sharePercent })
  saving.value.add(accountId)
  failedAccountId.value = undefined
  try {
    const updated = await updateAccountTracking(accountId, tracksSpending, sharePercent)
    replace(accountId, updated)
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

function onTrackChange(account: PlaidAccount, event: Event) {
  if (!(event.target instanceof HTMLInputElement)) return
  // The share is kept, so tracking the account again brings it back.
  void save(account, event.target.checked, account.share_percent)
}

function onSharedChange(account: PlaidAccount, event: Event) {
  if (!(event.target instanceof HTMLInputElement)) return
  void save(account, true, event.target.checked ? DEFAULT_SHARED_PERCENT : 100)
}

function onShareChange(account: PlaidAccount, event: Event) {
  const input = event.target
  if (!(input instanceof HTMLInputElement)) return
  const share = Number(input.value)
  if (!Number.isInteger(share) || share < 1 || share > 99) {
    input.value = String(account.share_percent)
    return
  }
  if (share !== account.share_percent) void save(account, true, share)
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

      <div v-if="loading" class="page-loading" aria-label="Loading your accounts">
        <Skeleton height="12rem" />
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
          Spending on Home and in your spending plan comes from the accounts you track. Mark an
          account you split with someone as shared, and only your part of it counts.
        </p>

        <section
          v-for="group in groups"
          :key="group.id"
          class="panel account-group"
          :aria-labelledby="`account-group-${group.id}`"
        >
          <h2 :id="`account-group-${group.id}`" class="card-label">{{ group.title }}</h2>
          <ul class="account-list">
            <li v-for="account in group.accounts" :key="account.account_id" class="account-row">
              <div class="account-summary">
                <span class="account-details">
                  <span class="account-name">{{ accountLabel(account) }}</span>
                  <span class="account-kind">{{ kindLabel(account) }}</span>
                </span>
                <span class="account-balance">{{ balanceText(account) }}</span>
              </div>

              <div
                v-if="account.trackable"
                class="account-controls"
                :aria-busy="saving.has(account.account_id)"
              >
                <label class="switch">
                  <input
                    type="checkbox"
                    role="switch"
                    :checked="account.tracks_spending"
                    @change="onTrackChange(account, $event)"
                  />
                  <span>Track spending</span>
                </label>
                <label v-if="account.tracks_spending" class="switch">
                  <input
                    type="checkbox"
                    role="switch"
                    :checked="isShared(account)"
                    @change="onSharedChange(account, $event)"
                  />
                  <span>Shared</span>
                </label>
                <label v-if="account.tracks_spending && isShared(account)" class="share-field">
                  <span>Your share</span>
                  <input
                    type="number"
                    min="1"
                    max="99"
                    step="1"
                    inputmode="numeric"
                    :value="account.share_percent"
                    @change="onShareChange(account, $event)"
                  />
                  <span aria-hidden="true">%</span>
                </label>
              </div>

              <Message
                v-if="failedAccountId === account.account_id"
                severity="error"
                size="small"
                class="save-error"
              >
                We couldn’t save that change. Try again.
              </Message>
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

.page-intro {
  max-width: 44rem;
  margin: 0;
  color: var(--app-text-secondary);
  line-height: 1.6;
}

.page-loading {
  display: grid;
  gap: 1.25rem;
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
  padding: 1.25rem 1.5rem 0.5rem;
}

.card-label {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  font-weight: 500;
  letter-spacing: -0.005em;
}

.account-list {
  margin: 0.5rem 0 0;
  padding: 0;
  list-style: none;
}

.account-row {
  display: grid;
  gap: 0.75rem;
  padding: 1rem 0;
}

.account-row + .account-row {
  border-top: 1px solid var(--app-divider);
}

.account-summary {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
}

.account-details {
  display: grid;
  gap: 0.125rem;
  min-width: 0;
}

.account-name {
  overflow: hidden;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-kind {
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

.account-balance {
  flex: none;
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.account-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem 1.5rem;
}

.account-controls[aria-busy='true'] {
  opacity: 0.6;
}

.switch {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  cursor: pointer;
}

/* A native checkbox drawn as a switch, so it stays keyboard and screen reader friendly. */
.switch input {
  position: relative;
  flex: none;
  width: 2.25rem;
  height: 1.25rem;
  margin: 0;
  appearance: none;
  background: var(--app-inset-hover);
  border-radius: 999px;
  cursor: pointer;
  transition: background-color 150ms ease;
}

.switch input::after {
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

.switch input:checked {
  background: var(--app-text);
}

.switch input:checked::after {
  transform: translateX(1rem);
}

.switch input:focus-visible {
  outline: 2px solid var(--app-text);
  outline-offset: 2px;
}

.share-field {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.875rem;
}

.share-field input {
  width: 3.5rem;
  padding: 0.25rem 0.5rem;
  color: var(--app-text);
  font: inherit;
  font-variant-numeric: tabular-nums;
  background: var(--app-surface);
  border: 1px solid var(--app-control-border);
  border-radius: var(--app-radius-chip);
}

.save-error {
  justify-self: start;
}

@media (prefers-reduced-motion: reduce) {
  .switch input,
  .switch input::after {
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

  .account-group {
    padding: 1rem 1.25rem 0.25rem;
  }
}
</style>
