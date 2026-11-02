# 07: iOS Home

**What to build:** A returning user opens the app on Home and sees the same Money Buckets picture as on the web. Balances are shown across all tracked accounts, and the user can pick one tracked account (remembered on the device). Home also shows this calendar month's spending by bucket and category alongside the plan, guilt-free spending left, recent transactions, and recurring streams for the selected account. Missing plans and still-arriving data are never shown as a complete picture. You can demo it with an account linked on the web.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** 01, 04

**Status:** ready-for-agent

- [ ] The combined balance covers tracked accounts and leaves shared accounts out. A selected shared account shows the user's share.
- [ ] The user can select one tracked account or all of them. The choice is remembered on the device across launches.
- [ ] Spending by bucket and category follows the calendar month, alongside the plan.
- [ ] Guilt-free spending left comes from the server, covers all tracked accounts whatever the picker says, and shows negative values honestly. The app sends the current month's start and end dates in the device's time zone.
- [ ] Recent transactions and recurring streams follow the selected account.
- [ ] Clear states for no spending plan, bank data still arriving, a bank needing reconnection, empty data, loading, and API errors.
- [ ] The app doesn't compute plan or guilt-free numbers itself.
- [ ] Workflow tests cover default view, account selection and recall, negative left, no plan, data arriving, and API failure.
