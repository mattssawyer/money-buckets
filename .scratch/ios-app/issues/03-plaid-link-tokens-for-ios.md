# 03: Plaid Link tokens that work on iOS

**What to build:** The iOS app can open native Plaid Link, including OAuth banks and reconnecting. When the app asks for a Link token (new bank, investments, or reconnect/update mode), the server includes the registered iOS redirect URI. The website serves the Universal Link association file so iOS returns to the app after an OAuth bank. Web Link requests behave exactly as before.

Source spec: `.scratch/ios-money-buckets/spec.md`. See [Plaid Link for iOS](https://plaid.com/docs/link/ios/).

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Link token requests can say they come from iOS. Those tokens carry the configured redirect URI for new-bank, investments, and reconnect tokens.
- [ ] Web Link token requests are unchanged (no redirect URI unless the web already used one).
- [ ] The redirect URI is configuration, not hard-coded, and is blank-safe for local runs.
- [ ] The production web host serves an Apple app site association file covering the redirect path, with the right content type and no redirect. The Content-Security-Policy and existing routes still work.
- [ ] Controller tests cover iOS and web token requests for each token type.
- [ ] The steps needed outside the code (registering the redirect URI in the Plaid dashboard, Apple team and bundle ID) are listed in the PR.
