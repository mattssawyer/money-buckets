# Money Buckets

A personal finance dashboard that pulls a user's bank data from Plaid and helps them build a Conscious Spending Plan from it.

## Language

### Bank data

**Plaid item**:
One login at one institution that a user has connected through Plaid. It holds one or more accounts.
_Avoid_: Connection, link, institution

**Account**:
A single bank, card or investment account within a Plaid item, with its latest balances.
_Avoid_: Plaid account (outside code that talks to Plaid directly)

**Dropped account**:
An account Plaid has stopped returning for its Plaid item, such as a closed account or one the user unchecked when relinking. From the day it's dropped it no longer counts toward net worth or appears among the user's accounts. It comes back if Plaid returns it again.
_Avoid_: Closed account, deleted account

**Selected account**:
What Home is showing, remembered across pages and visits: all tracked accounts together (the default) or one tracked account. Balance, spending, recent transactions and recurring streams all follow it. The spending plan doesn't; it always draws on every tracked account.
_Avoid_: Current account, active account

**Bank account**:
A checking, savings, money market or cash management account: where everyday money comes in and goes out. CDs, HSAs, prepaid cards, credit cards, loans and investment accounts aren't bank accounts.
_Avoid_: Cash account, depository account (Plaid's broader word)

**Tracked account**:
A bank account or credit card the user counts spending from. Checking, cash management and credit card accounts start tracked; savings and money market accounts don't, since money moved into them is saved rather than spent. Spending, recent transactions and recurring streams cover every tracked account unless the user picks one account.
_Avoid_: Spending account, included account

**Shared account**:
An account the user splits with someone else, such as a joint account for rent and household bills. Only the user's share of it counts, in spending and in net worth. A transfer between it and another tracked account isn't spending; that money counts when it's spent from the shared account.
_Avoid_: Joint account (in code), split account

**Investment account**:
An account Plaid types as an investment, such as an IRA, 401(k), brokerage, HSA or 529. A cash HSA or cash-management account Plaid calls a bank account is not one.
_Avoid_: Portfolio, retirement account (only some are)

**Holding**:
A position within an investment account: how much of one security it holds and what that's worth.
_Avoid_: Position, investment

**Net worth**:
Everything the user owns across their linked accounts minus everything they owe (credit cards and loans), not just their investments. Every account counts unless the user leaves it out on the Accounts page, and a shared account counts at the user's share.
_Avoid_: Portfolio value, total balance

**Recurring stream**:
A payment or deposit Plaid has detected repeating on an account, such as a bill or a paycheck. The user can say its payee doesn't repeat, which hides it, or set how often it's paid themselves.
_Avoid_: Recurring transaction, subscription

**Payee**:
Who the user pays (a bill) or is paid by (a paycheck), identified by the merchant, or the description when Plaid found no merchant. Jev judges each payee from all of its recent charges at once: whether the payments are regular, how often, and which plan line they belong under. Everyday spending gets a line too when one names it, so a grocery store sits on Groceries without being a bill. A well-known subscription is recognized from one charge; a payee whose name says nothing, like rent paid to a property manager, once the same amount repeats.
_Avoid_: Merchant (Plaid's field, which a payee falls back from), vendor

**Recurring candidate**:
A payee Jev judges the user likely pays or is paid by regularly, in tracked accounts, when Plaid hasn't detected it as a recurring stream. It's only a guess until the user confirms it; a confirmed candidate counts like a recurring stream, and a rejected one is never suggested again. When Plaid later detects the same payee, the recurring stream takes its place.
_Avoid_: Suggested stream, suspected subscription, Jev stream

**Stopped**:
A recurring stream or candidate whose next payment is a whole cycle overdue: a cancelled subscription, or a bill from an account the user moved away from. It no longer counts as recurring and isn't suggested. One late payment doesn't stop it.
_Avoid_: Inactive (Plaid's word for its own streams), stale

### Keeping data current

**Item linking**:
Connecting a new Plaid item, or reconnecting an existing one, and storing its access for later syncs.
_Avoid_: Onboarding, token exchange

**Item sync**:
Bringing a Plaid item's stored accounts, transactions and recurring streams up to date with Plaid, and recording a balance snapshot. It runs when an item is linked and when Plaid notifies us of changes. While Plaid has found no recurring streams for an item yet, they are checked again a few times over the next hours, and the user can ask for a check at any time.
_Avoid_: Refresh, backfill, import

**Balance snapshot**:
An account's balance as it stood on a given day. Plaid only ever reports today's balance, so snapshots are the only record of past ones, and an account has none from before it was linked.
_Avoid_: History, data point

### Spending plan

**Spending plan**:
A user's monthly Conscious Spending Plan (Ramit Sethi's method): take-home pay split across four buckets, with whatever is left as guilt-free spending. A user has at most one saved plan.
_Avoid_: Budget

**Bucket**:
One of the plan's four places for money: fixed costs, investments, savings or guilt-free spending. The first three hold lines; guilt-free spending is what's left after them. Each transaction's money lands in one bucket, except money that only moves between the user's own accounts, pay coming in, and credit card payments, which don't count toward any.
_Avoid_: Plan part, category (that's Plaid's word for transactions)

**Line**:
A named amount within a bucket, such as "Rent/mortgage" or "401(k)".
_Avoid_: Row, entry

**Breakdown item**:
One of the amounts a line can be split into, such as each insurance policy under "Insurance". It may come from a recurring stream. When a line has breakdown items, its amount is their sum.
_Avoid_: Item on its own (clashes with Plaid item), sub-line

**Correction**:
The user's own bucket and/or category for a payee's spending, set by clicking any of its transactions. It covers the payee's charges now and later: sorting uses the corrected bucket instead of asking Jev, and the corrected category is shown in place of Plaid's. Going back to automatic hands the charges back to sorting.
_Avoid_: Override, rule, recategorization

**Placing a bill**:
Putting a recurring bill on a plan line as a breakdown item during plan setup, on the line Jev chose for its payee. The user places or skips bills Jev put on no line.
_Avoid_: Mapping, assigning

**Spending history**:
What the user spent in each of the last few whole months, with the average per month: by bucket, then by the plan line Jev put each payee on, then by payee. Spending at payees on no line, which is all of guilt-free spending, is listed by Plaid category. Plan setup shows it beside the plan so the user can see what's behind each line's amount.
_Avoid_: Usual spending, suggested amount, budget

**Default amount**:
What a blank line counts as in plan setup until the user types their own: their average monthly spending on that line over the last three whole months, in whole dollars. Only a line with no breakdown items has one, so a line filled from recurring bills doesn't. Clearing a typed amount brings it back, and saving keeps it as the line's amount.
_Avoid_: Suggested amount, estimate, autofill

**Take-home pay**:
What lands in the user's account each month after taxes and paycheck deductions.
_Avoid_: Income, salary, net pay

**Paycheck contribution**:
An investment line taken out of the paycheck before it's deposited, like most 401(k)s.
_Avoid_: Pre-tax deduction

**Plan income**:
Take-home pay plus paycheck contributions. Every bucket's share is measured against it.
_Avoid_: Income, gross income

**Fixed-cost buffer**:
A percentage added on top of fixed costs to cover forgotten and rising costs. It defaults to 15%.
_Avoid_: Miscellaneous, padding

**Guilt-free spending**:
Plan income minus the other three buckets. It's negative when the plan spends more than it has.
_Avoid_: Discretionary, leftover, fun money

**Target**:
The suggested share of plan income for a bucket, such as 50–60% for fixed costs.
_Avoid_: Goal, limit

**Flagged**:
A plan outcome that needs the user's attention: fixed costs above their target, or guilt-free spending below zero.
_Avoid_: Over budget, warning

**Sorting**:
Deciding each transaction's bucket, using the user's plan lines as a guide, so a streaming charge lands in fixed costs when the plan lists subscriptions there. New transactions are sorted after each item sync, and every transaction is sorted again when a save changes the plan's lines. Payees are judged right after, since sorting decides which money only moved between the user's own accounts.
_Avoid_: Categorizing, classifying
