<script setup lang="ts">
import { ref } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import SignInPage from './views/SignInPage.vue'
import { setAccessTokenProvider } from './api/client'
import { useAuth } from './auth'

const { getAccessToken, isSignedIn, isLoaded } = useAuth()
const route = useRoute()
// The first route isn't known until the router resolves it, so a public page doesn't flash
// the sign-in page first.
const routerReady = ref(false)
useRouter()
  .isReady()
  .then(() => (routerReady.value = true))

setAccessTokenProvider(getAccessToken)
</script>

<template>
  <RouterView v-if="routerReady && route.meta.public" />
  <template v-else-if="isLoaded && routerReady">
    <SignInPage v-if="!isSignedIn" />
    <RouterView v-else />
  </template>
  <main v-else class="session-loading" role="status" aria-live="polite">Loading…</main>
</template>

<style scoped>
.session-loading {
  display: grid;
  min-height: 100svh;
  place-items: center;
  color: var(--app-text-secondary);
}
</style>
