<script setup lang="ts">
import { watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../auth'

const { isLoaded, isSignedIn, signIn } = useAuth()
const router = useRouter()

// WorkOS's Initiate login URI. A sign-in that starts at WorkOS, such as an invitation email's
// link, comes here and has to go straight back to WorkOS, which keeps the invitation.
watch(
  isLoaded,
  (loaded) => {
    if (!loaded) return
    if (isSignedIn.value) void router.replace('/')
    else void signIn()
  },
  { immediate: true },
)
</script>

<template>
  <main class="login-redirect" role="status" aria-live="polite">Loading…</main>
</template>

<style scoped>
.login-redirect {
  display: grid;
  min-height: 100svh;
  place-items: center;
  color: var(--app-text-secondary);
}
</style>
