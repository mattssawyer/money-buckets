<script setup lang="ts">
import { computed } from 'vue'
import Message from 'primevue/message'
import type { PlaidAccount } from '../api/PlaidService'
import { accountLabel } from '../accounts/useSelectedAccount'
import { kindLabel, useAccountTracking } from '../accounts/useAccountTracking'

/**
 * A short list of the accounts spending can come from, each with whether it counts and whether
 * it's shared. The Accounts page has the full set of choices for every account.
 */
const accounts = defineModel<PlaidAccount[]>('accounts', { required: true })

const { saving, failedAccounts, onSwitch, isShared, onSharedChange, onShareChange } =
  useAccountTracking(accounts)

const spendable = computed(() => accounts.value.filter((account) => account.trackable))
</script>

<template>
  <div class="account-choices">
    <Message
      v-for="failed in failedAccounts"
      :key="failed.account_id"
      severity="error"
      size="small"
    >
      We couldn’t save the change to {{ accountLabel(failed) }}. Try again.
    </Message>

    <div class="choice-heading" aria-hidden="true">
      <span />
      <span>Spending</span>
      <span>Shared</span>
    </div>
    <ul class="choices" aria-label="Accounts">
      <li
        v-for="account in spendable"
        :key="account.account_id"
        class="choice"
        :aria-busy="saving.has(account.account_id)"
      >
        <div class="choice-account">
          <span class="choice-name">{{ accountLabel(account) }}</span>
          <span class="choice-kind">{{ kindLabel(account) }}</span>
          <span v-if="isShared(account)" class="choice-share">
            Your share
            <span class="share-field">
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
            </span>
          </span>
        </div>
        <input
          type="checkbox"
          role="switch"
          class="switch"
          :checked="account.tracks_spending"
          :aria-label="`Track spending from ${accountLabel(account)}`"
          @change="onSwitch(account, 'tracks_spending', $event)"
        />
        <input
          type="checkbox"
          role="switch"
          class="switch"
          :checked="isShared(account)"
          :aria-label="`${accountLabel(account)} is shared`"
          @change="onSharedChange(account, $event)"
        />
      </li>
    </ul>
  </div>
</template>

<style scoped>
.account-choices {
  display: grid;
  gap: 0.5rem;
}

.choice-heading,
.choice {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 4.25rem 4.25rem;
  align-items: center;
  justify-items: center;
}

.choice-heading {
  color: var(--app-text-subdued);
  font-size: var(--text-xs);
  font-weight: 500;
}

.choices {
  display: grid;
  padding: 0;
  margin: 0;
  list-style: none;
}

.choice {
  padding: 0.625rem 0;
  border-top: 1px solid var(--app-divider);
}

.choice-account {
  display: grid;
  justify-self: stretch;
  gap: 0.125rem;
  min-width: 0;
}

.choice-name,
.choice-kind {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.choice-name {
  font-weight: 500;
}

.choice-kind {
  color: var(--app-text-secondary);
  font-size: var(--text-sm);
}

.choice-share {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.25rem;
  color: var(--app-text-secondary);
  font-size: var(--text-sm);
}
</style>
