# 05: Invitation sign-up and the invite-only message

**What to build:** An invited person opens their invitation on an iPhone, the app opens, and the invitation token is carried into WorkOS sign-up so they can create the account they were invited to create (with MFA). Anyone without an invitation is told plainly that Money Buckets accounts are invitation-only, so a public App Store listing doesn't look like open registration.

Source spec: `.scratch/ios-money-buckets/spec.md`. See [AuthKit invitations](https://workos.com/docs/authkit/invitations).

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] An invitation link opens the app (Universal Link) and starts sign-up with its invitation token.
- [ ] A new user who signs up from an invitation finishes MFA setup and lands in onboarding.
- [ ] An invalid, expired, or already-used invitation shows a clear message and doesn't look like a successful sign-up.
- [ ] The sign-in screen says accounts are invitation-only and offers no open registration path.
- [ ] Invitation emails and links work on both web and iOS. Any change to the invitation link or WorkOS settings is listed in the PR.
- [ ] Workflow tests cover a valid invitation, invalid invitation, missing invitation, and cancelled sign-up.
