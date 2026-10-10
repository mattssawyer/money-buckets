import { apiClient } from './client'

/**
 * Disconnects the user's banks from Plaid and deletes everything stored about them, including
 * their sign-in. Safe to call again after a failure.
 */
export async function deleteCurrentUser(): Promise<void> {
  await apiClient.delete('/users/me')
}

export interface CurrentUser {
  /** Whether the user has finished onboarding; until then the app opens on it. */
  onboarded: boolean
}

export async function getCurrentUser(): Promise<CurrentUser> {
  const { data } = await apiClient.get<CurrentUser>('/users/me')
  return data
}

/** Marks onboarding done, so the app opens on Home from now on. */
export async function finishOnboarding(): Promise<CurrentUser> {
  const { data } = await apiClient.put<CurrentUser>('/users/me/onboarded')
  return data
}
