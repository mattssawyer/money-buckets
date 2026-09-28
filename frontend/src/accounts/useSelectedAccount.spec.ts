import { effectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAccounts, type PlaidAccount } from '../api/PlaidService'
import { accountLabel, useSelectedAccount, yourBalance } from './useSelectedAccount'

vi.mock('../api/PlaidService', () => ({
  getAccounts: vi.fn<() => Promise<PlaidAccount[]>>(),
}))

const STORAGE_KEY = 'abacus.selectedAccountId'

function account(
  accountId: string,
  overrides: Partial<PlaidAccount> = {},
  current: number | null = null,
): PlaidAccount {
  return {
    account_id: accountId,
    balances: { available: null, current, iso_currency_code: 'USD', limit: null },
    mask: null,
    name: accountId,
    official_name: null,
    subtype: 'checking',
    type: 'depository',
    trackable: true,
    tracks_spending: true,
    share_percent: 100,
    ...overrides,
  }
}

const checking = account('checking')
const joint = account('joint', { share_percent: 50 })
const savings = account('savings', { subtype: 'savings', tracks_spending: false })

beforeEach(() => {
  localStorage.clear()
  vi.mocked(getAccounts).mockResolvedValue([checking, joint, savings])
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useSelectedAccount', () => {
  it('starts on all tracked accounts', async () => {
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.selectedAccountId.value).toBeUndefined()
    expect(selection.selectedAccount.value).toBeUndefined()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('all')
  })

  it('offers only the accounts spending is tracked from', async () => {
    vi.mocked(getAccounts).mockResolvedValue([
      account('ira', { type: 'investment', subtype: 'ira', trackable: false, tracks_spending: false }),
      account('card', { type: 'credit', subtype: 'credit card' }),
      checking,
      savings,
    ])
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.accounts.value.map((loaded) => loaded.account_id)).toEqual([
      'card',
      'checking',
    ])
  })

  it('restores an account picked on an earlier visit', async () => {
    localStorage.setItem(STORAGE_KEY, 'joint')
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.selectedAccountId.value).toBe('joint')
  })

  it('goes back to all tracked accounts when the remembered one is no longer tracked', async () => {
    localStorage.setItem(STORAGE_KEY, 'savings')
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.selectedAccountId.value).toBeUndefined()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('all')
    expect(selection.select('savings')).toBe(false)
  })

  it('remembers a new selection for other pages, including all tracked accounts', async () => {
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.select('joint')).toBe(true)
    expect(localStorage.getItem(STORAGE_KEY)).toBe('joint')

    expect(selection.select(undefined)).toBe(true)
    expect(selection.selectedAccountId.value).toBeUndefined()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('all')
  })

  it('refuses accounts that are not loaded and reports no change for the current one', async () => {
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.select(undefined)).toBe(false)
    expect(selection.select('someone-elses-account')).toBe(false)
    expect(selection.selectedAccountId.value).toBeUndefined()
  })

  it('keeps the current selection when accounts reload', async () => {
    const selection = useSelectedAccount()
    await selection.load()
    selection.select('joint')
    localStorage.clear()

    await selection.load()

    expect(selection.selectedAccountId.value).toBe('joint')
  })

  it('keeps all tracked accounts selected when accounts reload', async () => {
    localStorage.setItem(STORAGE_KEY, 'joint')
    const selection = useSelectedAccount()
    await selection.load()
    selection.select(undefined)

    await selection.load()

    expect(selection.selectedAccountId.value).toBeUndefined()
  })

  it('keeps loaded accounts and reports the failure when a reload fails', async () => {
    const selection = useSelectedAccount()
    await selection.load()
    vi.mocked(getAccounts).mockRejectedValueOnce(new Error('Server unavailable'))

    await selection.load()

    expect(selection.failed.value).toBe(true)
    expect(selection.loading.value).toBe(false)
    expect(selection.accounts.value).toHaveLength(2)
  })

  it('still selects when storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.selectedAccountId.value).toBeUndefined()
    expect(selection.select('joint')).toBe(true)
  })

  it('ignores accounts that arrive after the page is gone', async () => {
    const scope = effectScope()
    const selection = scope.run(() => useSelectedAccount())!
    const loading = selection.load()
    scope.stop()
    await loading

    expect(selection.accounts.value).toEqual([])
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('gives the balance of what is selected', async () => {
    vi.mocked(getAccounts).mockResolvedValue([
      account('checking', {}, 1000),
      account('joint', { share_percent: 50 }, 3000),
    ])
    const selection = useSelectedAccount()
    await selection.load()

    expect(selection.balance.value).toBe(2500)
    selection.select('joint')
    expect(selection.balance.value).toBe(1500)
  })
})

describe('yourBalance', () => {
  it('takes what is owed on cards away from cash, each at the user’s share', () => {
    expect(
      yourBalance([
        account('checking', {}, 1250.5),
        account('joint', { share_percent: 50 }, 2000),
        account('card', { type: 'credit', subtype: 'credit card' }, 300.25),
      ]),
    ).toBe(1950.25)
  })

  it('skips accounts without a balance, and is null when none has one', () => {
    expect(yourBalance([account('checking', {}, 100), account('new')])).toBe(100)
    expect(yourBalance([account('new')])).toBeNull()
    expect(yourBalance([])).toBeNull()
  })
})

describe('accountLabel', () => {
  it('adds the last digits when Plaid has them', () => {
    expect(accountLabel(account('Checking', { mask: '1234' }))).toBe('Checking ••1234')
    expect(accountLabel(account('Checking'))).toBe('Checking')
  })
})
