<script setup lang="ts">
import { computed } from 'vue'
import SameInstitutionNotice from './SameInstitutionNotice.vue'
import type { PlaidItem } from '../api/PlaidService'

const props = defineProps<{
  /** The user's older items at the institutions they just linked again; may span several. */
  items: PlaidItem[]
}>()

const emit = defineEmits<{
  /** The older items at one institution were removed. */
  removed: [itemIds: string[]]
  /** The user chose to keep both connections at one institution. */
  dismiss: [itemIds: string[]]
}>()

/** One group per institution, so each gets its own warning and removal. */
const groups = computed(() => {
  const byInstitution = new Map<string, PlaidItem[]>()
  for (const item of props.items) {
    const key = item.institution_id ?? item.institution_name ?? item.item_id
    const group = byInstitution.get(key)
    if (group) group.push(item)
    else byInstitution.set(key, [item])
  }
  return [...byInstitution].map(([key, items]) => ({ key, items }))
})

function ids(items: PlaidItem[]) {
  return items.map((item) => item.item_id)
}
</script>

<template>
  <div v-if="groups.length" class="same-institution-notices">
    <SameInstitutionNotice
      v-for="group in groups"
      :key="group.key"
      :items="group.items"
      @removed="emit('removed', ids(group.items))"
      @dismiss="emit('dismiss', ids(group.items))"
    />
  </div>
</template>

<style scoped>
.same-institution-notices {
  display: grid;
  gap: 0.75rem;
}
</style>
