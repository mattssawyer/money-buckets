# 14: iOS investments and net worth history

**What to build:** A user can view their investment accounts and net worth history on iOS. History only covers what recorded balance snapshots support, so the app never implies it knows balances from before an account was linked.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] Investment accounts and net worth history are shown, with shared accounts at the user's share.
- [ ] History starts at recorded balance snapshots (ADR 0001). Removed accounts drop out of current net worth from removal day.
- [ ] Empty history, loading, and errors have clear states.
- [ ] Workflow tests cover normal history, short history, empty history, and API failure.
