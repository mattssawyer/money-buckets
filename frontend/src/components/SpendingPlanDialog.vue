<script setup lang="ts">
import Dialog from 'primevue/dialog'
import SpendingPlanSetup from './SpendingPlanSetup.vue'
import type { SavedPlan } from '../spendingPlan/savedPlan'

/** Plan setup in a dialog that takes up most of the screen, which it needs. */
defineProps<{
  header: string
  /** Edit this saved plan. Without it, the dialog starts a new plan. */
  saved?: SavedPlan
  /** A new plan will replace the saved one. */
  replacing?: boolean
  /** Start on the buffer's percent, for changing just that. */
  focusBuffer?: boolean
}>()

const emit = defineEmits<{
  saved: [plan: SavedPlan]
}>()

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    dismissable-mask
    block-scroll
    :header="header"
    :draggable="false"
    class="app-sheet"
    :style="{ width: 'min(76rem, calc(100vw - 2rem))', height: '88dvh', maxHeight: '92dvh' }"
    :breakpoints="{ '640px': '100vw' }"
    :pt="{
      content: { style: { display: 'flex', flex: '1', minHeight: '0', padding: '0' } },
    }"
  >
    <!-- A new plan and an edit each start fresh rather than carrying over the other's changes. -->
    <SpendingPlanSetup
      :key="saved ? 'edit' : 'setup'"
      :saved="saved"
      :replacing="replacing"
      :focus-buffer="focusBuffer"
      @saved="(plan) => emit('saved', plan)"
    />
  </Dialog>
</template>
