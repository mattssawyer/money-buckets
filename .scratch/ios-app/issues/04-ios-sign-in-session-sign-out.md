# 04: iOS app: sign in, stay signed in, sign out

**What to build:** A native SwiftUI Money Buckets app that an existing invited user can sign into with WorkOS AuthKit (with the required MFA). The session is kept in Keychain and restored on relaunch. The user lands on a signed-in shell that loads their account from the server and can sign out. This ticket also sets up the test seam every later iOS ticket uses: workflow tests drive SwiftUI screens through a stubbed authenticated HTTP transport, with WorkOS (and later Plaid) behind replaceable provider adapters.

Source spec: `.scratch/ios-money-buckets/spec.md`. See [WorkOS iOS SDK](https://workos.com/changelog/ios-sdk).

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] A SwiftUI app project lives in the repo with a build and test command documented in the README.
- [ ] Sign-in uses WorkOS AuthKit as a public client with PKCE. No WorkOS API key or client secret is in the app.
- [ ] The access tokens the app gets are accepted by the existing server, which checks the WorkOS issuer and the client ID claim. Any WorkOS configuration needed for this is listed in the PR.
- [ ] MFA required by the WorkOS policy is completed during sign-in.
- [ ] Session secrets are kept in Keychain and the session is restored on relaunch without signing in again.
- [ ] An expired or rejected session sends the user back to sign-in, with a clear message rather than empty or stale data.
- [ ] Every API request goes through one authenticated HTTP transport that tests can replace.
- [ ] The signed-in shell loads the current user from the server and sends a user who hasn't finished onboarding to an onboarding placeholder, and others to a Home placeholder.
- [ ] Sign-out clears the Keychain session and returns to sign-in.
- [ ] Workflow tests cover sign-in, cancelled sign-in, session restore, expiry, unavailable API, and sign-out at the stubbed seam.
