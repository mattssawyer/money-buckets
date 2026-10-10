<script setup lang="ts">
import { ArrowLeft, ArrowRight, Check, Landmark, Plus, TrendingUp } from '@lucide/vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import AccountSettings from '../components/AccountSettings.vue'
import AddInvestments from '../components/AddInvestments.vue'
import InfoTip from '../components/InfoTip.vue'
import SameInstitutionNotice from '../components/SameInstitutionNotice.vue'
import SpendingPlanSetup from '../components/SpendingPlanSetup.vue'
import UserMenu from '../components/UserMenu.vue'
import { getAccounts, getLinkedItems, type PlaidAccount, type PlaidItem } from '../api/PlaidService'
import { getSpendingPlan } from '../api/SpendingPlanService'
import { accountLabel } from '../accounts/useSelectedAccount'
import { formatMoney } from '../investments/history'
import { useOnboarding } from '../onboarding'
import { closePlaidLink, linkItem } from '../plaid/plaidLink'
import { fromSaved, type SavedPlan } from '../spendingPlan/savedPlan'

type Step = 'banks' | 'accounts' | 'plan' | 'investments'

const STEPS: Array<{ id: Step; title: string }> = [
  { id: 'banks', title: 'Connect banks' },
  { id: 'accounts', title: 'Choose accounts' },
  { id: 'plan', title: 'Spending plan' },
  { id: 'investments', title: 'Investments' },
]

const logoUrl = `${import.meta.env.BASE_URL}logo.svg`

const route = useRoute()
const router = useRouter()
const onboarding = useOnboarding()

const loading = ref(true)
const loadError = ref(false)
const items = ref<PlaidItem[]>([])
const accounts = ref<PlaidAccount[]>([])
const linking = ref(false)
const linkError = ref('')
// Older connections to the bank just linked, which the user may want to remove.
const sameInstitution = ref<PlaidItem[]>([])
// A plan saved earlier in onboarding, so going back to the plan step edits it.
const savedPlan = ref<SavedPlan | null>(null)
const planLoading = ref(false)
const finishing = ref(false)
const finishError = ref('')
let disposed = false

/** The step in the URL, so Back works and a reload stays put. Every step after the first needs a bank. */
const step = computed<Step>(() => {
  const asked = STEPS.find((candidate) => candidate.id === route.query.step)?.id ?? 'banks'
  return items.value.length ? asked : 'banks'
})
const stepIndex = computed(() => STEPS.findIndex((candidate) => candidate.id === step.value))

const investmentAccounts = computed(() =>
  accounts.value.filter((account) => account.type === 'investment' || account.type === 'brokerage'),
)

onMounted(load)
onUnmounted(() => {
  disposed = true
  closePlaidLink()
})

// Also when the page opens on the plan step, once the banks load and let it show.
watch(step, (next) => {
  if (next === 'plan') void loadPlan()
})

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const [linked, stored] = await Promise.all([getLinkedItems(), getAccounts()])
    if (disposed) return
    items.value = linked
    accounts.value = stored
  } catch {
    if (!disposed) loadError.value = true
  } finally {
    if (!disposed) loading.value = false
  }
}

/** Reloads connections and accounts without leaving the step; on failure, keeps what's shown. */
async function refresh() {
  try {
    const [linked, stored] = await Promise.all([getLinkedItems(), getAccounts()])
    if (disposed) return
    items.value = linked
    accounts.value = stored
  } catch {
    // The next step's load, or Home, shows them.
  }
}

async function loadPlan() {
  planLoading.value = true
  try {
    const saved = await getSpendingPlan()
    if (!disposed) savedPlan.value = saved ? fromSaved(saved) : null
  } catch {
    // Without it the step starts a new plan, which is what a new user has anyway.
  } finally {
    if (!disposed) planLoading.value = false
  }
}

function go(next: Step) {
  void router.push({ name: 'welcome', query: next === 'banks' ? {} : { step: next } })
}

async function link(options: { investments?: boolean } = {}) {
  if (linking.value) return
  linking.value = true
  linkError.value = ''
  try {
    const result = await linkItem(options)
    if (disposed || !result) return
    sameInstitution.value = result.same_institution
    await refresh()
  } catch {
    if (!disposed) linkError.value = 'We couldn’t connect that account. Please try again.'
  } finally {
    if (!disposed) linking.value = false
  }
}

function onOlderRemoved() {
  sameInstitution.value = []
  void refresh()
}

function onPlanSaved(plan: SavedPlan) {
  savedPlan.value = plan
  go('investments')
}

async function finish() {
  if (finishing.value) return
  finishing.value = true
  finishError.value = ''
  try {
    await onboarding.finish()
    if (!disposed) await router.push({ name: 'home' })
  } catch {
    if (!disposed) finishError.value = 'We couldn’t finish setting up. Please try again.'
  } finally {
    if (!disposed) finishing.value = false
  }
}
</script>

<template>
  <div class="welcome">
    <header class="welcome-header">
      <div class="welcome-brand">
        <img :src="logoUrl" alt="" width="32" height="32" />
        <span>Money Buckets</span>
      </div>
      <ol class="steps" aria-label="Setup steps">
        <li
          v-for="(entry, index) in STEPS"
          :key="entry.id"
          class="step"
          :class="{ 'step-done': index < stepIndex, 'step-current': index === stepIndex }"
          :aria-current="index === stepIndex ? 'step' : undefined"
        >
          <span class="step-marker" aria-hidden="true">
            <Check v-if="index < stepIndex" :size="12" :stroke-width="2.5" />
            <template v-else>{{ index + 1 }}</template>
          </span>
          <span class="step-title">{{ entry.title }}</span>
        </li>
      </ol>
      <p class="steps-compact">Step {{ stepIndex + 1 }} of {{ STEPS.length }}</p>
      <UserMenu />
    </header>

    <main class="welcome-main" :class="{ 'welcome-wide': step === 'plan' }">
      <div v-if="loading" class="welcome-loading" aria-label="Loading your accounts">
        <Skeleton height="2.5rem" width="60%" />
        <Skeleton height="12rem" />
      </div>

      <section v-else-if="loadError" class="panel welcome-card" aria-labelledby="error-heading">
        <h1 id="error-heading">We couldn’t load your accounts.</h1>
        <p class="lede">Check your connection and try again.</p>
        <div class="step-actions">
          <Button label="Try again" severity="secondary" @click="load" />
        </div>
      </section>

      <section v-else-if="step === 'banks'" aria-labelledby="banks-heading">
        <h1 id="banks-heading">Connect your bank accounts</h1>
        <p class="lede">
          Link the checking, savings and credit card accounts you spend from. You can add more
          later.
        </p>

        <SameInstitutionNotice
          v-if="sameInstitution.length"
          :items="sameInstitution"
          @removed="onOlderRemoved"
          @dismiss="sameInstitution = []"
        />
        <Message v-if="linkError" severity="error">{{ linkError }}</Message>

        <div v-if="!items.length" class="panel welcome-card empty-card">
          <div class="card-icon" aria-hidden="true">
            <Landmark :size="22" :stroke-width="1.5" />
          </div>
          <p>You’ll sign in to your bank through Plaid.</p>
          <Button
            :label="linking ? 'Connecting…' : 'Connect a bank'"
            :loading="linking"
            :disabled="linking"
            @click="link()"
          >
            <template #icon v-if="!linking">
              <Plus :size="16" :stroke-width="1.75" aria-hidden="true" />
            </template>
          </Button>
        </div>

        <template v-else>
          <ul class="panel connected-list" aria-label="Connected banks">
            <li v-for="item in items" :key="item.item_id" class="connected">
              <span class="connected-name">
                <Landmark :size="16" :stroke-width="1.75" aria-hidden="true" />
                {{ item.institution_name ?? 'Connected bank' }}
              </span>
              <span class="connected-status">
                <Check :size="14" :stroke-width="2" aria-hidden="true" />
                Connected
              </span>
            </li>
          </ul>
          <div class="step-actions">
            <Button
              :label="linking ? 'Connecting…' : 'Connect another bank'"
              severity="secondary"
              outlined
              :loading="linking"
              :disabled="linking"
              @click="link()"
            >
              <template #icon v-if="!linking">
                <Plus :size="16" :stroke-width="1.75" aria-hidden="true" />
              </template>
            </Button>
            <Button label="Continue" icon-pos="right" :disabled="linking" @click="go('accounts')">
              <template #icon>
                <ArrowRight :size="16" :stroke-width="1.75" aria-hidden="true" />
              </template>
            </Button>
          </div>
        </template>
      </section>

      <section v-else-if="step === 'accounts'" aria-labelledby="accounts-heading">
        <div class="heading-row">
          <h1 id="accounts-heading">Choose what counts</h1>
          <InfoTip
            label="About choosing accounts"
            text="Spending is counted from the accounts switched on under Spending; savings start off, since money moved there is saved rather than spent. For an account you split with someone, like a joint account for rent, turn on Shared and set your share. Only your part counts."
          />
        </div>
        <p class="lede">Check which accounts you spend from, and mark any you share.</p>

        <AccountSettings v-model:accounts="accounts" />

        <div class="step-actions">
          <Button label="Back" severity="secondary" text @click="go('banks')">
            <template #icon>
              <ArrowLeft :size="16" :stroke-width="1.75" aria-hidden="true" />
            </template>
          </Button>
          <Button label="Continue" icon-pos="right" @click="go('plan')">
            <template #icon>
              <ArrowRight :size="16" :stroke-width="1.75" aria-hidden="true" />
            </template>
          </Button>
        </div>
      </section>

      <section v-else-if="step === 'plan'" class="plan-step" aria-labelledby="plan-heading">
        <div class="plan-step-heading">
          <div>
            <div class="heading-row">
              <h1 id="plan-heading">Make your spending plan</h1>
              <InfoTip
                label="About the spending plan"
                text="Your take-home pay split across fixed costs, investments and savings, with the rest left for guilt-free spending. Amounts fill in from your recurring bills and what you usually spend, which can take a few hours to arrive after connecting a bank."
              />
            </div>
            <p class="lede">
              Your history may still be coming in. You can skip this and make your plan later from
              Spending Plan.
            </p>
          </div>
          <div class="plan-step-actions">
            <Button label="Back" severity="secondary" text @click="go('accounts')">
              <template #icon>
                <ArrowLeft :size="16" :stroke-width="1.75" aria-hidden="true" />
              </template>
            </Button>
            <Button
              :label="savedPlan ? 'Continue' : 'Skip for now'"
              severity="secondary"
              @click="go('investments')"
            />
          </div>
        </div>
        <div class="panel plan-panel">
          <Skeleton v-if="planLoading" height="100%" />
          <SpendingPlanSetup
            v-else
            :key="savedPlan ? 'edit' : 'setup'"
            :saved="savedPlan ?? undefined"
            @saved="onPlanSaved"
          />
        </div>
      </section>

      <section v-else aria-labelledby="investments-heading">
        <h1 id="investments-heading">Add investment accounts</h1>
        <p class="lede">
          Optional. Link an IRA, 401(k) or brokerage account to follow its balance alongside your
          net worth.
        </p>

        <SameInstitutionNotice
          v-if="sameInstitution.length"
          :items="sameInstitution"
          @removed="onOlderRemoved"
          @dismiss="sameInstitution = []"
        />
        <Message v-if="linkError" severity="error">{{ linkError }}</Message>

        <ul
          v-if="investmentAccounts.length"
          class="panel connected-list"
          aria-label="Investment accounts"
        >
          <li v-for="account in investmentAccounts" :key="account.account_id" class="connected">
            <span class="connected-name">
              <TrendingUp :size="16" :stroke-width="1.75" aria-hidden="true" />
              {{ accountLabel(account) }}
            </span>
            <span v-if="account.balances.current != null" class="connected-balance">
              {{ formatMoney(account.balances.current) }}
            </span>
          </li>
        </ul>

        <div class="link-investments">
          <Button
            :label="linking ? 'Connecting…' : 'Link investment account'"
            :severity="investmentAccounts.length ? 'secondary' : undefined"
            :outlined="investmentAccounts.length > 0"
            :loading="linking"
            :disabled="linking"
            @click="link({ investments: true })"
          >
            <template #icon v-if="!linking">
              <Plus :size="16" :stroke-width="1.75" aria-hidden="true" />
            </template>
          </Button>
        </div>

        <AddInvestments :items="items" @added="refresh" />

        <Message v-if="finishError" severity="error">{{ finishError }}</Message>
        <div class="step-actions">
          <Button label="Back" severity="secondary" text @click="go('plan')">
            <template #icon>
              <ArrowLeft :size="16" :stroke-width="1.75" aria-hidden="true" />
            </template>
          </Button>
          <Button
            :label="investmentAccounts.length ? 'Finish' : 'Skip and finish'"
            :severity="investmentAccounts.length ? undefined : 'secondary'"
            :loading="finishing"
            :disabled="finishing || linking"
            @click="finish"
          />
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.welcome {
  display: flex;
  flex-direction: column;
  min-height: 100svh;
}

.welcome-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1.5rem;
  padding: 1rem 1.5rem;
}

.welcome-brand {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  font-weight: 550;
  letter-spacing: -0.035em;
}

.welcome-brand img {
  display: block;
  width: 2rem;
  height: 2rem;
  border-radius: 0.5rem;
}

.steps {
  display: flex;
  gap: 1.5rem;
  padding: 0;
  margin: 0;
  list-style: none;
}

.step {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--app-text-subdued);
  font-size: 0.875rem;
  font-weight: 500;
}

.step-marker {
  display: grid;
  width: 1.375rem;
  height: 1.375rem;
  place-items: center;
  font-size: 0.75rem;
  font-variant-numeric: tabular-nums;
  border: 1px solid var(--app-control-border);
  border-radius: 50%;
}

.step-current {
  color: var(--app-text);
}

.step-current .step-marker {
  color: var(--app-text-inverse);
  background: var(--app-text);
  border-color: var(--app-text);
}

.step-done {
  color: var(--app-text-secondary);
}

.step-done .step-marker {
  color: var(--app-text);
  background: var(--app-inset);
  border-color: transparent;
}

.steps-compact {
  display: none;
  color: var(--app-text-secondary);
  font-size: 0.875rem;
  font-weight: 500;
}

.welcome-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: min(100% - 2rem, 44rem);
  margin: 0 auto;
  padding: 2.5rem 0 3rem;
}

.welcome-main.welcome-wide {
  width: min(100% - 2rem, 76rem);
  padding-top: 1.5rem;
}

.welcome-main > section {
  display: grid;
  gap: 1.25rem;
}

.welcome-loading {
  display: grid;
  gap: 1.25rem;
}

h1 {
  font-size: 1.75rem;
  font-weight: 550;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.heading-row {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.lede {
  margin-top: -0.5rem;
  color: var(--app-text-secondary);
  line-height: 1.6;
}

.welcome-card {
  padding: 2rem 1.5rem;
}

.empty-card {
  display: grid;
  justify-items: center;
  gap: 1.25rem;
  text-align: center;
}

.empty-card p {
  max-width: 24rem;
  color: var(--app-text-secondary);
  line-height: 1.6;
}

.card-icon {
  display: grid;
  width: 3rem;
  height: 3rem;
  place-items: center;
  color: var(--app-text-secondary);
  background: var(--app-inset);
  border-radius: var(--app-radius-control);
}

.connected-list {
  display: grid;
  padding: 0.25rem 1.25rem;
  margin: 0;
  list-style: none;
}

.connected {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.875rem 0;
  border-top: 1px solid var(--app-divider);
}

.connected:first-child {
  border-top: 0;
}

.connected-name {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  min-width: 0;
  font-weight: 500;
}

.connected-status {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  color: var(--app-success);
  font-size: 0.875rem;
}

.connected-balance {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.step-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.75rem;
}

/* Back sits at the start, away from the buttons that go forward. */
.step-actions > :first-child:not(:only-child) {
  margin-right: auto;
}

.link-investments {
  display: flex;
}

.welcome-main > .plan-step {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 1rem;
}

.plan-step-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1.5rem;
}

.plan-step-heading .lede {
  margin-top: 0.5rem;
}

.plan-step-actions {
  display: flex;
  flex: none;
  gap: 0.5rem;
}

/* The setup scrolls inside, like it does in its dialog, with its save button always in view. */
.plan-panel {
  display: flex;
  flex: 1;
  min-height: 34rem;
  height: calc(100svh - 14rem);
  overflow: hidden;
}

@media (max-width: 64rem) {
  .steps {
    display: none;
  }

  .steps-compact {
    display: block;
  }
}

@media (max-width: 40rem) {
  .welcome-header {
    padding: 0.875rem 1rem;
  }

  .welcome-main {
    padding-top: 1.5rem;
  }

  h1 {
    font-size: 1.5rem;
  }

  .plan-step-heading {
    flex-direction: column;
    align-items: stretch;
  }

  .plan-panel {
    height: calc(100svh - 10rem);
  }
}
</style>
