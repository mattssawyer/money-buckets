import { computed, getCurrentScope, onScopeDispose, readonly, ref } from 'vue'
import { getAccounts, type PlaidAccount } from '../api/PlaidService'

/** Shared by every page, so picking an account on one carries over to the next. */
const STORAGE_KEY = 'abacus.selectedAccountId'
/** Stored for "all tracked accounts"; Plaid account ids never look like this. */
const ALL_TRACKED = 'all'

/**
 * The accounts spending is tracked from, and which of them the user is viewing: all of them
 * together (the default, an undefined selectedAccountId) or one. The choice is remembered across
 * pages and visits.
 */
export function useSelectedAccount() {
  const accounts = ref<PlaidAccount[]>([])
  // Starts from the remembered choice, so data for it can load alongside the account list.
  const selectedAccountId = ref<string | undefined>(recall())
  const loading = ref(false)
  const failed = ref(false)
  let disposed = false

  if (getCurrentScope()) onScopeDispose(() => (disposed = true))

  const selectedAccount = computed(() =>
    accounts.value.find((account) => account.account_id === selectedAccountId.value),
  )
  /**
   * Whether the balance of all tracked accounts leaves out shared ones: money moved into a shared
   * account is already set aside for shared bills, so it isn't the user's to spend.
   */
  const leavesOutShared = computed(
    () => !selectedAccount.value && accounts.value.some((account) => isShared(account)),
  )
  /** The user's balance in whatever they're viewing; see {@link yourBalance}. */
  const balance = computed(() => {
    if (selectedAccount.value) return yourBalance([selectedAccount.value])
    const own = accounts.value.filter((account) => !isShared(account))
    // Every tracked account is shared, so none of it counts.
    if (!own.length && leavesOutShared.value) return 0
    return yourBalance(own)
  })

  /** Loads accounts and resolves the selection. On failure, keeps what was already loaded. */
  async function load() {
    loading.value = true
    failed.value = false
    try {
      const loaded = (await getAccounts()).filter((account) => account.tracks_spending)
      if (disposed) return
      accounts.value = loaded
      selectedAccountId.value = chooseAccount(loaded, selectedAccountId.value)
      remember(selectedAccountId.value)
    } catch {
      if (!disposed) failed.value = true
    } finally {
      if (!disposed) loading.value = false
    }
  }

  /**
   * Switches to one of the loaded accounts, or to all of them when accountId is undefined.
   * Returns whether the selection changed.
   */
  function select(accountId: string | undefined): boolean {
    if (accountId === selectedAccountId.value) return false
    if (
      accountId !== undefined &&
      !accounts.value.some((account) => account.account_id === accountId)
    ) {
      return false
    }
    selectedAccountId.value = accountId
    remember(accountId)
    return true
  }

  return {
    accounts: readonly(accounts),
    selectedAccountId: readonly(selectedAccountId),
    selectedAccount,
    balance,
    leavesOutShared,
    loading: readonly(loading),
    failed: readonly(failed),
    load,
    select,
  }
}

export function accountLabel(account: PlaidAccount): string {
  return account.mask ? `${account.name} ••${account.mask}` : account.name
}

/**
 * What's the user's own across accounts: money in bank accounts, less what's owed on credit
 * cards, each at the user's share of the account. Like net worth, only US dollar balances count,
 * since amounts in different currencies can't be added. Null when none of them has a balance.
 */
export function yourBalance(accounts: readonly PlaidAccount[]): number | null {
  let total: number | null = null
  for (const account of accounts) {
    const current = account.balances.current
    if (current == null || account.balances.iso_currency_code !== 'USD') continue
    // Plaid reports what's owed on a card as a positive balance.
    const signed = account.type === 'credit' ? -current : current
    total = (total ?? 0) + (signed * account.share_percent) / 100
  }
  return total == null ? null : Math.round(total * 100) / 100
}

function isShared(account: PlaidAccount): boolean {
  return account.share_percent < 100
}

function chooseAccount(accounts: PlaidAccount[], current: string | undefined): string | undefined {
  const isLoaded = (accountId: string | undefined) =>
    accounts.some((account) => account.account_id === accountId)

  if (current !== undefined) return isLoaded(current) ? current : undefined
  const remembered = recall()
  return isLoaded(remembered) ? remembered : undefined
}

// Storage can be unavailable (private browsing, blocked site data); selection still works for
// this visit without it.
function recall(): string | undefined {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored == null || stored === ALL_TRACKED ? undefined : stored
  } catch {
    return undefined
  }
}

function remember(accountId: string | undefined) {
  try {
    localStorage.setItem(STORAGE_KEY, accountId ?? ALL_TRACKED)
  } catch {
    // Not remembered past this visit.
  }
}
