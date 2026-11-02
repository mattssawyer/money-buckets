# 10: iOS spending plan: create and edit

**What to build:** A user can create and edit their spending plan on iOS. They enter take-home pay, gross pay where needed, and paycheck contributions (including percent of gross pay), and add lines in fixed costs, investments, and savings. Guilt-free spending is what remains. They can adjust the fixed-cost buffer and see when fixed costs exceed their target or guilt-free spending falls below zero, then save. The plan is the same one the web shows.

Source spec: `.scratch/ios-money-buckets/spec.md`

**Blocked by:** 01, 04

**Status:** ready-for-agent

- [ ] Take-home pay, gross pay, and paycheck contributions (fixed amount or percent of gross pay) can be entered and saved.
- [ ] The user can add, edit, and remove lines in fixed costs, investments, and savings.
- [ ] Guilt-free spending, bucket shares, buffer, and the two warnings come from the server's plan evaluation, not from iOS code. While editing, the app asks the server to evaluate the unsaved plan, keeps the last numbers on screen while it waits, and shows a failed request clearly.
- [ ] Saving updates the shared plan. Reopening on iOS or the web shows the same plan.
- [ ] Failed saves and invalid amounts show clearly and don't look saved.
- [ ] Workflow tests cover creating a first plan, editing it, buffer change, both warnings, and failed save.
