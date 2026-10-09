<script setup lang="ts">
import { ArrowRight, Wallet } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Skeleton from 'primevue/skeleton'
import AppSidebar from '../components/AppSidebar.vue'
import UserMenu from '../components/UserMenu.vue'
import SpendingPlanOverview from '../components/SpendingPlanOverview.vue'
import SpendingPlanSetup from '../components/SpendingPlanSetup.vue'
import { getSpendingPlan } from '../api/SpendingPlanService'
import { fromSaved, type SavedPlan } from '../spendingPlan/savedPlan'

type DialogMode = 'setup' | 'edit'

const loading = ref(true)
const loadError = ref(false)
const savedPlan = ref<SavedPlan | null>(null)
const dialogVisible = ref(false)
const dialogMode = ref<DialogMode>('setup')
// Whether the dialog opens on the buffer's percent rather than at the top.
const focusBuffer = ref(false)

const dialogHeader = computed(() => {
  if (dialogMode.value === 'edit') return 'Edit your spending plan'
  return savedPlan.value ? 'Start a new spending plan' : 'Create your spending plan'
})

onMounted(loadPlan)

async function loadPlan() {
  loading.value = true
  loadError.value = false
  try {
    const saved = await getSpendingPlan()
    savedPlan.value = saved ? fromSaved(saved) : null
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function openDialog(mode: DialogMode, buffer = false) {
  dialogMode.value = mode
  focusBuffer.value = buffer
  dialogVisible.value = true
}

function onSaved(plan: SavedPlan) {
  savedPlan.value = plan
  dialogVisible.value = false
}
</script>

<template>
  <div class="app-shell">
    <AppSidebar />
    <main class="spending-plan-page" aria-labelledby="spending-plan-heading">
      <header class="page-heading">
        <h1 id="spending-plan-heading">Spending Plan</h1>
        <div class="page-actions">
          <RouterLink to="/accounts" class="accounts-link">Tracked accounts</RouterLink>
          <UserMenu />
        </div>
      </header>

      <div v-if="loading" class="plan-loading" aria-label="Loading your spending plan">
        <Skeleton height="12rem" />
        <Skeleton height="16rem" />
      </div>

      <section v-else-if="loadError" class="panel plan-prompt" aria-labelledby="plan-error-heading">
        <div class="plan-prompt-content">
          <h2 id="plan-error-heading">We couldn’t load your spending plan.</h2>
          <p>Check your connection and try again.</p>
          <Button label="Try again" severity="secondary" @click="loadPlan" />
        </div>
      </section>

      <SpendingPlanOverview
        v-else-if="savedPlan"
        :plan="savedPlan"
        @edit="openDialog('edit')"
        @edit-buffer="openDialog('edit', true)"
        @start-over="openDialog('setup')"
      />

      <section v-else class="panel plan-prompt" aria-labelledby="plan-prompt-heading">
        <div class="plan-prompt-content">
          <div class="plan-icon" aria-hidden="true">
            <Wallet :size="24" :stroke-width="1.5" />
          </div>
          <h2 id="plan-prompt-heading">Create your spending plan</h2>
          <p>
            Decide how much of your income goes toward fixed costs, investments, and savings. The
            rest is yours to spend guilt-free.
          </p>
          <Button label="Get started" aria-haspopup="dialog" @click="openDialog('setup')">
            <template #icon>
              <ArrowRight :size="16" :stroke-width="1.75" aria-hidden="true" />
            </template>
          </Button>
        </div>
      </section>
    </main>

    <Dialog
      v-model:visible="dialogVisible"
      modal
      dismissable-mask
      block-scroll
      :header="dialogHeader"
      :draggable="false"
      :style="{ width: 'min(76rem, calc(100vw - 2rem))', height: '88dvh', maxHeight: '92dvh' }"
      :breakpoints="{ '640px': 'calc(100vw - 1.5rem)' }"
      :pt="{
        content: { style: { display: 'flex', flex: '1', minHeight: '0', padding: '0' } },
      }"
    >
      <SpendingPlanSetup
        :key="dialogMode"
        :saved="dialogMode === 'edit' ? (savedPlan ?? undefined) : undefined"
        :replacing="dialogMode === 'setup' && savedPlan != null"
        :focus-buffer="focusBuffer"
        @saved="onSaved"
      />
    </Dialog>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  align-items: stretch;
  height: 100svh;
}

.spending-plan-page {
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

.page-actions {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.accounts-link {
  color: var(--app-text-secondary);
  font-size: 0.875rem;
  font-weight: 500;
}

.accounts-link:hover {
  color: var(--app-text);
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

.plan-loading {
  display: grid;
  gap: 1.25rem;
}

.plan-prompt {
  display: grid;
  flex: 1;
  min-height: 24rem;
  padding: 3rem 1.5rem;
  place-items: center;
}

.plan-prompt-content {
  width: min(100%, 27rem);
  text-align: center;
}

.plan-icon {
  display: grid;
  width: 3rem;
  height: 3rem;
  margin: 0 auto 1.25rem;
  place-items: center;
  color: var(--app-text-secondary);
  background: var(--app-inset);
  border-radius: var(--app-radius-control);
}

h2 {
  font-size: 1.5rem;
  font-weight: 550;
  line-height: 1.25;
  letter-spacing: -0.035em;
}

.plan-prompt p {
  margin: 0.75rem 0 1.5rem;
  color: var(--app-text-secondary);
  line-height: 1.7;
}

@media (max-width: 900px) {
  .spending-plan-page {
    padding: 1.25rem 1.25rem calc(var(--app-tabbar-height) + env(safe-area-inset-bottom) + 1.5rem);
  }

  h1 {
    font-size: 1.375rem;
  }
}

@media (max-width: 640px) {
  .plan-prompt {
    min-height: 20rem;
    padding: 2rem 1.25rem;
  }

  h2 {
    font-size: 1.25rem;
  }
}
</style>
