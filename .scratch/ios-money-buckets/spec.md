# Money Buckets iOS first release

Status: ready-for-agent

## Problem Statement

People in the invited Money Buckets group use the web app to follow a Conscious Spending Plan, but they do not yet have an iOS app for the same work. A mobile app that omits parts of the web experience or changes the meaning of the spending picture would make it harder to use Money Buckets consistently and harder to trust the amount of guilt-free spending left.

## Solution

Create a native iOS version of Money Buckets for the existing invited group first. It carries the current Money Buckets tasks end to end, using iOS-appropriate layouts and navigation while keeping the same product concepts and results as the web app.

Share the first release privately with invited users. Broader App Store availability follows only after the invited pilot shows that users can complete the agreed workflows, trust the spending picture, and report no major usability blockers. The public listing remains invitation-only for account creation; a listing does not open registration.

## User Stories

1. As an invited new user, I want to open my invitation on iOS and carry its invitation token into sign-up, so that I can create the account I was invited to create.
2. As an invited new user, I want WorkOS sign-in to retain its required multi-factor authentication, so that the iOS app meets the account security standard of the web app.
3. As an invited user, I want to sign in to the same Money Buckets account I use on the web, so that my spending plan, linked accounts, corrections, recurring answers, and history are shared between platforms.
4. As a person without an invitation, I want the app to explain that accounts are invitation-only, so that a public App Store listing does not imply that I can register immediately.
5. As a returning user who has not finished onboarding, I want the app to resume onboarding, so that I can connect a bank and complete the required first step.
6. As a new user, I want to connect at least one bank during onboarding, so that Money Buckets can build my spending picture from bank data.
7. As a new user, I want to review which accounts were found after connecting a bank, so that I can choose which accounts count toward spending and net worth.
8. As a new user, I want a brief introduction to the spending plan that I can skip, so that I understand the four buckets without having to create the plan before bank data arrives.
9. As a new user, I want to link investment accounts during onboarding or skip that step, so that I can include investments when I am ready.
10. As a returning user who already completed onboarding, I want to open on Home, so that I can see my current Money Buckets picture without repeating setup.
11. As a user, I want to connect another bank or investment account, so that the app reflects my linked financial accounts.
12. As a user whose bank stops accepting its login, I want to reconnect the existing Plaid item, so that the item continues syncing with its history instead of appearing as a second link.
13. As a user, I want to remove a linked bank, so that it stops syncing, its transactions and recurring payments are deleted, and its account names and balance history remain while it is dropped from current net worth as of removal day.
14. As a user, I want Home to show balances for all tracked accounts by default, so that I can see the money I track for spending at a glance.
15. As a user, I want to select one tracked account on Home and have that choice remembered, so that I can inspect the balance, spending, recent transactions, and recurring streams for that account.
16. As a user, I want Home spending and guilt-free spending left to follow the calendar month, so that the headline number has the same meaning as it does on the web.
17. As a user, I want to see spending by bucket and category alongside the plan, so that I can understand what has been spent and where it belongs.
18. As a user, I want to see how much guilt-free spending I have left, including when it is negative, so that I can make spending decisions using an honest number.
19. As a user, I want Home to tell me when the spending plan is missing or data is still arriving, so that an incomplete picture is not presented as complete.
20. As a user, I want to review recent and paginated transactions, so that I can check the activity behind my spending picture.
21. As a user, I want to correct a payee's bucket or category from one of its transactions, so that the correction applies to that payee's current and future charges.
22. As a user, I want to return a payee to automatic sorting, so that Jev can sort its charges again when I no longer want my correction.
23. As a user, I want to mark a transaction as excluded from or included in totals, so that my spending picture reflects my corrections without erasing the transaction history.
24. As a user, I want to review Plaid recurring streams and say when a payee does not repeat or set its frequency myself, so that recurring payments reflect what I know about them.
25. As a user, I want to review recurring candidates separately from Plaid streams and confirm or dismiss each one, so that Jev's guesses do not count as confirmed recurring payments without my say.
26. As a user, I want to undo a recurring answer and request another recurring check, so that I can recover from a mistaken answer or ask for updated information.
27. As a user creating a spending plan, I want to enter take-home pay, gross pay where needed, and paycheck contributions, so that the plan represents money I receive and investments taken from pay before deposit.
28. As a user creating or editing a spending plan, I want to set lines in fixed costs, investments, and savings and have guilt-free spending calculated as what remains, so that the plan follows the four-bucket model.
29. As a user creating or editing a spending plan, I want to place recurring bills on suggested plan lines or skip them, so that recurring bills are accounted for without counting a candidate twice.
30. As a user creating or editing a spending plan, I want default line amounts informed by the last three whole months and to see spending history beside the plan, so that I can set amounts with their history in view.
31. As a user, I want to adjust the fixed-cost buffer and see when fixed costs exceed their target or guilt-free spending falls below zero, so that I can notice a plan that needs attention.
32. As a user, I want to change which accounts are tracked for spending and which count in net worth, so that the app reflects the accounts I want to include.
33. As a user with a shared account, I want to set my share percentage, so that only my share counts toward spending and net worth.
34. As a user, I want to view investment accounts and net worth history, so that I can follow how my accounts change over time.
35. As a user, I want investment and net worth history to show only history supported by recorded balance snapshots, so that the app does not imply it knows balances from before an account was linked.
36. As a user, I want to sign out from Settings, so that I can end my session on a shared or personal device.
37. As a user, I want to delete my account and its stored data from Settings, so that I can leave Money Buckets and remove my data.
38. As a user, I want to open the Privacy Policy and Terms of Service from the app, so that I can understand how Money Buckets handles my data and use.
39. As an invited pilot user, I want the iOS app to support the same tasks as the web app, so that I can use either platform without losing part of my Money Buckets workflow.
40. As a pilot participant, I want to complete the agreed workflows and judge whether the spending picture is trustworthy, so that Money Buckets can use my experience to decide whether to proceed to broader App Store availability.

## Implementation Decisions

- Build a native iOS app with SwiftUI. The screens and navigation may fit iOS conventions and do not need to reproduce web pages screen for screen.
- Treat this as a parity release of the existing Money Buckets product. It includes the current end-to-end tasks listed in these user stories and adds no iOS-only feature set.
- Keep the existing Money Buckets account and financial data shared with the web app. The iOS client must use the current WorkOS user identity and existing server-backed product data rather than create an iOS-only account or spending-plan model.
- Keep the current domain meanings and calculation rules. In particular, the spending plan covers all tracked accounts; the Home account selector does not narrow the plan; the combined Home balance leaves shared accounts out while a selected shared account shows the user's share; shared accounts count at the user's share in spending and net worth; and guilt-free spending left is the plan's guilt-free amount minus guilt-free spending in the current calendar month across all tracked accounts, including when the result is negative.
- Preserve the current first-run rules: connecting a bank is required, while the spending-plan introduction and investment linking can be skipped. The user can create a spending plan after bank history has had time to arrive.
- Use WorkOS AuthKit for sign-in and invitation-based sign-up, retain the required MFA policy, and pass invitation tokens through the native sign-up flow. Do not embed a WorkOS API key or client secret in the app. Store session secrets in iOS Keychain.
- Use native Plaid Link for bank linking and reconnect flows. The backend remains responsible for creating Link tokens, exchanging public tokens, and syncing bank data. Add or configure the registered Universal Link redirect URI needed by iOS Link sessions, including update-mode sessions; keep existing web Link behavior working.
- The server remains the authority for financial data, sorting, recurring judgments, and guilt-free calculations. Do not reimplement those business rules as a second source of truth in the iOS client.
- Show clear loading, no-data, delayed-data, and error states wherever the underlying workflow can be incomplete or fail. Never present missing or still-arriving bank data as a complete spending picture.
- Distribute the first release privately to invited pilot users. Proceed to a broader public App Store listing only after the agreed outcome gate is met. Account creation remains invitation-only after listing.
- Review the Privacy Policy and Terms of Service as part of release work. Update their content in the same release if the iOS app changes data collected or processors.

## Testing Decisions

- Use the agreed client seam: workflow tests drive user-visible SwiftUI screens through a stubbed authenticated HTTP transport. Keep WorkOS and Plaid integrations behind replaceable provider adapters so authentication and Link outcomes can be controlled in tests.
- Test observable behavior rather than view-model structure, internal state names, or SDK implementation details. A good test follows an invited user or existing user through the app, supplies API and provider outcomes, and checks what the user can see and what requests or updates result.
- Exercise the critical workflows through the client seam: invitation-based sign-up and sign-in; MFA completion and session restoration; onboarding completion and skip paths; bank link, reconnect, investment consent, and removal; Home account selection and guilt-free spending; transaction review and corrections; recurring streams and candidates; plan create/edit; tracked and shared account settings; investment and net worth history; sign-out; account deletion; and Privacy/Terms access.
- Include failure and incomplete-data behavior at that same seam: invalid or missing invitation, cancelled sign-in or Link, authentication expiry, unavailable API, Plaid reconnect required, bank data still arriving, empty histories, failed mutations, and account deletion failure. The visible result must be clear and must not imply successful completion or trustworthy current data when it is not.
- Keep server controller tests as the API behavior seam for authenticated endpoints and any mobile-specific Link-token changes. Existing controller tests and service tests are prior art for preserving endpoint behavior, while existing web view and component tests are prior art for user-facing Money Buckets behavior.
- Verify that test fixtures preserve existing ADR decisions: balance history starts at recorded snapshots; recurring candidates remain distinct from Plaid streams until confirmed; and Jev's recurring judgment is made per payee.

## Out of Scope

- Widgets, push notifications, or other iOS-only additions in the first release.
- A broad public sign-up flow. The App Store listing can be broadly visible after the pilot, but account creation remains invitation-only.
- Pricing decisions for people outside the invited group.
- Screen-by-screen reproduction of the web layout, or detailed navigation and interaction design beyond the product workflows and behavior described here.
- Android support.
- Numeric adoption or usage targets for the invited pilot; no usage baseline exists.

## Further Notes

- The pilot gate is outcome-based: invited users can complete the agreed workflows, trust the spending picture, and report no major usability blockers. No numeric threshold is set.
- Plaid Link for iOS requires a server-created Link token with a registered `redirect_uri` and Universal Link setup for OAuth returns. The existing server token creation does not currently provide this iOS redirect URI, so implementation will need a compatibility-preserving server change. See [Plaid Link for iOS](https://plaid.com/docs/link/ios/).
- WorkOS documents a native iOS AuthKit SDK using a public client and PKCE, with no API key or client secret in the mobile app. WorkOS's invitation flow keeps registration closed unless a valid invitation token is present, and its AuthKit supports the configured MFA requirement. See [WorkOS iOS SDK](https://workos.com/changelog/ios-sdk), [native AuthKit sign-in](https://workos.com/blog/ios-sdk-authkit-sign-in-tutorial), [AuthKit invitations](https://workos.com/docs/authkit/invitations), and [AuthKit MFA](https://workos.com/docs/authkit/mfa).
- Apple Keychain Services is the platform mechanism for securely storing small secrets such as session tokens. See [Keychain Services](https://developer.apple.com/documentation/security/keychain-services).
- Follow the existing decisions recorded in ADRs: balance history comes only from balance snapshots; Jev's recurring candidates stay distinct from Plaid recurring streams until confirmed; and Jev judges recurring payees rather than isolated transactions.
- Keep product copy aligned with the domain language in `CONTEXT.md`, including “spending plan,” “bucket,” “line,” “tracked account,” “shared account,” “recurring stream,” “recurring candidate,” “correction,” and “guilt-free spending.”
