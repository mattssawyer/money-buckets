import {
  createLinkToken,
  exchangePublicToken,
  finishLinkSession,
  type LinkResult,
  type LinkSessionResult,
} from '../api/PlaidService'

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

/**
 * Opens Plaid Link for a session where the user can add several banks before it closes. Resolves
 * once Link closes, with whether it closed on an error; the banks added come from
 * {@link finishLinkSession}, since Link hands the page no token for them. Rejects when Link
 * can't open.
 */
export function openMultiItemLink(token: string): Promise<{ failed: boolean }> {
  return new Promise((resolve, reject) => {
    if (!window.Plaid) {
      reject(new Error('Plaid Link unavailable'))
      return
    }
    handler?.destroy()
    // Link can report the end more than once (HANDOFF, then onSuccess or onExit); the first counts.
    let settled = false
    const settle = (failed: boolean) => {
      if (settled) return
      settled = true
      resolve({ failed })
    }
    handler = window.Plaid.create({
      token,
      onSuccess() {
        settle(false)
      },
      onExit(error) {
        settle(Boolean(error))
      },
      onEvent(eventName) {
        if (eventName === 'HANDOFF') settle(false)
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
 * Connects one or more banks through Link, which stays open until the user is done adding them,
 * and saves them. Resolves with what the server says about them, or null when the user closes
 * Link without adding one. Rejects when Link fails before any bank was added.
 */
export async function linkItems(): Promise<LinkSessionResult | null> {
  const token = await createLinkToken()
  const { failed } = await openMultiItemLink(token)
  const result = await finishLinkSession(token)
  if (result.item_ids.length) return result
  if (failed) throw new Error('Plaid Link failed')
  return null
}

/**
 * Connects a new Plaid item through Link and saves it, one bank per session; investment links
 * use this. Resolves with what the server says about it, or null when the user closes Link
 * without finishing.
 */
export async function linkItem(
  options: { investments?: boolean } = {},
): Promise<LinkResult | null> {
  const publicToken = await openPlaidLink(await createLinkToken(options))
  return publicToken ? exchangePublicToken(publicToken) : null
}
