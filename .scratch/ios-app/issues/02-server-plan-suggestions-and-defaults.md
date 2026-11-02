# 02: Server provides plan suggestions and default line amounts

**What to build:** The server provides the spending-plan helpers the web frontend computes today, so iOS can use them without copying the rules: suggested plan lines for recurring bills, recurring bills not yet placed on the plan, an estimated monthly take-home pay from recurring income, and default line amounts from the last three whole months of spending. The web plan flow switches to the server's answers, and what users see doesn't change.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] The server suggests a plan line for each recurring bill, using monthly amounts converted from the stream frequency.
- [ ] The server lists recurring bills that are not yet placed on (or skipped from) the saved plan, without counting a recurring candidate twice when it matches a Plaid stream (ADR 0002).
- [ ] The server estimates monthly take-home pay from recurring income, or returns nothing when it can't.
- [ ] The server returns default amounts per plan line from the last three whole calendar months, plus the spending history by category and payee that the web shows beside the plan.
- [ ] The web plan flow uses these server responses. Existing behaviour is unchanged and the web tests still pass.
- [ ] Controller tests cover empty history, no recurring streams, confirmed versus unconfirmed candidates, and shared accounts at the user's share.
