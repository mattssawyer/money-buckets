<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import {
  createReconnectLinkToken,
  finishReconnect,
  getLinkedItems,
  type PlaidItem,
} from '../api/PlaidService'
import { closePlaidLink, openPlaidLink } from '../plaid/plaidLink'

const emit = defineEmits<{
  /** An item synced again after the user signed in to its bank. */
  reconnected: []
}>()

const items = ref<PlaidItem[]>([])
const reconnecting = ref<string | null>(null)
const failedItemId = ref<string | null>(null)
let disposed = false

const needing = computed(() => items.value.filter((item) => item.reconnect))

onMounted(load)

onUnmounted(() => {
  disposed = true
  closePlaidLink()
})

/** The page shows its own error if the items can't load, so this stays quiet. */
async function load() {
  try {
    const loaded = await getLinkedItems()
    if (!disposed) items.value = loaded
  } catch {
    // Nothing to show.
  }
}

function bank(item: PlaidItem) {
  return item.institution_name ?? 'One of your banks'
}

/** Opens Link in update mode; once the user signs in, the item syncs before the page reloads. */
async function reconnect(item: PlaidItem) {
  if (reconnecting.value) return
  reconnecting.value = item.item_id
  failedItemId.value = null
  try {
    const publicToken = await openPlaidLink(await createReconnectLinkToken(item.item_id))
    if (disposed || !publicToken) return
    await finishReconnect(item.item_id)
    if (disposed) return
    await load()
    emit('reconnected')
  } catch {
    if (!disposed) failedItemId.value = item.item_id
  } finally {
    if (!disposed) reconnecting.value = null
  }
}
</script>

<template>
  <div v-if="needing.length" class="reconnect-notices">
    <Message
      v-for="item in needing"
      :key="item.item_id"
      :severity="item.reconnect === 'LOGIN_REQUIRED' ? 'warn' : 'info'"
      role="alert"
    >
      <div class="notice-body">
        <p v-if="item.reconnect === 'LOGIN_REQUIRED'">
          {{ bank(item) }} needs you to sign in again. Its accounts won’t update until you do.
        </p>
        <p v-else>{{ bank(item) }} will disconnect soon. Sign in again to keep it updating.</p>
        <p v-if="failedItemId === item.item_id" class="notice-error">
          We couldn’t reconnect {{ bank(item) }}. Try again.
        </p>
        <div>
          <Button
            :label="reconnecting === item.item_id ? 'Reconnecting…' : 'Reconnect'"
            size="small"
            :loading="reconnecting === item.item_id"
            :disabled="reconnecting !== null"
            @click="reconnect(item)"
          />
        </div>
      </div>
    </Message>
  </div>
</template>

<style scoped>
.reconnect-notices {
  display: grid;
  gap: 0.75rem;
}

.notice-body {
  display: grid;
  gap: 0.75rem;
  line-height: 1.6;
}

.notice-error {
  color: var(--app-danger);
}
</style>
