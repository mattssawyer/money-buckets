import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { createMemoryHistory, createRouter } from 'vue-router'

const auth = vi.hoisted(() => ({
  hasInvitation: vi.fn<() => boolean>(),
  signIn: vi.fn(),
  signUp: vi.fn(),
}))

vi.mock('../auth', async () => {
  const { ref } = await import('vue')
  return { useAuth: () => ({ ...auth, isLoaded: ref(true) }) }
})

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.clearAllMocks()
  auth.hasInvitation.mockReturnValue(false)
})

afterEach(() => {
  vi.unstubAllEnvs()
})

async function click(label: string) {
  vi.resetModules()
  const { default: SignInPage } = await import('../views/SignInPage.vue')
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: {} }],
  })
  const wrapper = mount(SignInPage, { global: { plugins: [router, PrimeVue] } })
  const button = wrapper.findAll('button').find((b) => b.text() === label)!
  await button.trigger('click')
  await flushPromises()
  return wrapper
}

describe('sign-in page', () => {
  it('sends people to WorkOS to create an account when sign-up is open', async () => {
    const wrapper = await click('Create an account')

    expect(auth.signUp).toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('invite-only')
  })

  it('explains that an invitation is needed when sign-up is invite-only', async () => {
    vi.stubEnv('VITE_INVITE_ONLY', 'true')

    const wrapper = await click('Create an account')

    expect(auth.signUp).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Money Buckets is invite-only right now.')
  })

  it('sends someone with an invitation to WorkOS when sign-up is invite-only', async () => {
    vi.stubEnv('VITE_INVITE_ONLY', 'true')
    auth.hasInvitation.mockReturnValue(true)

    await click('Create an account')

    expect(auth.signUp).toHaveBeenCalled()
  })
})
