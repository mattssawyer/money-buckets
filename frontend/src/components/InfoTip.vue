<script setup lang="ts">
import { Info } from '@lucide/vue'
import { nextTick, onBeforeUnmount, ref, useId, useTemplateRef } from 'vue'

/**
 * An explanation kept out of the way behind an info icon. It shows on hover or keyboard focus,
 * and screen readers read it as the button's description.
 */
defineProps<{
  /** What the button is about, for screen readers, e.g. "About your spending". */
  label: string
  text: string
}>()

/** How far the bubble keeps from the edges of the window. */
const EDGE = 8

const id = useId()
const button = useTemplateRef('button')
const bubble = useTemplateRef('bubble')
const open = ref(false)
const position = ref({ top: 0, left: 0 })

/**
 * The bubble sits on the page itself rather than inside the icon's panel, so a dialog or a
 * scrolling panel can't cut it off. It opens below the icon and moves left to stay in view.
 */
async function show() {
  open.value = true
  await nextTick()
  if (!button.value || !bubble.value) return
  const anchor = button.value.getBoundingClientRect()
  const width = bubble.value.offsetWidth
  position.value = {
    top: anchor.bottom + 6,
    left: Math.max(EDGE, Math.min(anchor.left - EDGE, window.innerWidth - width - EDGE)),
  }
  // Scrolling would leave it behind, so it closes instead.
  window.addEventListener('scroll', hide, { capture: true, once: true })
}

function hide() {
  open.value = false
  window.removeEventListener('scroll', hide, { capture: true })
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') hide()
}

onBeforeUnmount(hide)
</script>

<template>
  <span class="info-tip" @mouseenter="show" @mouseleave="hide">
    <button
      ref="button"
      type="button"
      class="info-tip-button"
      :aria-label="label"
      :aria-describedby="id"
      @focus="show"
      @blur="hide"
      @keydown="onKeydown"
    >
      <Info :size="14" :stroke-width="1.75" aria-hidden="true" />
    </button>
    <Teleport to="body">
      <span
        :id="id"
        ref="bubble"
        role="tooltip"
        class="info-tip-bubble"
        :class="{ 'info-tip-open': open }"
        :style="{ top: `${position.top}px`, left: `${position.left}px` }"
      >
        {{ text }}
      </span>
    </Teleport>
  </span>
</template>

<style scoped>
.info-tip {
  display: inline-flex;
  vertical-align: middle;
}

.info-tip-button {
  display: grid;
  width: 1.25rem;
  height: 1.25rem;
  padding: 0;
  place-items: center;
  color: var(--app-text-subdued);
  background: transparent;
  border: 0;
  border-radius: 50%;
  cursor: help;
}

.info-tip-button:hover,
.info-tip-button:focus-visible {
  color: var(--app-text);
}

.info-tip-bubble {
  position: fixed;
  /* Above PrimeVue's dialogs, which stack from 1100. */
  z-index: 3000;
  width: max-content;
  max-width: min(16rem, calc(100vw - 1rem));
  padding: 0.5rem 0.65rem;
  color: var(--app-text-inverse);
  background: var(--app-text);
  border-radius: var(--app-radius-chip);
  box-shadow: var(--app-shadow-sm);
  font-family: var(--app-font);
  font-size: 0.8125rem;
  font-weight: 400;
  letter-spacing: normal;
  line-height: 1.45;
  text-align: left;
  white-space: normal;
  pointer-events: none;
  visibility: hidden;
  opacity: 0;
  transition: opacity 120ms ease;
}

.info-tip-open {
  visibility: visible;
  opacity: 1;
}
</style>
