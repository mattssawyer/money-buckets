# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

People who want to follow a Conscious Spending Plan (Ramit Sethi's method) without keeping a spreadsheet. Today that's the owner and a small group of invited friends, most of them non-technical, on a free, invite-only service at moneybuckets.io. The aim is a broader public audience later, so design shouldn't assume users who know the codebase, Plaid, or personal-finance jargon.

They use it on phone and desktop equally, and every task (checking guilt-free spending left, reviewing transactions, setting up the plan, managing accounts) needs to work well on both.

## Product Purpose

Money Buckets pulls a user's bank data from Plaid and turns it into a monthly spending plan they can follow: take-home pay split across four buckets (fixed costs, investments, savings, guilt-free spending), with the plan and actual spending kept up to date automatically. Success is a user who knows, at a glance, how much guilt-free money they have left this month and trusts that the number is right without sorting transactions by hand.

## Positioning

It's built around the Conscious Spending Plan rather than a category budget: four buckets, and whatever is left after fixed costs, investments and savings is guilt-free to spend. Jev (via TypeSafe) sorts transactions into buckets and plan lines, judges recurring payees, and spots recurring payments Plaid misses, so the plan stays current with little upkeep. The user can correct any payee by clicking one of its transactions.

## Operating Context

- Onboarding at `/welcome`: link a bank (required), review accounts, meet the spending plan (skippable), link investments (skippable). Bills and history take hours to arrive after linking, so making the plan can wait.
- Home: balance, recent transactions, recurring bills and paychecks, spending this month by bucket and category, guilt-free spending left. Follows the selected account.
- Spending plan: built from take-home pay, recurring bills and spending history, with default amounts from the last three months.
- Accounts: which accounts count toward spending and net worth; shared accounts count at the user's share.
- Investments: holdings and net worth over time.
- Settings, account deletion, and public `/privacy` and `/terms` pages.

## Capabilities and Constraints

- Stack: Vue 3, TypeScript, Vite, Tailwind CSS, PrimeVue 5 frontend; Spring Boot backend; PostgreSQL; WorkOS AuthKit sign-in with required MFA; Plaid for bank data; TypeSafe/Jev for judgments.
- Strict Content Security Policy and Trusted Types on the frontend; UI work must not need inline scripts or third-party origins without updating the policy.
- Domain terms are defined in `CONTEXT.md` and should be used in UI copy and code (e.g. "spending plan", not "budget"; "bucket", "line", "payee", "tracked account", "shared account").
- Plaid only reports today's balance; balance history exists only from snapshots taken since linking.
- Any change to data collected or processors must be reflected in `/privacy` and `/terms` in the same change.
- Undecided: pricing or whether the service stays free beyond the invited group.

## Brand Commitments

- Name: Money Buckets. Domain: moneybuckets.io. "Jev" is the named AI that sorts and judges.
- Voice: plain and short. Labels say what things are; explanations sit behind an info tooltip (`InfoTip`) instead of paragraphs of hint text.
- The owner likes the current look of the app. Visual work is refinement within the existing system, not a redesign, unless the owner asks for one.

## Evidence on Hand

- No testimonials, customer counts, case studies, press, or usage numbers exist. Do not fabricate any.
- Real product facts: README.md, CONTEXT.md, docs/adr/, docs/security-policy.md, and the legal pages in `frontend/src/legal/`.

## Product Principles

1. The guilt-free number must be right. Accuracy and honesty about missing or still-arriving data come before polish; never hide a state silently.
2. Automatic first, correctable always. Jev and Plaid do the sorting; the user can override any payee in one click.
3. Plain language for non-technical people. No finance or Plaid jargon where an everyday word works.
4. Quiet interface. Explanations stay out of the way until asked for.
5. Works the same on phone and desktop.

## Accessibility & Inclusion

No formal standard is required. Use good sense: readable contrast, keyboard access, and labels screen readers can use.
