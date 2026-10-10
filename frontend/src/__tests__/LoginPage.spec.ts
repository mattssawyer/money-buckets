import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginPage from '../views/LoginPage.vue'

const auth = vi.hoisted(() => ({ isSignedIn: false, signIn: vi.fn() }))

vi.mock('../auth', async () => {
  const { ref } = await import('vue')
  return {
    useAuth: () => ({
      isLoaded: ref(true),
      isSignedIn: ref(auth.isSignedIn),
      signIn: auth.signIn,
    }),
  }
})

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.clearAllMocks()
})

async function open() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<p>Home</p>' } },
      { path: '/login', component: LoginPage },
    ],
  })
  await router.push('/login')
  mount(LoginPage, { global: { plugins: [router] } })
  await flushPromises()
  return router
}

describe('login page', () => {
  it('goes straight to WorkOS to sign in, so an invitation started there carries on', async () => {
    auth.isSignedIn = false

    await open()

    expect(auth.signIn).toHaveBeenCalled()
  })

  it('goes home when already signed in', async () => {
    auth.isSignedIn = true

    const router = await open()

    expect(auth.signIn).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/')
  })
})
