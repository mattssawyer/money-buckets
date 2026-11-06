import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createLinkToken, finishLinkSession, type LinkSessionResult } from '../api/PlaidService'
import { closePlaidLink, linkItems } from './plaidLink'

vi.mock('../api/PlaidService', () => ({
  createLinkToken: vi.fn<() => Promise<string>>(),
  exchangePublicToken: vi.fn<() => void>(),
  finishLinkSession: vi.fn<(linkToken: string) => Promise<LinkSessionResult>>(),
}))

type LinkOptions = Parameters<Window['Plaid']['create']>[0]
let linkOptions: LinkOptions | undefined

beforeEach(() => {
  linkOptions = undefined
  vi.mocked(createLinkToken).mockResolvedValue('link-token')
  vi.mocked(finishLinkSession).mockResolvedValue({ item_ids: ['a', 'b'], same_institution: [] })
  vi.stubGlobal('Plaid', {
    create: vi.fn<(options: LinkOptions) => unknown>((options: LinkOptions) => {
      linkOptions = options
      return { open: vi.fn<() => void>(), destroy: vi.fn<() => void>() }
    }),
  })
})

afterEach(() => {
  closePlaidLink()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

/** Link's callbacks, once linkItems has fetched the link token and opened it. */
function opened(): Promise<LinkOptions> {
  return vi.waitFor(() => {
    if (!linkOptions) throw new Error('Link not open yet')
    return linkOptions
  })
}

describe('linkItems', () => {
  it('saves every bank added once Link hands off', async () => {
    const linking = linkItems()
    const link = await opened()
    link.onEvent?.('HANDOFF', {})

    await expect(linking).resolves.toEqual({ item_ids: ['a', 'b'], same_institution: [] })
    expect(finishLinkSession).toHaveBeenCalledWith('link-token')
  })

  it('saves the session once when Link reports its end twice', async () => {
    const linking = linkItems()
    const link = await opened()
    link.onEvent?.('HANDOFF', {})
    link.onSuccess('', {})
    await linking

    expect(finishLinkSession).toHaveBeenCalledOnce()
  })

  it('resolves with nothing when the user closes Link without adding a bank', async () => {
    vi.mocked(finishLinkSession).mockResolvedValue({ item_ids: [], same_institution: [] })
    const linking = linkItems()
    const link = await opened()
    link.onExit(null, {})

    await expect(linking).resolves.toBeNull()
  })

  it('keeps the banks added before Link failed', async () => {
    const linking = linkItems()
    const link = await opened()
    link.onExit({ error_code: 'INSTITUTION_DOWN' }, {})

    await expect(linking).resolves.toEqual({ item_ids: ['a', 'b'], same_institution: [] })
  })

  it('fails when Link fails before any bank was added', async () => {
    vi.mocked(finishLinkSession).mockResolvedValue({ item_ids: [], same_institution: [] })
    const linking = linkItems()
    const link = await opened()
    link.onExit({ error_code: 'INSTITUTION_DOWN' }, {})

    await expect(linking).rejects.toThrow('Plaid Link failed')
  })
})
