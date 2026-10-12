<script setup lang="ts">
import { ArrowLeft, ArrowRight, Check, Landmark, Plus, TrendingUp } from '@lucide/vue'
import { computed, onMounted, onUnmounted, ref, useTemplateRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import AccountChoices from '../components/AccountChoices.vue'
import AddInvestments from '../components/AddInvestments.vue'
import InfoTip from '../components/InfoTip.vue'
import PlanFlow from '../components/PlanFlow.vue'
import SameInstitutionNotice from '../components/SameInstitutionNotice.vue'
import SpendingPlanDialog from '../components/SpendingPlanDialog.vue'
import UserMenu from '../components/UserMenu.vue'
import { getAccounts, getLinkedItems, type PlaidAccount, type PlaidItem } from '../api/PlaidService'
import { getSpendingPlan } from '../api/SpendingPlanService'
import { accountLabel } from '../accounts/useSelectedAccount'
import { formatMoney } from '../money'
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
const planDialogVisible = ref(false)
const finishing = ref(false)
const finishError = ref('')
// Shown for a moment after finishing, before Home.
const finished = ref(false)
let disposed = false

/** The same curve as the step transitions in the styles below. */
const EASE_OUT = 'cubic-bezier(0.22, 1, 0.36, 1)'
/** How long the "You're all set" moment shows before Home. */
const FINISHED_PAUSE_MS = 1400

// Anyone who has asked their system for less motion skips the movement, and the pause.
const animated =
  typeof window.matchMedia === 'function' &&
  !window.matchMedia('(prefers-reduced-motion: reduce)').matches

const card = useTemplateRef<HTMLElement>('card')
// Which way the steps slide: a later step comes in from the right, an earlier one from the left.
const direction = ref<'forward' | 'back'>('forward')

/** The step in the URL, so Back works and a reload stays put. Every step after the first needs a bank. */
const step = computed<Step>(() => {
  const asked = STEPS.find((candidate) => candidate.id === route.query.step)?.id ?? 'banks'
  return items.value.length ? asked : 'banks'
})
const stepIndex = computed(() => STEPS.findIndex((candidate) => candidate.id === step.value))
// Every step shows as done once the user finishes.
const progressIndex = computed(() => (finished.value ? STEPS.length : stepIndex.value))

const investmentAccounts = computed(() =>
  accounts.value.filter((account) => account.type === 'investment' || account.type === 'brokerage'),
)

onMounted(load)
onUnmounted(() => {
  disposed = true
  closePlaidLink()
})

watch(
  stepIndex,
  (next, previous) => {
    direction.value = next < previous ? 'back' : 'forward'
  },
  { flush: 'sync' },
)

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
  try {
    const saved = await getSpendingPlan()
    if (!disposed) savedPlan.value = saved ? fromSaved(saved) : null
  } catch {
    // Without it the step offers a new plan, which is what a new user has anyway.
  }
}

/** Holds the card at its size while a step leaves, so it doesn't collapse between steps. */
function holdSize() {
  const element = card.value
  if (!element) return
  element.style.width = `${element.offsetWidth}px`
  element.style.height = `${element.offsetHeight}px`
}

/** Grows or shrinks the card from the size it was held at to what the new step needs. */
function settleSize() {
  const element = card.value
  if (!element) return
  const from = { width: element.offsetWidth, height: element.offsetHeight }
  element.style.width = ''
  element.style.height = ''
  const to = { width: element.offsetWidth, height: element.offsetHeight }
  const unchanged = from.width === to.width && from.height === to.height
  if (!animated || unchanged || typeof element.animate !== 'function') return
  element.animate(
    [
      { width: `${from.width}px`, height: `${from.height}px` },
      { width: `${to.width}px`, height: `${to.height}px` },
    ],
    { duration: 460, easing: EASE_OUT },
  )
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
  planDialogVisible.value = false
  go('investments')
}

async function finish() {
  if (finishing.value) return
  finishing.value = true
  finishError.value = ''
  try {
    await onboarding.finish()
    if (disposed) return
    if (animated) {
      finished.value = true
      await new Promise((resolve) => setTimeout(resolve, FINISHED_PAUSE_MS))
      if (disposed) return
    }
    await router.push({ name: 'home' })
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
      <UserMenu />
    </header>

    <main class="welcome-main">
      <div ref="card" class="panel welcome-card">
        <Transition
          :name="`step-${direction}`"
          mode="out-in"
          :duration="{ enter: 700, leave: 200 }"
          @before-leave="holdSize"
          @enter="settleSize"
        >
          <section v-if="finished" class="finished" aria-labelledby="finished-heading">
            <div class="finished-mark" aria-hidden="true">
              <Check :size="30" :stroke-width="2.5" />
            </div>
            <h1 id="finished-heading">You’re all set</h1>
            <p class="lede">Taking you to your dashboard…</p>
          </section>

          <div v-else-if="loading" class="welcome-loading" aria-label="Loading your accounts">
            <Skeleton height="2.5rem" width="60%" />
            <Skeleton height="12rem" />
          </div>

          <section v-else-if="loadError" aria-labelledby="error-heading">
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

            <Transition name="swap" mode="out-in">
              <div v-if="!items.length" class="empty-state">
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

              <div v-else class="connected-block">
                <TransitionGroup
                  tag="ul"
                  name="item"
                  class="connected-list"
                  aria-label="Connected banks"
                >
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
                </TransitionGroup>
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
                  <Button
                    label="Continue"
                    icon-pos="right"
                    :disabled="linking"
                    @click="go('accounts')"
                  >
                    <template #icon="{ class: iconClass }">
                      <ArrowRight
                        :class="iconClass"
                        :size="16"
                        :stroke-width="1.75"
                        aria-hidden="true"
                      />
                    </template>
                  </Button>
                </div>
              </div>
            </Transition>
          </section>

          <section v-else-if="step === 'accounts'" aria-labelledby="accounts-heading">
            <div class="heading-row">
              <h1 id="accounts-heading">Choose what counts</h1>
              <InfoTip
                label="About choosing accounts"
                text="Only accounts switched on under Spending count toward it. For a joint account, turn on Shared and set your share."
              />
            </div>
            <p class="lede">Check which accounts you spend from, and mark any you share.</p>

            <AccountChoices v-model:accounts="accounts" />

            <div class="step-actions">
              <Button label="Back" severity="secondary" text @click="go('banks')">
                <template #icon>
                  <ArrowLeft :size="16" :stroke-width="1.75" aria-hidden="true" />
                </template>
              </Button>
              <Button label="Continue" icon-pos="right" @click="go('plan')">
                <template #icon="{ class: iconClass }">
                  <ArrowRight
                    :class="iconClass"
                    :size="16"
                    :stroke-width="1.75"
                    aria-hidden="true"
                  />
                </template>
              </Button>
            </div>
          </section>

          <section v-else-if="step === 'plan'" aria-labelledby="plan-heading">
            <h1 id="plan-heading">Your spending plan</h1>
            <p class="lede">Pay yourself and your bills first. Then enjoy the rest, guilt-free.</p>

            <PlanFlow class="plan-flow" />

            <p v-if="savedPlan" class="plan-note plan-saved">
              <Check :size="16" :stroke-width="2" aria-hidden="true" />
              Your plan is saved.
            </p>
            <p v-else class="plan-note">
              Your bills take a few hours to arrive, so it’s easier to make your plan later from
              Home.
            </p>

            <div class="step-actions">
              <Button label="Back" severity="secondary" text @click="go('accounts')">
                <template #icon>
                  <ArrowLeft :size="16" :stroke-width="1.75" aria-hidden="true" />
                </template>
              </Button>
              <Button
                :label="savedPlan ? 'Edit plan' : 'Make it now'"
                severity="secondary"
                outlined
                aria-haspopup="dialog"
                @click="planDialogVisible = true"
              />
              <Button
                :label="savedPlan ? 'Continue' : 'Set it up later'"
                icon-pos="right"
                @click="go('investments')"
              >
                <template #icon="{ class: iconClass }">
                  <ArrowRight
                    :class="iconClass"
                    :size="16"
                    :stroke-width="1.75"
                    aria-hidden="true"
                  />
                </template>
              </Button>
            </div>
          </section>

          <section v-else aria-labelledby="investments-heading">
            <h1 id="investments-heading">Add investment accounts</h1>
            <p class="lede">
              Optional. Link an IRA, 401(k) or brokerage account to follow its balance alongside
              your net worth.
            </p>

            <SameInstitutionNotice
              v-if="sameInstitution.length"
              :items="sameInstitution"
              @removed="onOlderRemoved"
              @dismiss="sameInstitution = []"
            />
            <Message v-if="linkError" severity="error">{{ linkError }}</Message>

            <TransitionGroup
              v-if="investmentAccounts.length"
              tag="ul"
              name="item"
              class="connected-list"
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
            </TransitionGroup>

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
        </Transition>
      </div>

      <ol class="progress" aria-label="Setup progress">
        <li
          v-for="(entry, index) in STEPS"
          :key="entry.id"
          class="progress-step"
          :class="{
            'progress-done': index < progressIndex,
            'progress-current': index === progressIndex,
          }"
          :aria-current="index === progressIndex ? 'step' : undefined"
        >
          <span class="progress-bubble">
            <Check v-if="index < progressIndex" :size="14" :stroke-width="2.5" aria-hidden="true" />
            <template v-else>{{ index + 1 }}</template>
          </span>
          <span class="progress-label">{{ entry.title }}</span>
          <span v-if="index < progressIndex" class="visually-hidden">, done</span>
        </li>
      </ol>
    </main>

    <SpendingPlanDialog
      v-model:visible="planDialogVisible"
      :header="savedPlan ? 'Edit your spending plan' : 'Create your spending plan'"
      :saved="savedPlan ?? undefined"
      @saved="onPlanSaved"
    />
  </div>
</template>

<style scoped>
.welcome {
  /* One curve for everything that moves, so steps, the card and the progress bar feel related. */
  --ease-out: cubic-bezier(0.22, 1, 0.36, 1);
  --ease-spring: cubic-bezier(0.34, 1.56, 0.64, 1);

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

/* The card sits in the middle of the screen, with the progress bar just under it. */
.welcome-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  gap: 1.75rem;
  width: min(100% - 2rem, 30rem);
  margin: 0 auto;
  padding: 1rem 0 3rem;
}

/* Narrow and upright, taller than it is wide, with the buttons along the bottom. */
.welcome-card {
  display: flex;
  flex-direction: column;
  align-self: center;
  width: 100%;
  min-height: min(34rem, 100svh - 13rem);
  padding: clamp(1.5rem, 4vw, 2.25rem);
  /* Steps slide within the card rather than over its edges. */
  overflow: hidden;
  animation: card-in 700ms var(--ease-out) both;
}

.welcome-card > section,
.welcome-loading {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 1.25rem;
}

/* Cards inside the card would stack shadows; they read as plain outlined boxes here. */
.welcome-card :deep(.panel) {
  box-shadow: none;
  border: 1px solid var(--app-divider);
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

/* Fills the card's height, so the card stays upright without empty space below. */
.empty-state {
  display: grid;
  flex: 1;
  align-content: center;
  justify-items: center;
  gap: 1rem;
  padding: 1.75rem 1rem;
  text-align: center;
  background: var(--app-canvas);
  border-radius: var(--app-radius-control);
}

.empty-state p {
  color: var(--app-text-secondary);
}

.card-icon {
  display: grid;
  width: 3rem;
  height: 3rem;
  place-items: center;
  color: var(--app-text-secondary);
  background: var(--app-surface);
  border-radius: var(--app-radius-control);
  box-shadow: var(--app-shadow-border);
}

.connected-list {
  display: grid;
  padding: 0.25rem 1.25rem;
  margin: 0;
  list-style: none;
  border: 1px solid var(--app-divider);
  border-radius: var(--app-radius-control);
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
  font-size: var(--text-base);
}

.connected-balance {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.step-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 0.75rem;
  margin-top: auto;
  padding-top: 0.25rem;
}

/* Back sits at the start, away from the buttons that go forward. */
.step-actions > :first-child:not(:only-child) {
  margin-right: auto;
}

.plan-flow {
  margin: 0.25rem 0;
}

.plan-note {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  color: var(--app-text-secondary);
  font-size: var(--text-base);
  line-height: 1.5;
}

.plan-saved {
  color: var(--app-success);
  font-weight: 500;
}

.link-investments {
  display: flex;
}

/* The card rises into place when the page opens, and the progress bar follows. */
@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(1.25rem) scale(0.98);
  }
}

.progress {
  animation: fade-up 600ms 180ms var(--ease-out) both;
}

@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(0.5rem);
  }
}

/*
 * Steps: the old one slips away the way the user is going, then the new one slides in from the
 * other side, its heading, text and controls following one after another.
 */
.step-forward-leave-active,
.step-back-leave-active {
  transition:
    opacity 160ms ease-in,
    transform 200ms ease-in;
}

.step-forward-enter-active,
.step-back-enter-active {
  transition:
    opacity 360ms var(--ease-out),
    transform 520ms var(--ease-out);
}

.step-forward-enter-from {
  opacity: 0;
  transform: translateX(2.5rem);
}

.step-forward-leave-to {
  opacity: 0;
  transform: translateX(-1.5rem);
}

.step-back-enter-from {
  opacity: 0;
  transform: translateX(-2.5rem);
}

.step-back-leave-to {
  opacity: 0;
  transform: translateX(1.5rem);
}

.step-forward-enter-active > *,
.step-back-enter-active > * {
  animation: fade-up 520ms var(--ease-out) both;
}

.step-forward-enter-active > :nth-child(2),
.step-back-enter-active > :nth-child(2) {
  animation-delay: 50ms;
}

.step-forward-enter-active > :nth-child(3),
.step-back-enter-active > :nth-child(3) {
  animation-delay: 100ms;
}

.step-forward-enter-active > :nth-child(n + 4),
.step-back-enter-active > :nth-child(n + 4) {
  animation-delay: 150ms;
}

/* The first bank's prompt gives way to the list of connected banks. */
.swap-enter-active {
  transition:
    opacity 320ms var(--ease-out),
    transform 420ms var(--ease-out);
}

.swap-leave-active {
  transition: opacity 140ms ease-in;
}

.swap-enter-from {
  opacity: 0;
  transform: scale(0.97);
}

.swap-leave-to {
  opacity: 0;
}

.connected-block {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 1.25rem;
}

/* Each bank or investment account drops into its list as it's connected. */
.item-enter-active {
  transition:
    opacity 360ms var(--ease-out),
    transform 460ms var(--ease-spring);
}

.item-enter-from {
  opacity: 0;
  transform: translateY(-0.5rem) scale(0.98);
}

.item-move {
  transition: transform 360ms var(--ease-out);
}

/* After finishing: a check that pops in and draws itself, before Home. */
.finished {
  align-items: center;
  justify-content: center;
  text-align: center;
}

.finished .lede {
  margin-top: -0.25rem;
}

.finished-mark {
  display: grid;
  width: 4.5rem;
  height: 4.5rem;
  margin-bottom: 0.5rem;
  place-items: center;
  color: var(--app-text-inverse);
  background: var(--app-text);
  border-radius: 50%;
  animation:
    bubble-pop 620ms 120ms var(--ease-spring) both,
    ring 1100ms 300ms var(--ease-out) both;
}

.finished-mark :deep(path) {
  stroke-dasharray: 24;
  animation: draw 480ms 420ms var(--ease-out) both;
}

@keyframes ring {
  from {
    box-shadow: 0 0 0 0 rgb(23 23 23 / 30%);
  }

  to {
    box-shadow: 0 0 0 1.25rem rgb(23 23 23 / 0%);
  }
}

@keyframes draw {
  from {
    stroke-dashoffset: 24;
  }

  to {
    stroke-dashoffset: 0;
  }
}

@keyframes bubble-pop {
  0% {
    transform: scale(0.6);
  }

  60% {
    transform: scale(1.12);
  }

  100% {
    transform: scale(1);
  }
}

/* Numbered bubbles joined by a line that fills in as steps are done. */
.progress {
  display: flex;
  width: 100%;
  max-width: 30rem;
  padding: 0;
  margin: 0 auto;
  list-style: none;
}

.progress-step {
  position: relative;
  display: grid;
  flex: 1;
  justify-items: center;
  gap: 0.5rem;
  color: var(--app-text-secondary);
  font-size: var(--text-sm);
  font-weight: 500;
  text-align: center;
}

.progress-step:not(:first-child)::before {
  position: absolute;
  top: calc(0.875rem - 1px);
  right: 50%;
  left: -50%;
  height: 2px;
  content: '';
  background: var(--app-text);
}

/* Steps still to come are outlined in the same black, so the bar reads as one piece. */
.progress-bubble {
  position: relative;
  z-index: 1;
  display: grid;
  width: 1.75rem;
  height: 1.75rem;
  place-items: center;
  color: var(--app-text);
  font-variant-numeric: tabular-nums;
  background: var(--app-canvas);
  border: 2px solid var(--app-text);
  border-radius: 50%;
}

.progress-done,
.progress-current {
  color: var(--app-text);
}

.progress-done .progress-bubble,
.progress-current .progress-bubble {
  color: var(--app-text-inverse);
  background: var(--app-text);
}

.progress-current .progress-bubble {
  box-shadow:
    0 0 0 3px var(--app-canvas),
    0 0 0 5px var(--app-text);
  animation: bubble-pop 560ms var(--ease-spring);
}

.progress-bubble {
  transition:
    background-color 320ms var(--ease-out),
    color 320ms var(--ease-out),
    box-shadow 420ms var(--ease-out);
}

/* A finished step's check draws itself in. */
.progress-done .progress-bubble :deep(path) {
  stroke-dasharray: 24;
  animation: draw 420ms 120ms var(--ease-out) both;
}

.progress-label {
  transition: color 320ms var(--ease-out);
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

@media (prefers-reduced-motion: reduce) {
  .welcome *,
  .welcome *::before,
  .welcome *::after,
  .welcome-card {
    animation: none !important;
    transition-duration: 1ms !important;
  }
}

@media (max-width: 40rem) {
  .welcome-header {
    padding: 0.875rem 1rem;
  }

  .welcome-main {
    justify-content: flex-start;
  }

  /* Only the current step keeps its name, so the labels don't crowd each other. */
  .progress-step:not(.progress-current) .progress-label {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
}
</style>
