<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import SignInPage from './views/SignInPage.vue'
import { setAccessTokenProvider } from './api/client'
import { useAuth } from './auth'
import { useOnboarding } from './onboarding'

const { getAccessToken, isSignedIn, isLoaded } = useAuth()
const onboarding = useOnboarding()
const route = useRoute()
const router = useRouter()
// The first route isn't known until the router resolves it, so a public page doesn't flash
// the sign-in page first.
const routerReady = ref(false)
router.isReady().then(() => (routerReady.value = true))
// Whether the signed-in user still needs onboarding is known, so Home doesn't flash first.
const onboardingChecked = ref(false)

setAccessTokenProvider(getAccessToken)

const removeGuard = router.beforeEach((to) => onboarding.redirect(to) ?? true)
onUnmounted(removeGuard)

watch(
  isSignedIn,
  async (signedIn) => {
    if (!signedIn) return
    await Promise.all([onboarding.load(), router.isReady()])
    const target = onboarding.redirect(route)
    if (target) await router.replace(target)
    onboardingChecked.value = true
  },
  { immediate: true },
)
</script>

<template>
  <RouterView v-if="routerReady && route.meta.public" />
  <template v-else-if="isLoaded && routerReady">
    <SignInPage v-if="!isSignedIn" />
    <RouterView v-else-if="onboardingChecked" />
    <main v-else class="session-loading" role="status" aria-live="polite">Loading…</main>
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
