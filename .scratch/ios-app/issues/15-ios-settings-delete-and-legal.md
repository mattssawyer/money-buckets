# 15: iOS Settings: delete account and legal pages

**What to build:** From Settings on iOS, a user can delete their Money Buckets account and stored data, and open the Privacy Policy and Terms of Service.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] Account deletion asks for confirmation, uses the existing server deletion, then clears the local session and returns to sign-in.
- [ ] Failed deletion shows clearly and doesn't sign the user out as if it worked.
- [ ] Privacy Policy and Terms of Service open from Settings, showing the current published versions.
- [ ] Sign-out is reachable from Settings.
- [ ] Workflow tests cover deletion success, deletion failure, and opening both legal pages.
