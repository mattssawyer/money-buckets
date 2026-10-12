<script setup lang="ts">
import Skeleton from 'primevue/skeleton'
import AppSidebar from './AppSidebar.vue'
import SkeletonCard from './SkeletonCard.vue'
import SkeletonRows from './SkeletonRows.vue'

defineProps<{ home: boolean }>()
</script>

<template>
  <!--
    Stands in for the page while sign-in starts up. Home's cards are laid out as HomePage lays
    them out while it loads, so the page takes over without anything moving.
  -->
  <div class="app-shell">
    <AppSidebar />
    <main class="boot-page" role="status" aria-label="Loading" aria-busy="true">
      <div class="boot-heading">
        <Skeleton width="min(60%, 15rem)" height="calc(var(--text-xl) * 1.2)" />
        <Skeleton shape="circle" size="2rem" />
      </div>

      <div v-if="home" class="boot-grid">
        <div class="boot-main">
          <section class="panel boot-card boot-balance">
            <div class="boot-card-heading"><p class="card-title">Balance</p></div>
            <Skeleton width="min(100%, 12.5rem)" height="calc(var(--text-3xl) * 1.1)" />
          </section>
          <section class="panel boot-card boot-transactions">
            <div class="boot-card-heading"><p class="card-title">Recent transactions</p></div>
            <SkeletonRows :rows="6" />
          </section>
          <section class="panel boot-card boot-recurring">
            <div class="boot-card-heading"><p class="card-title">Recurring</p></div>
            <SkeletonRows :rows="5" />
          </section>
        </div>
        <section class="panel boot-card boot-spending">
          <div class="boot-card-heading"><p class="card-title">Spending</p></div>
          <div class="boot-ring">
            <Skeleton class="boot-ring-skeleton" width="100%" height="100%" />
          </div>
        </section>
      </div>

      <div v-else class="boot-cards">
        <SkeletonCard height="12rem" />
        <SkeletonCard height="16rem" :lines="5" />
      </div>
    </main>
  </div>
</template>

<style scoped>
/* The page frame and Home's grid, as in HomePage. */
.app-shell {
  display: flex;
  align-items: stretch;
  height: 100svh;
}

.boot-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 1.25rem 1.5rem 1.5rem 0;
  overflow: hidden;
}

.boot-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  min-height: var(--app-control-height);
  margin-bottom: 1.125rem;
}

/* On the bare page, the card skeleton's grey would barely show. */
.boot-heading :deep(.p-skeleton) {
  background: rgb(11 11 11 / 7%);
}

.boot-grid {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(0, 1.2fr) minmax(18rem, 0.8fr);
  gap: 1rem;
  min-height: 0;
}

.boot-main,
.boot-cards {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  min-width: 0;
  min-height: 0;
}

.boot-cards {
  gap: 1.25rem;
}

.boot-card {
  padding: 1.125rem 1.25rem 1.25rem;
}

.boot-card-heading {
  display: flex;
  align-items: center;
  min-height: var(--app-control-height-sm);
}

.boot-transactions :deep(.skeleton-rows) {
  max-height: 18rem;
  overflow: hidden;
}

.boot-recurring :deep(.skeleton-rows) {
  max-height: 13.5rem;
  overflow: hidden;
}

.boot-spending {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  container-type: inline-size;
}

.boot-ring {
  width: min(22rem, 85cqi, 100%);
  aspect-ratio: 1;
  margin: 0.5rem auto 0;
}

.boot-ring-skeleton {
  border-radius: 50%;
  mask: radial-gradient(closest-side, transparent 76%, #000 76.5%);
}

.boot-balance {
  display: grid;
  gap: 0.75rem;
}

@media (max-width: 900px) {
  .app-shell {
    height: auto;
    min-height: 100svh;
  }

  .boot-page {
    padding: 1.25rem 1.25rem calc(var(--app-tabbar-height) + env(safe-area-inset-bottom) + 1.5rem);
  }

  .boot-heading {
    margin-bottom: 1.25rem;
  }

  .boot-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .boot-card {
    padding: 1.125rem 1.125rem 1.25rem;
  }

  .boot-ring {
    width: min(100%, 18rem);
  }

  /* Phones show the first five rows of each list in full, as Home does. */
  .boot-transactions :deep(.skeleton-rows),
  .boot-recurring :deep(.skeleton-rows) {
    max-height: none;
  }

  :deep(.skeleton-row:nth-child(n + 6)) {
    display: none;
  }
}
</style>
