<script setup lang="ts">
import { computed, ref } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { removeItem, type PlaidItem } from '../api/PlaidService'

const props = defineProps<{
  /** The user's older items at the institution they just linked again. */
  items: PlaidItem[]
}>()

const emit = defineEmits<{
  removed: []
  dismiss: []
}>()

const removing = ref(false)
const failed = ref(false)
// A retry skips items already removed, which the server no longer knows.
const removed = new Set<string>()

const institution = computed(
  () => props.items.find((item) => item.institution_name)?.institution_name ?? 'this institution',
)

/**
 * Removes older items in order, skipping completed removals on retry. A failure shows an error;
 * the removed event is emitted only after all succeed.
 */
async function removeOlder() {
  removing.value = true
  failed.value = false
  try {
    for (const item of props.items) {
      if (removed.has(item.item_id)) continue
      await removeItem(item.item_id)
      removed.add(item.item_id)
    }
    emit('removed')
  } catch {
    failed.value = true
  } finally {
    removing.value = false
  }
}
</script>

<template>
  <Message severity="warn" class="same-institution" role="status">
    <div class="notice-body">
      <p>
        You already had {{ institution }} connected, so its accounts are now here twice and net
        worth counts them twice. Removing the older connection keeps your history.
      </p>
      <p v-if="failed" class="notice-error">We couldn’t remove the older connection. Try again.</p>
      <div class="notice-actions">
        <Button
          label="Remove the older connection"
          size="small"
          :loading="removing"
          :disabled="removing"
          @click="removeOlder"
        />
        <Button
          label="Keep both"
          size="small"
          severity="secondary"
          :disabled="removing"
          @click="emit('dismiss')"
        />
      </div>
    </div>
  </Message>
</template>

<style scoped>
.notice-body {
  display: grid;
  gap: 0.75rem;
  line-height: 1.6;
}

.notice-error {
  color: var(--app-danger);
}

.notice-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
</style>
