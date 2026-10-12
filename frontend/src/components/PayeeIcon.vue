<script setup lang="ts">
import { computed, ref, watch, type Component } from 'vue'
import {
  ArrowLeftRight,
  Banknote,
  Building2,
  Car,
  Clapperboard,
  Hammer,
  House,
  Landmark,
  Plane,
  Receipt,
  Scissors,
  ShoppingBag,
  Stethoscope,
  Utensils,
  Wrench,
} from '@lucide/vue'

/** Plaid's primary categories, each drawn as what the money went on. */
const CATEGORY_ICONS: Record<string, Component> = {
  BANK_FEES: Landmark,
  ENTERTAINMENT: Clapperboard,
  FOOD_AND_DRINK: Utensils,
  GENERAL_MERCHANDISE: ShoppingBag,
  GENERAL_SERVICES: Wrench,
  GOVERNMENT_AND_NON_PROFIT: Building2,
  HOME_IMPROVEMENT: Hammer,
  INCOME: Banknote,
  LOAN_PAYMENTS: Landmark,
  MEDICAL: Stethoscope,
  PERSONAL_CARE: Scissors,
  RENT_AND_UTILITIES: House,
  TRANSFER_IN: ArrowLeftRight,
  TRANSFER_OUT: ArrowLeftRight,
  TRANSPORTATION: Car,
  TRAVEL: Plane,
}

/**
 * A payee's logo, or when Plaid has none (or it fails to load), an icon for the payee's
 * category. Sized by the parent through width and height.
 */
const props = defineProps<{
  logoUrl?: string | null
  category?: string | null
}>()

const failed = ref(false)
watch(
  () => props.logoUrl,
  () => (failed.value = false),
)

const icon = computed(() => (props.category && CATEGORY_ICONS[props.category]) || Receipt)
</script>

<template>
  <img v-if="logoUrl && !failed" class="payee-icon" :src="logoUrl" alt="" @error="failed = true" />
  <span v-else class="payee-icon payee-icon-fallback" aria-hidden="true">
    <component :is="icon" :stroke-width="1.75" />
  </span>
</template>

<style scoped>
.payee-icon {
  flex: none;
  display: grid;
  width: 2rem;
  height: 2rem;
  place-items: center;
  object-fit: cover;
  border-radius: 50%;
}

.payee-icon-fallback {
  color: var(--app-text-secondary);
  background: var(--app-inset);
}

.payee-icon-fallback :deep(svg) {
  width: 50%;
  height: 50%;
}
</style>
