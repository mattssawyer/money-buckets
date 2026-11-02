# 13: iOS managing bank connections

**What to build:** A user can connect another bank or investment account on iOS. When a bank stops accepting its login, they reconnect the existing Plaid item in update mode so it keeps syncing with its history instead of becoming a second link. They can also remove a bank, which stops syncing and deletes its transactions and recurring payments. Its account names and balance history stay, and it drops out of current net worth from removal day.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** 03, 06

**Status:** ready-for-agent

- [ ] Connect another bank or investment account with native Plaid Link.
- [ ] Banks needing reconnection are flagged. Reconnect uses an update-mode iOS Link token and keeps the same item.
- [ ] Investment consent can be added to an existing item where the web supports it.
- [ ] Removing a bank asks for confirmation, explains what is deleted and what stays, and the result matches the web.
- [ ] Cancelled Link, failed reconnect, and failed removal show clearly.
- [ ] Workflow tests cover add, reconnect, investment consent, remove, and each failure.
