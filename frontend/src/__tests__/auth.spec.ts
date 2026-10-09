import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const workos = vi.hoisted(() => {
  class LoginRequiredError extends Error {}
  const client = {
    getUser: vi.fn<() => { email: string; firstName: string | null } | null>(),
    getAccessToken: vi.fn<() => Promise<string>>(),
    signIn: vi.fn<(options?: { invitationToken?: string }) => Promise<void>>(),
    signUp: vi.fn<() => Promise<void>>(),
    signOut: vi.fn<(options?: { returnTo?: string }) => void>(),
  }
  return {
    LoginRequiredError,
    client,
    createClient: vi.fn<(clientId: string, options: object) => Promise<typeof client>>(
      async () => client,
    ),
  }
})

vi.mock('@workos-inc/authkit-js', () => ({
  createClient: workos.createClient,
  LoginRequiredError: workos.LoginRequiredError,
}))

async function loadAuth() {
  vi.resetModules()
  const { startAuth, useAuth } = await import('../auth')
  await startAuth('client_123')
  return useAuth()
}

beforeEach(() => {
  vi.clearAllMocks()
  workos.client.getUser.mockReturnValue({ email: 'ada@example.com', firstName: 'Ada' })
})

afterEach(() => {
  window.history.replaceState({}, '', '/')
})

describe('auth', () => {
  it('keeps the refresh token where it survives a reload, coming back to the app root', async () => {
    await loadAuth()

    expect(workos.createClient).toHaveBeenCalledWith(
      'client_123',
      expect.objectContaining({ devMode: true, redirectUri: `${window.location.origin}/` }),
    )
  })

  it('is signed in when WorkOS has a user', async () => {
    const auth = await loadAuth()

    expect(auth.isLoaded.value).toBe(true)
    expect(auth.isSignedIn.value).toBe(true)
    expect(auth.user.value?.email).toBe('ada@example.com')
  })

  it('is signed out when WorkOS has no user', async () => {
    workos.client.getUser.mockReturnValue(null)
    const auth = await loadAuth()

    expect(auth.isSignedIn.value).toBe(false)
  })

  it('gives no access token once the session is gone', async () => {
    workos.client.getAccessToken.mockRejectedValue(new workos.LoginRequiredError())
    const auth = await loadAuth()

    await expect(auth.getAccessToken()).resolves.toBeNull()
  })

  it('passes an invitation along when signing in', async () => {
    window.history.replaceState({}, '', '/?invitation_token=inv_123')
    const auth = await loadAuth()

    await auth.signIn()

    expect(workos.client.signIn).toHaveBeenCalledWith({ invitationToken: 'inv_123' })
  })

  it('signs out at WorkOS and comes back to the app', async () => {
    const auth = await loadAuth()

    auth.signOut()

    expect(workos.client.signOut).toHaveBeenCalledWith({ returnTo: `${window.location.origin}/` })
  })
})
