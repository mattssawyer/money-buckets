<script setup lang="ts">
import Skeleton from 'primevue/skeleton'

const { rows = 4 } = defineProps<{ rows?: number }>()

// Names and amounts vary in length, so the placeholders do too.
const NAME_WIDTHS = ['9rem', '6.5rem', '11rem', '7.5rem', '8.5rem', '10rem']
const AMOUNT_WIDTHS = ['3.5rem', '4.25rem', '3rem', '4rem']
</script>

<template>
  <!-- Shaped and sized like a list of transactions, so the real rows land without moving. -->
  <ul class="skeleton-rows" aria-hidden="true">
    <li v-for="row in rows" :key="row" class="skeleton-row">
      <Skeleton shape="circle" size="2rem" />
      <span class="skeleton-details">
        <Skeleton :width="NAME_WIDTHS[(row - 1) % NAME_WIDTHS.length]" height="0.75rem" />
        <Skeleton width="4.5rem" height="0.5625rem" />
      </span>
      <Skeleton
        class="skeleton-amount"
        :width="AMOUNT_WIDTHS[(row - 1) % AMOUNT_WIDTHS.length]"
        height="0.75rem"
      />
    </li>
  </ul>
</template>

<style scoped>
.skeleton-rows {
  display: grid;
  margin: 1rem 0 0;
  padding: 0;
  list-style: none;
}

.skeleton-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-height: 3.25rem;
  padding: 0.625rem 0;
}

.skeleton-row:not(:last-child)::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  left: 2.75rem;
  height: 1px;
  background: var(--app-divider);
}

.skeleton-details {
  display: grid;
  gap: 0.4375rem;
}

.skeleton-amount {
  margin-left: auto;
}

@media (max-width: 640px) {
  .skeleton-row {
    min-height: 3.5rem;
  }
}
</style>
