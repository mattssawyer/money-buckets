import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from '../App.vue'

const auth = vi.hoisted(() => ({ isLoaded: false, isSignedIn: false }))

vi.mock('@clerk/vue', async () => {
  const { ref } = await import('vue')
  return {
    useAuth: () => ({
      getToken: ref(async () => null),
      isLoaded: ref(auth.isLoaded),
      isSignedIn: ref(auth.isSignedIn),
    }),
  }
})

vi.mock('../api/client', () => ({ setAccessTokenProvider: vi.fn() }))

vi.mock('../views/SignInPage.vue', () => ({ default: { template: '<p>Sign in page</p>' } }))

enableAutoUnmount(afterEach)

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<p>Home</p>' } },
      { path: '/privacy', component: { template: '<p>Privacy</p>' }, meta: { public: true } },
    ],
  })
  await router.push(path)
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('app', () => {
  it('shows a public page to someone signed out, before Clerk loads', async () => {
    auth.isLoaded = false
    auth.isSignedIn = false

    expect((await mountAt('/privacy')).text()).toBe('Privacy')
  })

  it('asks someone signed out to sign in everywhere else', async () => {
    auth.isLoaded = true
    auth.isSignedIn = false

    expect((await mountAt('/')).text()).toBe('Sign in page')
  })

  it('shows the app to someone signed in', async () => {
    auth.isLoaded = true
    auth.isSignedIn = true

    expect((await mountAt('/')).text()).toBe('Home')
  })
})
