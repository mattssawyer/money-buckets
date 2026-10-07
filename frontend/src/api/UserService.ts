import { apiClient } from './client'

/**
 * Disconnects the user's banks from Plaid and deletes everything stored about them, including
 * their sign-in. Safe to call again after a failure.
 */
export async function deleteCurrentUser(): Promise<void> {
  await apiClient.delete('/users/me')
}
