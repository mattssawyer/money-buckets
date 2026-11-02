# 09: iOS recurring streams and candidates

**What to build:** A user can review recurring payments on iOS. Plaid recurring streams and Jev's recurring candidates appear in separate lists. The user can confirm or dismiss a candidate, say a payee doesn't repeat or set its frequency, undo an answer, and ask for another recurring check.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** 07

**Status:** ready-for-agent

- [ ] Plaid streams and recurring candidates are listed separately. Candidates don't count as recurring until confirmed (ADR 0002).
- [ ] Recurring judgments are shown and answered per payee, not per transaction (ADR 0003).
- [ ] The user can confirm or dismiss a candidate, mark a payee as not repeating, or set its frequency.
- [ ] The user can undo an answer and ask for a new recurring check, with a visible in-progress or finished state.
- [ ] Failed answers or checks show clearly and don't look saved.
- [ ] Workflow tests cover each answer, undo, recheck, empty lists, and failures.
