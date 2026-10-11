import { computed, ref, type Ref } from 'vue'
import { updateAccountTracking, type AccountTracking, type PlaidAccount } from '../api/PlaidService'

/** Marking an account as shared starts at an even split. */
const DEFAULT_SHARED_PERCENT = 50

/** Plaid subtypes that don't read well with just a capital letter. */
const KIND_LABELS: Record<string, string> = {
  cd: 'CD',
  hsa: 'HSA',
  ira: 'IRA',
  roth: 'Roth IRA',
  '401k': '401(k)',
  '403b': '403(b)',
  '457b': '457(b)',
  '529': '529 plan',
  'roth 401k': 'Roth 401(k)',
}

/** The kind of account, as Plaid's subtype reads to people, e.g. "Roth IRA". */
export function kindLabel(account: PlaidAccount) {
  const kind = account.subtype ?? account.type
  return KIND_LABELS[kind] ?? kind.charAt(0).toUpperCase() + kind.slice(1)
}

/**
 * Saves what the user chooses about each account: whether it counts toward spending and net
 * worth, and their share of a shared one. Changes show at once and save in the background.
 */
export function useAccountTracking(accounts: Ref<PlaidAccount[]>) {
  const saving = ref(new Set<string>())
  /** Accounts whose last change couldn't be saved; each clears once that account saves. */
  const failedAccountIds = ref(new Set<string>())
  /** What the server last confirmed for each account, to go back to when a save fails. */
  const confirmed = new Map<string, AccountTracking>()

  const failedAccounts = computed(() =>
    accounts.value.filter((account) => failedAccountIds.value.has(account.account_id)),
  )

  function tracking(account: PlaidAccount): AccountTracking {
    return {
      tracks_spending: account.tracks_spending,
      counts_in_net_worth: account.counts_in_net_worth,
      share_percent: account.share_percent,
    }
  }

  function current(accountId: string): PlaidAccount | undefined {
    return accounts.value.find((account) => account.account_id === accountId)
  }

  function sameTracking(a: AccountTracking, b: AccountTracking) {
    return (
      a.tracks_spending === b.tracks_spending &&
      a.counts_in_net_worth === b.counts_in_net_worth &&
      a.share_percent === b.share_percent
    )
  }

  /**
   * Shows the change right away and saves it. An account's saves go one at a time, so a slow
   * earlier response can't overwrite a newer choice: a change made while one is saving is sent
   * when it returns. If a save fails, the account goes back to what was last saved.
   */
  async function save(account: PlaidAccount, changes: Partial<AccountTracking>) {
    const accountId = account.account_id
    const shown = current(accountId)
    if (!shown) return
    if (!confirmed.has(accountId)) confirmed.set(accountId, tracking(shown))
    replace(accountId, changes)
    if (saving.value.has(accountId)) return

    saving.value.add(accountId)
    try {
      for (;;) {
        const wanted = tracking(current(accountId)!)
        let saved: PlaidAccount
        try {
          saved = await updateAccountTracking(accountId, wanted)
        } catch {
          replace(accountId, confirmed.get(accountId)!)
          failedAccountIds.value.add(accountId)
          return
        }
        confirmed.set(accountId, tracking(saved))
        failedAccountIds.value.delete(accountId)
        if (sameTracking(tracking(current(accountId)!), wanted)) {
          replace(accountId, saved)
          return
        }
        // The user changed it again while this was saving; send that too.
      }
    } finally {
      saving.value.delete(accountId)
    }
  }

  function replace(accountId: string, changes: Partial<PlaidAccount>) {
    accounts.value = accounts.value.map((account) =>
      account.account_id === accountId ? { ...account, ...changes } : account,
    )
  }

  function onSwitch(
    account: PlaidAccount,
    key: 'tracks_spending' | 'counts_in_net_worth',
    event: Event,
  ) {
    if (!(event.target instanceof HTMLInputElement)) return
    void save(account, { [key]: event.target.checked })
  }

  function isShared(account: PlaidAccount) {
    return account.share_percent < 100
  }

  function onSharedChange(account: PlaidAccount, event: Event) {
    if (!(event.target instanceof HTMLInputElement)) return
    void save(account, { share_percent: event.target.checked ? DEFAULT_SHARED_PERCENT : 100 })
  }

  function onShareChange(account: PlaidAccount, event: Event) {
    const input = event.target
    if (!(input instanceof HTMLInputElement)) return
    const share = Number(input.value)
    if (!Number.isInteger(share) || share < 1 || share > 99) {
      input.value = String(account.share_percent)
      return
    }
    if (share !== account.share_percent) void save(account, { share_percent: share })
  }

  return { saving, failedAccounts, onSwitch, isShared, onSharedChange, onShareChange }
}
