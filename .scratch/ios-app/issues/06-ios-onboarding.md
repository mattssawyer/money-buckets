# 06: iOS onboarding

**What to build:** A new user goes through the same first-run flow as on the web, adapted to iOS. They connect a bank with native Plaid Link (required), review the accounts found and choose which count toward spending and net worth, see a short spending-plan introduction they can skip, and link investments or skip that step. Onboarding resumes where they left off and ends on Home. This ticket brings in the Plaid Link provider adapter that later tickets reuse.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** 03, 04

**Status:** ready-for-agent

- [ ] Connecting a bank uses native Plaid Link with a server-created iOS Link token. The public token goes to the server to exchange.
- [ ] The user can't finish onboarding without at least one connected bank.
- [ ] After linking, the user reviews the accounts found and sets tracking for spending and net worth.
- [ ] The spending-plan introduction explains the four buckets, can be skipped, and doesn't require creating a plan before bank history arrives.
- [ ] The investments step can link investment accounts or be skipped.
- [ ] Finishing tells the server onboarding is done, and the app opens on Home from then on. A user who quits partway resumes onboarding next time.
- [ ] Cancelled Link, failed exchange, and bank data still arriving all show clear states.
- [ ] Workflow tests cover the full path, both skip paths, resume, and cancelled or failed Link through the stubbed transport and Plaid adapter.
