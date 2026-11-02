# 01: Server works out the plan and guilt-free spending left

**What to build:** The server becomes the authority for evaluating a spending plan and for guilt-free spending left, so the iOS app can show these numbers without copying the rules. Today the web frontend computes them itself. After this ticket, the server returns the plan evaluation (plan income, bucket totals and shares, fixed-cost buffer, target status, guilt-free amount) and this calendar month's guilt-free spending left. Web Home, the plan overview, and the plan editor show the server's numbers, and what users see doesn't change.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## Approach

**Compute on request; store nothing new.** The evaluation and guilt-free left are worked out each time they're asked for. Only the plan's inputs stay stored, as today (take-home pay, gross pay, buffer percent, lines and items). Guilt-free left changes with every bank sync, correction, exclusion, or tracking change, so a stored value would go out of date. The evaluation is a pure function of the saved plan, and the math is cheap. Storing would only matter for a history of past values, and nothing asks for that.

**One server-side evaluator.** Port the web's plan evaluation to a plain server class with no database or web code. It takes take-home pay, lines, and buffer percent, and returns:

- plan income: take-home pay plus investments taken from the paycheck, or nothing until take-home pay is known
- the amount from the paycheck
- the fixed-cost subtotal and the buffer
- for each bucket (fixed costs including the buffer, investments, savings): amount, share of plan income, target, status (below, within, or above), and whether it's flagged
- guilt-free spending, the fourth bucket, kept apart from the three that hold lines because it's what they leave (see `CONTEXT.md`): amount, share, target, status, flagged

Only two things are flagged: fixed costs above target, and guilt-free spending below zero. Other out-of-range parts get a neutral status only.

**Three ways to reach it:**

1. **Saved plan.** Getting or saving the spending plan also returns its evaluation.
2. **Plan being edited.** A new endpoint takes the same shape as a save request and returns the evaluation without saving. The editor calls it once typing stops (400 ms), one request at a time, rather than on every keystroke.
3. **Guilt-free spending left.** A new endpoint returns the plan's guilt-free amount, guilt-free spending for the month, and what's left. Spending covers all tracked accounts (shared accounts at the user's share) and ignores the Home account picker. The value can be negative. Nothing comes back for left when there's no plan or no take-home pay.

**The client sends the month.** The server can't tell where the user's month starts, so the client sends the start and end dates, the same way the spending-by-bucket endpoint works today. The web and iOS apps both do this.

**Then delete the web copy.** Once the web shows the server's values, remove the frontend plan evaluation and guilt-free-left functions so only one copy of the rules remains.

## Things to settle while building

- **Editor latency.** Totals in the editor update instantly today. With the server doing the math, each update is a request away. Keep showing the last numbers while a new answer loads, and say clearly if the request fails. Don't add a client-side copy of the rules to hide the delay.
- **Rounding parity.** Java and JavaScript round some halfway values differently, especially negative ones (JavaScript `Math.round` rounds halves up toward positive infinity). Round exact decimals half up toward positive, as `Math.round` does, and include halfway and negative cases in the tests. Decided 2026-11-01: exact decimals are fine even where the old web's floating-point rounding came out a cent or a percent lower.
- **Percent-of-gross lines.** The web editor works out the dollar amount for paycheck lines set as a percent of gross pay. The server should compute gross pay × percent itself rather than trust a client-sent amount.
- **Out of scope:** default plan line names and other setup content. They aren't calculation rules; ticket 02 covers plan helpers.

## Acceptance criteria

- [ ] No new database columns or tables hold evaluation results or guilt-free left.
- [ ] A server-side evaluator produces everything listed above. Its tests carry over every case from the web's existing plan evaluation and guilt-free-left tests, with the same expected numbers, plus halfway and negative rounding cases.
- [ ] Getting and saving the spending plan return the evaluation.
- [ ] The server evaluates a plan the user hasn't saved yet without saving it.
- [ ] Percent-of-gross paycheck lines are computed on the server from gross pay.
- [ ] Guilt-free spending left uses the month dates the client sends, covers all tracked accounts at the user's share, ignores the account picker, can be negative, and returns nothing for left without a plan or take-home pay.
- [ ] Web Home, the plan overview, and the plan editor show the server's values. Visible numbers are unchanged and the web tests still pass.
- [ ] The frontend plan evaluation and guilt-free-left functions are removed.
- [ ] While waiting, the editor keeps showing the last numbers, and a failed request shows clearly.
- [ ] Controller tests cover the new and changed endpoints: no plan, no take-home pay, negative left, shared accounts, the account picker being ignored, and an unsaved plan.
