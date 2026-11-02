# 08: iOS transactions and corrections

**What to build:** A user can review their transactions on iOS, scrolling through them page by page, and correct the picture from any transaction. They can change a payee's bucket or category (applies to that payee's current and future charges), send a payee back to automatic sorting, and exclude a transaction from totals or include it again.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** 07

**Status:** ready-for-agent

- [ ] Transactions load page by page, with loading, empty, and error states.
- [ ] From a transaction, the user can set the payee's bucket and category. The change shows on that payee's transactions and in Home spending after refresh.
- [ ] The user can return a payee to automatic sorting.
- [ ] The user can exclude a transaction from totals and include it again. The transaction stays in history.
- [ ] A failed change shows clearly and the screen doesn't show it as saved.
- [ ] Workflow tests cover paging, each correction, revert to automatic sorting, exclude/include, and failed mutations.
