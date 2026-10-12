<script setup lang="ts">
import { Landmark, Plus } from '@lucide/vue'
import { onMounted, onUnmounted, ref } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import AppSidebar from '../components/AppSidebar.vue'
import UserMenu from '../components/UserMenu.vue'
import InfoTip from '../components/InfoTip.vue'
import AccountSettings from '../components/AccountSettings.vue'
import ReconnectNotice from '../components/ReconnectNotice.vue'
import SameInstitutionNotice from '../components/SameInstitutionNotice.vue'
import { getAccounts, type PlaidAccount, type PlaidItem } from '../api/PlaidService'
import { closePlaidLink, linkItem } from '../plaid/plaidLink'

const accounts = ref<PlaidAccount[]>([])
const loading = ref(true)
const loadError = ref(false)
const linking = ref(false)
const linkError = ref('')
// Older connections to the bank just linked, which the user may want to remove.
const sameInstitution = ref<PlaidItem[]>([])
let disposed = false

onMounted(load)

onUnmounted(() => {
  disposed = true
  closePlaidLink()
})

async function load() {
  loading.value = true
  loadError.value = false
  try {
    accounts.value = await getAccounts()
  } catch {
    if (!disposed) loadError.value = true
  } finally {
    if (!disposed) loading.value = false
  }
}

/** Connects a bank through Plaid Link, then lists its accounts. */
async function linkAccount() {
  if (linking.value) return
  linking.value = true
  linkError.value = ''
  try {
    const result = await linkItem()
    if (disposed || !result) return
    sameInstitution.value = result.same_institution
    await load()
  } catch {
    if (!disposed) linkError.value = 'We couldn’t connect that account. Please try again.'
  } finally {
    if (!disposed) linking.value = false
  }
}

function onOlderRemoved() {
  sameInstitution.value = []
  void load()
}
</script>

<template>
  <div class="app-shell">
    <AppSidebar />
    <main class="accounts-page" aria-labelledby="accounts-heading">
      <header class="page-heading">
        <div class="title-row">
          <div class="page-title">
            <h1 id="accounts-heading">Accounts</h1>
            <InfoTip
              label="About accounts"
              text="Choose which accounts count toward your spending and your net worth. For an account you split with someone, set your share, and only your part counts."
            />
          </div>
          <Button
            v-if="accounts.length"
            :label="linking ? 'Connecting…' : 'Add an account'"
            :aria-label="linking ? 'Connecting…' : 'Add an account'"
            :loading="linking"
            :disabled="linking"
            class="add-account-button"
            @click="linkAccount"
          >
            <template #icon v-if="!linking">
              <Plus :size="16" :stroke-width="1.75" aria-hidden="true" />
            </template>
          </Button>
        </div>
        <UserMenu />
      </header>

      <div class="accounts-content">
        <ReconnectNotice @reconnected="load" />
        <SameInstitutionNotice
          v-if="sameInstitution.length"
          :items="sameInstitution"
          @removed="onOlderRemoved"
          @dismiss="sameInstitution = []"
        />
        <Message v-if="linkError" severity="error">{{ linkError }}</Message>

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
            <p>Connect a bank or credit card account through Plaid to see it here.</p>
            <Button
              :label="linking ? 'Connecting…' : 'Add an account'"
              :loading="linking"
              :disabled="linking"
              @click="linkAccount"
            >
              <template #icon v-if="!linking">
                <Plus :size="16" :stroke-width="1.75" aria-hidden="true" />
              </template>
            </Button>
          </div>
        </section>

        <AccountSettings v-else v-model:accounts="accounts" />
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

.title-row {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  /* As wide as the cards, so the button lines up with their right edge. */
  max-width: 60rem;
}

.page-title {
  display: flex;
  align-items: center;
  gap: 0.4rem;
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

@media (max-width: 900px) {
  .accounts-page {
    padding: 1.25rem 1.25rem calc(var(--app-tabbar-height) + env(safe-area-inset-bottom) + 1.5rem);
  }

  h1 {
    font-size: 1.375rem;
  }
}

/* Adding an account shrinks to an icon beside the avatar, as on Home. */
@media (max-width: 640px) {
  .page-heading {
    gap: 0.5rem;
  }

  .add-account-button {
    width: 2rem;
    height: 2rem;
    padding: 0;
    border-radius: 50%;
  }

  .add-account-button :deep(.p-button-label) {
    display: none;
  }
}
</style>
