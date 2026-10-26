<script setup lang="ts">
import { computed } from 'vue'
import Message from 'primevue/message'
import type { PlaidAccount } from '../api/PlaidService'
import { accountLabel } from '../accounts/useSelectedAccount'
import { kindLabel, useAccountTracking } from '../accounts/useAccountTracking'
import { formatMoney } from '../money'

/** The user's accounts, grouped by kind, with what counts toward spending and net worth. */
const accounts = defineModel<PlaidAccount[]>('accounts', { required: true })

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

const { saving, failedAccounts, onSwitch, isShared, onSharedChange, onShareChange } =
  useAccountTracking(accounts)

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

/** Cards and loans show what's owed, which Plaid reports as a positive balance. */
function isOwed(account: PlaidAccount) {
  return account.type === 'credit' || account.type === 'loan'
}
</script>

<template>
  <div class="account-settings">
    <Message
      v-for="failed in failedAccounts"
      :key="failed.account_id"
      severity="error"
      class="save-error"
    >
      We couldn’t save the change to {{ accountLabel(failed) }}. Try again.
    </Message>

    <section
      v-for="group in groups"
      :key="group.id"
      class="panel account-group"
      :aria-labelledby="`account-group-${group.id}`"
    >
      <table class="account-table">
        <caption :id="`account-group-${group.id}`" class="card-title">
          {{
            group.title
          }}
        </caption>
        <thead>
          <tr>
            <th scope="col" class="column-account">Account</th>
            <th scope="col" class="column-balance">Balance</th>
            <th scope="col" class="column-setting">Spending</th>
            <th scope="col" class="column-setting">Net worth</th>
            <th scope="col" class="column-shared">Shared</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="account in group.accounts"
            :key="account.account_id"
            :aria-busy="saving.has(account.account_id)"
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
              <span
                v-else
                class="not-applicable"
                title="Spending isn’t tracked from this kind of account"
              >
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
            <td class="column-shared" data-label="Shared">
              <span class="shared-cell">
                <input
                  type="checkbox"
                  role="switch"
                  class="switch"
                  :checked="isShared(account)"
                  :aria-label="`${accountLabel(account)} is shared`"
                  @change="onSharedChange(account, $event)"
                />
                <label v-if="isShared(account)" class="share-field">
                  <input
                    type="number"
                    min="1"
                    max="99"
                    step="1"
                    inputmode="numeric"
                    :value="account.share_percent"
                    :aria-label="`Your share of ${accountLabel(account)}, in percent`"
                    @change="onShareChange(account, $event)"
                  />
                  <span aria-hidden="true">%</span>
                </label>
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.account-settings {
  display: grid;
  gap: 1.25rem;
  /* Narrow places, like onboarding's card, get the stacked layout below as well as small screens. */
  container-type: inline-size;
}

.save-error {
  margin: 0;
}

.account-group {
  padding: 1rem 1.25rem 0.25rem;
}

.account-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.card-title {
  padding-bottom: 0.5rem;
  text-align: left;
}

.account-table thead th {
  padding: 0 0 0.5rem;
  color: var(--app-text-secondary);
  font-size: var(--text-xs);
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

/* Wide enough for the switch and the share beside it, so switches line up either way. */
.column-shared {
  width: 9.5rem;
}

.account-table th.column-shared,
.account-table td.column-shared {
  padding-left: 1.25rem;
  text-align: left;
}

.shared-cell {
  display: inline-flex;
  align-items: center;
  gap: 0.625rem;
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
  font-size: var(--text-sm);
}

.column-balance {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.owed {
  display: block;
  color: var(--app-text-secondary);
  font-size: var(--text-xs);
  font-weight: 400;
}

.not-applicable {
  color: var(--app-text-secondary);
}

/* In narrow places each account becomes a small card with its settings labelled. */
@container (max-width: 40rem) {
  .account-table,
  .account-table caption,
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
    /* A switch per row, so rows are as tall as a finger-sized target and theirs never overlap. */
    min-height: var(--app-touch-target);
  }

  /*
   * The share field sits left of the switch, so every switch lines up at the right edge. The gap
   * clears the switch's enlarged target so it doesn't cover the field.
   */
  .shared-cell {
    flex-direction: row-reverse;
    gap: 1rem;
  }

  .account-table td.column-balance,
  .account-table td.column-setting,
  .account-table td.column-shared {
    padding-right: 0;
    padding-left: 0;
    text-align: right;
  }

  .account-table td::before {
    color: var(--app-text-secondary);
    font-size: var(--text-base);
    content: attr(data-label);
  }

  .owed {
    display: inline;
    margin-left: 0.25rem;
  }
}
</style>
