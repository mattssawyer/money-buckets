<script setup lang="ts">
import { Landmark } from '@lucide/vue'
import { computed, onUnmounted, ref } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import InfoTip from './InfoTip.vue'
import { addInvestments, type PlaidItem } from '../api/PlaidService'
import { openPlaidLink } from '../plaid/plaidLink'

/**
 * The user's connections that may also hold investments, each with a button to add them, so the
 * user doesn't link the same institution twice.
 */
const props = defineProps<{
  /** Every linked item; the ones that already have investments or can't offer any are left out. */
  items: PlaidItem[]
}>()

const emit = defineEmits<{
  /** An item's investments were added, or it turned out to offer none; its details changed. */
  added: []
}>()

const addingItemId = ref<string>()
const addError = ref('')
// Outlives the connection it's about, which leaves the list once it's known not to offer investments.
const addNote = ref('')
let disposed = false

// Unknown availability still shows; trying finds out, and banks without investments drop off.
const itemsWithoutInvestments = computed(() =>
  props.items.filter((item) => !item.investments && item.investments_available !== false),
)

onUnmounted(() => (disposed = true))

/**
 * Retries with Link update mode if the holdings check fails, then tells the page to reload. A bank that
 * doesn't offer investments leaves the list with a note saying so.
 */
async function addInvestmentsTo(item: PlaidItem) {
  if (addingItemId.value) return
  addingItemId.value = item.item_id
  addError.value = ''
  addNote.value = ''
  const institution = item.institution_name ?? 'that institution'
  try {
    let result = await addInvestments(item.item_id)
    if (result.outcome === 'needs_consent' && result.link_token) {
      // Plaid needs the user's consent first; update mode asks for it on the same connection.
      const finished = await openPlaidLink(result.link_token)
      if (disposed || !finished) return
      result = await addInvestments(item.item_id)
    }
    if (disposed) return
    if (result.outcome === 'not_offered') {
      addNote.value = `${item.institution_name ?? 'That institution'} doesn’t offer investment accounts through Plaid.`
    } else if (result.outcome !== 'added') {
      addError.value = `${item.institution_name ?? 'That institution'} didn’t share any investment accounts.`
      return
    }
    emit('added')
  } catch {
    if (!disposed)
      addError.value = `We couldn’t add investments from ${institution}. Please try again.`
  } finally {
    if (!disposed) addingItemId.value = undefined
  }
}
</script>

<template>
  <Message v-if="addNote && !itemsWithoutInvestments.length" severity="secondary">
    {{ addNote }}
  </Message>

  <section
    v-if="itemsWithoutInvestments.length"
    class="panel connections"
    aria-labelledby="connections-heading"
  >
    <div class="connections-heading">
      <h2 id="connections-heading" class="section-heading">Already connected</h2>
      <InfoTip
        label="About adding investments"
        text="Add investments to a connection you already have instead of linking it again. Linking the same institution twice counts its accounts twice."
      />
    </div>
    <Message v-if="addError" severity="error" size="small">{{ addError }}</Message>
    <Message v-else-if="addNote" severity="secondary" size="small">{{ addNote }}</Message>
    <ul class="connection-list">
      <li v-for="item in itemsWithoutInvestments" :key="item.item_id" class="connection">
        <span class="connection-name">
          <Landmark :size="16" :stroke-width="1.75" aria-hidden="true" />
          {{ item.institution_name ?? 'Connected institution' }}
        </span>
        <Button
          label="Add investments"
          size="small"
          severity="secondary"
          :loading="addingItemId === item.item_id"
          :disabled="addingItemId != null"
          @click="addInvestmentsTo(item)"
        />
      </li>
    </ul>
  </section>
</template>

<style scoped>
.connections {
  display: grid;
  gap: 0.75rem;
  padding: 1.125rem 1.25rem;
}

.connections-heading {
  display: flex;
  align-items: center;
  gap: 0.35rem;
}

.connection-list {
  display: grid;
  padding: 0;
  list-style: none;
}

.connection {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.625rem 0;
  border-top: 1px solid var(--app-divider);
}

.connection-name {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-weight: 500;
}

.section-heading {
  color: var(--app-text-secondary);
  font-size: 0.875rem;
  font-weight: 500;
  letter-spacing: 0;
}
</style>
