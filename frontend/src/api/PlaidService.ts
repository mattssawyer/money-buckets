import { apiClient } from './client'
import type { SpendingPlanBucket } from './SpendingPlanService'

export interface PlaidAccount {
  account_id: string
  balances: {
    available: number | null
    current: number | null
    iso_currency_code: string | null
    unofficial_currency_code?: string | null
    limit: number | null
  }
  mask: string | null
  name: string
  official_name: string | null
  subtype: string | null
  type: string
  /** Whether spending can be tracked from the account: a bank account or credit card. */
  trackable: boolean
  tracks_spending: boolean
  counts_in_net_worth: boolean
  /** The user's share of the account's money, 1–100; below 100 for a shared account. */
  share_percent: number
}

/** What the user chooses about an account on the Accounts page. */
export interface AccountTracking {
  tracks_spending: boolean
  counts_in_net_worth: boolean
  /** 1–100; applies to both spending and net worth. */
  share_percent: number
}

export interface PlaidTransaction {
  transaction_id: string
  account_id: string
  /** Plaid convention: positive when money leaves the account. */
  amount: number
  iso_currency_code: string | null
  date: string
  name: string | null
  merchant_name: string | null
  logo_url: string | null
  pending: boolean
  category: string | null
  /** Plaid's detailed category within it, e.g. FOOD_AND_DRINK_GROCERIES; null once corrected. */
  category_detailed: string | null
  /** Null until sorting reaches it. NOT_COUNTED is money moved between own accounts. */
  bucket: SpendingBucket | null
  /** The plan line Jev put the payee on, within the transaction's bucket; null when on none. */
  plan_line: string | null
  /** The user's share of the account's money, 1–100; amount is the whole transaction's. */
  share_percent: number
  /** Who was paid, or paid the user; corrections and recurring answers are kept per payee. */
  payee_key: string | null
  payee_kind: RecurringKind | null
  /** Whether the user set the payee's bucket, or its category, themselves. */
  bucket_corrected: boolean
  category_corrected: boolean
  /** Whether the payee repeats, as far as Plaid, the user or Jev have said. */
  recurring: TransactionRecurring | null
}

export type RecurringKind = 'BILL' | 'PAYCHECK'

/**
 * DETECTED: Plaid found it recurring. CONFIRMED / DISMISSED: the user said it does or doesn't
 * repeat. SUGGESTED: Jev thinks it repeats and the user hasn't answered.
 */
export type TransactionRecurring = 'DETECTED' | 'CONFIRMED' | 'DISMISSED' | 'SUGGESTED'

/** A payee, as recurring answers and corrections are stored under. */
export interface PayeeRef {
  kind: RecurringKind
  merchant_key: string
}

export interface TransactionPage {
  transactions: PlaidTransaction[]
  /** How many transactions there are across all pages. */
  total: number
}

export interface RecurringStream {
  stream_id: string
  account_id: string
  merchant_name: string | null
  description: string | null
  amount: number
  iso_currency_code: string | null
  frequency: string
  next_date: string | null
  last_date: string | null
  is_inflow: boolean
  category: string | null
  /** Plaid detailed personal finance category, e.g. RENT_AND_UTILITIES_TELEPHONE. */
  category_detailed: string | null
  /** The user's share of the account's money, 1–100; amount is the whole stream's. */
  share_percent: number
  /**
   * The spending plan line Jev put this payee's bills under. Null for pay, for bills that fit no
   * line, and until the payee is judged.
   */
  plan_bucket: SpendingPlanBucket | null
  plan_line: string | null
}

/** Whether the user has answered a recurring candidate yet, and how. */
export type RecurringCandidateStatus = 'SUGGESTED' | 'CONFIRMED' | 'DISMISSED'

/**
 * A payee Jev thinks the user pays (a bill) or is paid by (a paycheck) regularly, which Plaid
 * hasn't detected as a recurring stream. Shaped like a stream, from its latest charge, so a
 * confirmed one can be listed and planned like Plaid's; its stream_id is made up.
 */
export interface RecurringCandidate extends RecurringStream {
  kind: RecurringKind
  /** What the user's answer is stored under. */
  merchant_key: string
  /** How likely Jev thinks the payments are regular, 0–1. */
  probability: number
  status: RecurringCandidateStatus
}

export interface CategorySpend {
  /** Plaid primary personal finance category, or UNCATEGORIZED. */
  category: string
  amount: number
  /** The transactions that add up to amount, newest first. Refunds are negative. */
  transactions: PlaidTransaction[]
}

/** UNSORTED holds transactions sorting hasn't reached yet. */
export type Bucket = 'FIXED_COSTS' | 'GUILT_FREE' | 'SAVINGS' | 'INVESTMENTS' | 'UNSORTED'

export interface BucketSpend {
  bucket: Bucket
  amount: number
  /** Largest first. */
  categories: CategorySpend[]
}

export interface SpendingByBucket {
  start: string
  end: string
  total: number
  /** In plan order, with unsorted transactions last. */
  buckets: BucketSpend[]
}

interface AccountsResponse {
  accounts: PlaidAccount[]
}

/** One login at one institution. */
export interface PlaidItem {
  item_id: string
  /** Null until a sync learns it. */
  institution_name: string | null
  /** Whether the item has Plaid's investments product. */
  investments: boolean
  /** Whether its institution offers investments at all; null until a sync finds out. */
  investments_available: boolean | null
}

/**
 * added: done. needs_consent: finish Link update mode with link_token, then ask again.
 * not_offered: the institution has no investments to share, like most banks.
 */
export interface AddInvestmentsResult {
  outcome: 'added' | 'needs_consent' | 'not_offered'
  link_token: string | null
}

export interface LinkResult {
  item_id: string
  /** The user's other items at the same institution, whose accounts now appear twice. */
  same_institution: PlaidItem[]
}

/** Investment links also show institutions, like 401(k) providers, that have no transactions. */
export async function createLinkToken(options: { investments?: boolean } = {}): Promise<string> {
  const { data } = await apiClient.post<{ link_token: string }>('/plaid/create-link-token', null, {
    params: options.investments ? { investments: true } : undefined,
  })
  return data.link_token
}

export async function exchangePublicToken(publicToken: string): Promise<LinkResult> {
  const { data } = await apiClient.post<LinkResult>('/plaid/items', { publicToken })
  return data
}

export async function getLinkedItemIds(): Promise<string[]> {
  const { data } = await apiClient.get<{ item_ids: string[] }>('/plaid/items')
  return data.item_ids
}

export async function getLinkedItems(): Promise<PlaidItem[]> {
  const { data } = await apiClient.get<{ items: PlaidItem[] }>('/plaid/items')
  return data.items
}

/** Adds investments to a linked item. */
export async function addInvestments(itemId: string): Promise<AddInvestmentsResult> {
  const { data } = await apiClient.post<AddInvestmentsResult>(
    `/plaid/items/${encodeURIComponent(itemId)}/investments`,
  )
  return data
}

/** Removes an item from Plaid. Its accounts leave net worth from today. */
export async function removeItem(itemId: string): Promise<void> {
  await apiClient.delete(`/plaid/items/${encodeURIComponent(itemId)}`)
}

export async function getAccounts(): Promise<PlaidAccount[]> {
  const { data } = await apiClient.get<AccountsResponse>('/plaid/accounts')
  return data.accounts
}

/** Spending between range.start and range.end, both included; this month without a range. */
export async function getSpendingByBucket(
  accountId?: string,
  range?: { start: string; end: string },
): Promise<SpendingByBucket> {
  const { data } = await apiClient.get<SpendingByBucket>('/plaid/spending/by-bucket', {
    params: { ...(accountId ? { account_id: accountId } : {}), ...(range ?? {}) },
  })
  return data
}

export async function getTransactions(limit = 25, accountId?: string): Promise<PlaidTransaction[]> {
  const { data } = await apiClient.get<{ transactions: PlaidTransaction[] }>(
    '/plaid/transactions',
    {
      params: { limit, ...(accountId ? { account_id: accountId } : {}) },
    },
  )

  return data.transactions
}

/** One page of transactions, newest first. Pages start at 0 and hold at most 100. */
export async function getTransactionPage(
  page: number,
  pageSize: number,
  accountId?: string,
): Promise<TransactionPage> {
  const { data } = await apiClient.get<TransactionPage>('/plaid/transactions', {
    params: { page, limit: pageSize, ...(accountId ? { account_id: accountId } : {}) },
  })
  return data
}

/** Sets whether an account counts toward spending and net worth, and the user's share of it. */
export async function updateAccountTracking(
  accountId: string,
  tracking: AccountTracking,
): Promise<PlaidAccount> {
  const { data } = await apiClient.put<PlaidAccount>(
    `/plaid/accounts/${encodeURIComponent(accountId)}/tracking`,
    tracking,
  )
  return data
}

/** Asks Plaid again for recurring transactions; load them afterwards to see what changed. */
export async function syncRecurringTransactions(): Promise<void> {
  await apiClient.post('/plaid/transactions/recurring/sync')
}

export async function getRecurringTransactions(
  accountId?: string,
  limit?: number,
): Promise<RecurringStream[]> {
  const { data } = await apiClient.get<{ streams: RecurringStream[] }>(
    '/plaid/transactions/recurring',
    {
      params: {
        ...(accountId ? { account_id: accountId } : {}),
        ...(limit != null ? { limit } : {}),
      },
    },
  )

  return data.streams
}

/** Every recurring candidate in the tracked accounts, or in one account, answered or not. */
export async function getRecurringCandidates(accountId?: string): Promise<RecurringCandidate[]> {
  const { data } = await apiClient.get<{ candidates: RecurringCandidate[] }>(
    '/plaid/transactions/recurring/candidates',
    { params: accountId ? { account_id: accountId } : {} },
  )

  return data.candidates
}

/** Stores the user's yes or no for a candidate's merchant, covering its later charges too. */
export async function answerRecurringCandidate(
  candidate: PayeeRef,
  confirmed: boolean,
): Promise<void> {
  await apiClient.put('/plaid/transactions/recurring/candidates/answer', {
    kind: candidate.kind,
    merchant_key: candidate.merchant_key,
    confirmed,
  })
}

/** Forgets the user's answer, so the candidate is suggested again. */
export async function undoRecurringAnswer(candidate: PayeeRef): Promise<void> {
  await apiClient.delete('/plaid/transactions/recurring/candidates/answer', {
    params: { kind: candidate.kind, merchant_key: candidate.merchant_key },
  })
}

/**
 * Sets the bucket and category for every charge from a payee, now and later. Null leaves that
 * part to sorting or Plaid.
 */
export async function correctPayee(
  merchantKey: string,
  bucket: SpendingBucket | null,
  category: string | null,
): Promise<void> {
  await apiClient.put('/payees/corrections', { merchant_key: merchantKey, bucket, category })
}

/** Forgets a payee's correction, so its charges are sorted again. */
export async function undoPayeeCorrection(merchantKey: string): Promise<void> {
  await apiClient.delete('/payees/corrections', { params: { merchant_key: merchantKey } })
}

/** A bucket a transaction's money can count toward. */
export type SpendingBucket = Exclude<Bucket, 'UNSORTED'> | 'NOT_COUNTED'
