<script setup lang="ts">
import { computed } from 'vue'
import { BUCKET_STYLES } from '../api/plaidLabels'
import { formatWholeDollars } from '../money'
import InfoTip from './InfoTip.vue'
import type { GuiltFreeLeft } from '../spendingPlan/guiltFreeLeft'

/** How much of the plan's guilt-free spending a month has used, along the foot of its card. */
const props = defineProps<{
  guiltFree: GuiltFreeLeft
  /** Only one account is shown above, but the plan covers every tracked account. */
  allAccounts: boolean
  /** The ring above already says what's left, so this line says how much of it is spent. */
  leftShownAbove?: boolean
  /** Why the number may still change, such as spending that isn't sorted yet. */
  note?: string
}>()

const over = computed(() => props.guiltFree.left < 0)
const spentPercent = computed(() => {
  const { budget, spent } = props.guiltFree
  if (budget > 0) return Math.min(100, (spent / budget) * 100)
  return spent > 0 ? 100 : 0
})
const summary = computed(() => {
  const { budget, spent, left } = props.guiltFree
  if (props.leftShownAbove) {
    // A plan whose other buckets use up all its income leaves nothing to measure against.
    if (budget <= 0) return `${formatWholeDollars(spent)} spent, none planned`
    return `${formatWholeDollars(spent)} of ${formatWholeDollars(budget)} spent`
  }
  return `${formatWholeDollars(Math.abs(left))} ${over.value ? 'over' : 'left'}`
})
</script>

<template>
  <section
    class="guilt-free-progress"
    :class="{ 'guilt-free-over': over }"
    aria-labelledby="guilt-free-progress-title"
  >
    <div class="guilt-free-heading">
      <span class="guilt-free-title-row">
        <h3 id="guilt-free-progress-title" class="guilt-free-title">
          Guilt-free spending<template v-if="allAccounts"> · all accounts</template>
        </h3>
        <InfoTip
          label="About guilt-free spending"
          text="What your plan leaves to spend freely each month, minus what you've spent from it."
        />
      </span>
      <span class="guilt-free-summary">{{ summary }}</span>
    </div>
    <div class="guilt-free-bar" aria-hidden="true">
      <div
        class="guilt-free-bar-fill"
        :style="{
          width: `${spentPercent}%`,
          backgroundColor: over ? undefined : BUCKET_STYLES.GUILT_FREE.color,
        }"
      />
    </div>
    <p v-if="note" class="guilt-free-note">{{ note }}</p>
  </section>
</template>

<style scoped>
/* Pinned to the foot of the card, below whatever the legend shows. */
.guilt-free-progress {
  display: grid;
  flex: none;
  gap: 0.5rem;
  margin-top: auto;
  padding-top: 1rem;
  border-top: 1px solid var(--app-divider);
}

.guilt-free-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
}

.guilt-free-title-row {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  min-width: 0;
}

.guilt-free-title {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--text-sm);
  font-weight: 500;
  letter-spacing: normal;
}

.guilt-free-summary {
  color: var(--app-text);
  font-size: var(--text-base);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.guilt-free-bar {
  height: 0.375rem;
  overflow: hidden;
  background: var(--app-inset);
  border-radius: 999px;
}

.guilt-free-bar-fill {
  height: 100%;
  border-radius: inherit;
}

.guilt-free-note {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--text-xs);
}

.guilt-free-over .guilt-free-bar-fill {
  background: var(--app-over-target);
}

.guilt-free-over .guilt-free-summary {
  color: var(--app-over-target);
}
</style>
