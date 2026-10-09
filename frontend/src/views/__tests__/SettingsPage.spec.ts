import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { AxiosError, AxiosHeaders } from 'axios'
import PrimeVue from 'primevue/config'
import { computed } from 'vue'
import SettingsPage from '../SettingsPage.vue'
import { deleteCurrentUser } from '../../api/UserService'

const auth = vi.hoisted(() => ({ signOut: vi.fn<() => void>() }))

vi.mock('../../auth', () => ({
  useAuth: () => ({
    user: computed(() => ({ email: 'ada@example.com' })),
    signOut: auth.signOut,
  }),
}))
vi.mock('../../components/UserMenu.vue', () => ({ default: { template: '<div />' } }))

vi.mock('../../api/UserService', () => ({
  deleteCurrentUser: vi.fn(),
}))

enableAutoUnmount(afterEach)

function mountPage() {
  return mount(SettingsPage, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      stubs: {
        AppSidebar: true,
        teleport: true,
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
      },
    },
  })
}

function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAll('button').find((element) => element.text() === label)
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}

async function openAndType(wrapper: VueWrapper, text: string) {
  await button(wrapper, 'Delete…').trigger('click')
  await wrapper.find('#delete-confirmation').setValue(text)
}

function failure(status: number) {
  return new AxiosError('failed', undefined, undefined, undefined, {
    status,
    statusText: '',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: {},
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(deleteCurrentUser).mockResolvedValue()
})

describe('settings page', () => {
  it('shows the email the user signs in with', () => {
    expect(mountPage().text()).toContain('ada@example.com')
  })

  it('signs out', async () => {
    const wrapper = mountPage()

    await button(wrapper, 'Sign out').trigger('click')

    expect(auth.signOut).toHaveBeenCalled()
  })

  it('deletes nothing until the user types delete', async () => {
    const wrapper = mountPage()
    await openAndType(wrapper, 'delet')

    expect(button(wrapper, 'Delete everything').attributes('disabled')).toBeDefined()
    await wrapper.find('form').trigger('submit')
    expect(deleteCurrentUser).not.toHaveBeenCalled()
  })

  it('deletes the account and signs out once confirmed', async () => {
    const wrapper = mountPage()
    await openAndType(wrapper, 'Delete ')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(deleteCurrentUser).toHaveBeenCalledTimes(1)
    expect(auth.signOut).toHaveBeenCalled()
  })

  it('stays signed in and lets the user try again when deleting fails', async () => {
    vi.mocked(deleteCurrentUser).mockRejectedValue(failure(502))
    const wrapper = mountPage()
    await openAndType(wrapper, 'delete')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('We couldn’t finish deleting your account. Try again.')
    expect(auth.signOut).not.toHaveBeenCalled()
    expect(button(wrapper, 'Delete everything').attributes('disabled')).toBeUndefined()
  })

  it('says when this server can’t delete accounts', async () => {
    vi.mocked(deleteCurrentUser).mockRejectedValue(failure(503))
    const wrapper = mountPage()
    await openAndType(wrapper, 'delete')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Deleting accounts isn’t set up on this server.')
  })
})
