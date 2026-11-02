# 11: iOS plan: recurring bills, defaults, and spending history

**What to build:** While creating or editing the plan on iOS, the user can place recurring bills on suggested plan lines or skip them. Line amounts default from the last three whole months and can be changed. Spending history is shown next to the plan so the user can set amounts with it in view.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** 02, 10

**Status:** ready-for-agent

- [ ] Unplaced recurring bills appear with a suggested line. The user can place each one or skip it, and a bill is never counted twice.
- [ ] Non-recurring lines default from the server's three-month amounts and the user can change them.
- [ ] Spending history by category and payee is reachable beside the plan without leaving it.
- [ ] Suggestions, defaults, and history all come from the server.
- [ ] Empty history and no recurring bills show sensible states.
- [ ] Workflow tests cover placing, skipping, changing a default, and empty history.
