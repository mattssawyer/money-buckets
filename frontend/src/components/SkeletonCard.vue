<script setup lang="ts">
import Skeleton from 'primevue/skeleton'

const { height, lines = 3 } = defineProps<{ height: string; lines?: number }>()

const LINE_WIDTHS = ['72%', '54%', '64%', '40%', '58%']
</script>

<template>
  <!-- A card's outline with a title and a few lines, standing in for a card that's loading. -->
  <div class="panel skeleton-card" :style="{ height }" aria-hidden="true">
    <Skeleton width="8rem" height="0.875rem" />
    <div class="skeleton-lines">
      <Skeleton
        v-for="line in lines"
        :key="line"
        :width="LINE_WIDTHS[(line - 1) % LINE_WIDTHS.length]"
        height="0.75rem"
      />
    </div>
  </div>
</template>

<style scoped>
.skeleton-card {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  overflow: hidden;
  padding: 1.125rem 1.25rem 1.25rem;
}

.skeleton-lines {
  display: grid;
  gap: 0.875rem;
}
</style>
