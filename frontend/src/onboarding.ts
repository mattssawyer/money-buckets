import { readonly, ref } from 'vue'
import type { RouteLocationNormalized, RouteLocationRaw } from 'vue-router'
import { finishOnboarding, getCurrentUser } from './api/UserService'

/** Null until the server says; stays null if it can't, so nobody is kept from the app. */
const onboarded = ref<boolean | null>(null)

/**
 * Waits before each try. Right after the page loads, AuthKit can have no access token yet while it
 * refreshes the session, so the first request goes out signed out and is turned away.
 */
const RETRY_DELAYS_MS = [0, 300, 1000]

/** Asks the server whether the signed-in user still needs onboarding. */
async function load(): Promise<void> {
  for (const delay of RETRY_DELAYS_MS) {
    if (delay) await new Promise((resolve) => setTimeout(resolve, delay))
    try {
      onboarded.value = (await getCurrentUser()).onboarded
      return
    } catch {
      // Tried again below, or left unknown.
    }
  }
  onboarded.value = null
}

async function finish(): Promise<void> {
  onboarded.value = (await finishOnboarding()).onboarded
}

/**
 * Where to send someone instead: a new user opens on onboarding rather than an empty Home, and
 * someone who has finished it doesn't land on it again. Other pages stay open either way.
 */
function redirect(to: RouteLocationNormalized): RouteLocationRaw | undefined {
  if (onboarded.value === false && to.name === 'home') return { name: 'welcome' }
  if (onboarded.value === true && to.name === 'welcome') return { name: 'home' }
  return undefined
}

export function useOnboarding() {
  return { onboarded: readonly(onboarded), load, finish, redirect }
}
