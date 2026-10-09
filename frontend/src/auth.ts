import { createClient, LoginRequiredError, type User } from '@workos-inc/authkit-js'
import { computed, readonly, ref, shallowRef } from 'vue'

type AuthClient = Awaited<ReturnType<typeof createClient>>

const user = shallowRef<User | null>(null)
const isLoaded = ref(false)
let client: AuthClient | undefined

/**
 * Starts the WorkOS AuthKit session: finishes a sign-in when WorkOS redirects back with a code,
 * or picks up an earlier session.
 */
export async function startAuth(clientId: string): Promise<void> {
  client = await createClient(clientId, {
    redirectUri: `${window.location.origin}/`,
    // An HTTP-only refresh cookie needs a WorkOS custom domain, so the single-use refresh token
    // is kept in localStorage instead. The Content-Security-Policy in index.html is what stops
    // injected scripts from reading it.
    devMode: true,
    onRefresh: (response) => (user.value = response.user),
    onRefreshFailure: () => (user.value = null),
  })
  user.value = client.getUser()
  isLoaded.value = true
}

/** A fresh access token for the API, or null when signed out. */
async function getAccessToken(): Promise<string | null> {
  if (!client) return null
  try {
    return await client.getAccessToken()
  } catch (error) {
    if (error instanceof LoginRequiredError) return null
    throw error
  }
}

/** An invitation link from WorkOS lands on the app first; its token goes along to WorkOS. */
function invitation(): { invitationToken?: string } {
  const invitationToken = new URLSearchParams(window.location.search).get('invitation_token')
  return invitationToken ? { invitationToken } : {}
}

/** Sends the user to WorkOS to sign in. */
async function signIn(): Promise<void> {
  await client?.signIn(invitation())
}

/** Sends the user to WorkOS to create an account, which needs an invitation. */
async function signUp(): Promise<void> {
  await client?.signUp(invitation())
}

/** Ends the session at WorkOS and comes back to the sign-in page. */
function signOut(): void {
  if (client?.getUser()) {
    client.signOut({ returnTo: `${window.location.origin}/` })
  } else {
    window.location.assign('/')
  }
}

export function useAuth() {
  return {
    user: readonly(user),
    isLoaded: readonly(isLoaded),
    isSignedIn: computed(() => user.value !== null),
    getAccessToken,
    signIn,
    signUp,
    signOut,
  }
}
