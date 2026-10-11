import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from '../App.vue'
import { getCurrentUser } from '../api/UserService'

const auth = vi.hoisted(() => ({ isLoaded: false, isSignedIn: false }))

vi.mock('../auth', async () => {
  const { ref } = await import('vue')
  return {
    useAuth: () => ({
      getAccessToken: async () => null,
      isLoaded: ref(auth.isLoaded),
      isSignedIn: ref(auth.isSignedIn),
    }),
  }
})

vi.mock('../api/client', () => ({ setAccessTokenProvider: vi.fn() }))

vi.mock('../api/UserService', () => ({
  getCurrentUser: vi.fn<() => Promise<{ onboarded: boolean }>>(),
  finishOnboarding: vi.fn<() => Promise<{ onboarded: boolean }>>(),
}))

vi.mock('../views/SignInPage.vue', () => ({ default: { template: '<p>Sign in page</p>' } }))

enableAutoUnmount(afterEach)

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<p>Home</p>' } },
      { path: '/welcome', name: 'welcome', component: { template: '<p>Welcome</p>' } },
      { path: '/accounts', name: 'accounts', component: { template: '<p>Accounts</p>' } },
      { path: '/privacy', component: { template: '<p>Privacy</p>' }, meta: { public: true } },
    ],
  })
  await router.push(path)
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  return Object.assign(wrapper, { router })
}

beforeEach(() => {
  vi.mocked(getCurrentUser).mockResolvedValue({ onboarded: true })
})

describe('app', () => {
  it('shows a public page to someone signed out, before the sign-in loads', async () => {
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

  it('opens onboarding instead of Home for someone who hasn’t finished it', async () => {
    auth.isLoaded = true
    auth.isSignedIn = true
    vi.mocked(getCurrentUser).mockResolvedValue({ onboarded: false })

    const wrapper = await mountAt('/')

    expect(wrapper.text()).toBe('Welcome')
    await wrapper.router.push('/')
    await flushPromises()
    expect(wrapper.text()).toBe('Welcome')
  })

  it('leaves other pages open during onboarding', async () => {
    auth.isLoaded = true
    auth.isSignedIn = true
    vi.mocked(getCurrentUser).mockResolvedValue({ onboarded: false })

    expect((await mountAt('/accounts')).text()).toBe('Accounts')
  })

  it('sends someone who has finished onboarding on to Home', async () => {
    auth.isLoaded = true
    auth.isSignedIn = true

    expect((await mountAt('/welcome')).text()).toBe('Home')
  })

  it('shows Home when it can’t tell whether onboarding is done', async () => {
    auth.isLoaded = true
    auth.isSignedIn = true
    vi.mocked(getCurrentUser).mockRejectedValue(new Error('Server unavailable'))

    const wrapper = await mountAt('/')
    await settleRetries()

    expect(getCurrentUser).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toBe('Home')
  })

  it('asks again when the first check is turned away, as right after the page loads', async () => {
    auth.isLoaded = true
    auth.isSignedIn = true
    vi.mocked(getCurrentUser)
      .mockRejectedValueOnce(new Error('Request failed with status code 401'))
      .mockResolvedValue({ onboarded: false })

    const wrapper = await mountAt('/')
    await settleRetries()

    expect(wrapper.text()).toBe('Welcome')
  })
})

/** Waits out the onboarding check's retries. */
async function settleRetries() {
  await new Promise((resolve) => setTimeout(resolve, 1400))
  await flushPromises()
}
