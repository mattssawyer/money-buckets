import { createLinkToken, exchangePublicToken, type LinkResult } from '../api/PlaidService'

type LinkHandler = ReturnType<Window['Plaid']['create']>

let handler: LinkHandler | undefined

/**
 * Opens Plaid Link with a link token. Resolves with the public token when the user finishes,
 * or null when they close Link without an error. Rejects when Link fails. Opening Link again
 * replaces any handler still open.
 */
export function openPlaidLink(token: string): Promise<string | null> {
  return new Promise((resolve, reject) => {
    if (!window.Plaid) {
      reject(new Error('Plaid Link unavailable'))
      return
    }
    handler?.destroy()
    handler = window.Plaid.create({
      token,
      onSuccess(publicToken) {
        resolve(publicToken)
      },
      onExit(error) {
        if (error) reject(new Error('Plaid Link failed'))
        else resolve(null)
      },
    })
    handler.open()
  })
}

/** Tears down Link, for pages that unmount while it's open. */
export function closePlaidLink(): void {
  handler?.destroy()
  handler = undefined
}

/**
 * Connects a new Plaid item through Link and saves it. Resolves with what the server says about
 * it, or null when the user closes Link without finishing.
 */
export async function linkItem(
  options: { investments?: boolean } = {},
): Promise<LinkResult | null> {
  const publicToken = await openPlaidLink(await createLinkToken(options))
  return publicToken ? exchangePublicToken(publicToken) : null
}
