<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import { useAuth } from '../auth'

const { user, signOut } = useAuth()
const router = useRouter()
const menu = ref<InstanceType<typeof Menu>>()

const initial = computed(() =>
  (user.value?.firstName || user.value?.email || '?').charAt(0).toUpperCase(),
)
const label = computed(() => user.value?.email ?? 'Your account')

const items: MenuItem[] = [
  { label: 'Settings', command: () => router.push('/settings') },
  { label: 'Sign out', command: signOut },
]
</script>

<template>
  <button
    type="button"
    class="user-menu-button"
    :aria-label="`${label}: account menu`"
    aria-haspopup="true"
    aria-controls="user-menu"
    @click="menu?.toggle($event)"
  >
    <span aria-hidden="true">{{ initial }}</span>
  </button>
  <Menu id="user-menu" ref="menu" :model="items" popup />
</template>

<style scoped>
.user-menu-button {
  display: grid;
  flex: none;
  width: 2rem;
  height: 2rem;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: var(--app-text);
  color: var(--app-text-inverse);
  font: inherit;
  font-size: 0.875rem;
  font-weight: 550;
  cursor: pointer;
}

.user-menu-button:focus-visible {
  outline: 2px solid var(--app-ring);
  outline-offset: 2px;
}
</style>
