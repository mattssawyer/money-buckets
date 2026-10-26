---
target: home
total_score: 27
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 2
target_identity: "file:/Users/matthewsawyer/Projects/money_buckets/frontend/src/views/HomePage.vue"
target_fingerprint: "sha256:40dab65a8ceb3f6fc43b93ea9b9bc4a47eb09f676af9b01f28eba5041e72890a"
target_path: /Users/matthewsawyer/Projects/money_buckets/frontend/src/views/HomePage.vue
timestamp: 2026-10-26T15-44-56Z
slug: frontend-src-views-homepage-vue
closed: true
---
Method: dual-agent (A: a22aed0ac96157201 · B: aec038792ddb64d8a). Source-only: browser skipped (Home needs WorkOS sign-in; no dev servers running).

## Design Health Score
| # | Heuristic | Score | Key Issue |
|---|---|---|---|
| 1 | Visibility of System Status | 3 | Good skeletons; no "still sorting" cue on guilt-free figure |
| 2 | Match System / Real World | 3 | "Plaid" in user copy; ring total counts savings/investments as spending |
| 3 | User Control and Freedom | 3 | "No" on a candidate vanishes; undo only in Recurring dialog |
| 4 | Consistency and Standards | 3 | Legend swatch and bucket order differ from DESIGN.md; View all inconsistent |
| 5 | Error Prevention | 3 | "Remove the older connection" has no confirm |
| 6 | Recognition Rather Than Recall | 2 | Rows don't show bucket; tap-to-correct invisible on touch |
| 7 | Flexibility and Efficiency | 2 | One candidate save locks all; no batch; expand state not kept |
| 8 | Aesthetic and Minimalist Design | 3 | Buckets start expanded; hint paragraphs |
| 9 | Error Recovery | 3 | Plan load failure hides guilt-free silently |
| 10 | Help and Documentation | 2 | One InfoTip on Home; buckets/guilt-free/Not sorted unexplained |
| **Total** | | **27/40** | **Acceptable** |

## Design Specificity Verdict
Visual skin specific (Quiet Ledger followed closely); composition interchangeable (greeting, balance, transactions, recurring, donut). The product's defining number, guilt-free left, is the smallest figure at the foot of the last card. Detector: 0 findings across HomePage.vue, 17 components, App.vue, and src/components.

## Priority Issues
- [P1] Guilt-free left buried and under-weighted (GuiltFreeProgress.vue; HomePage.vue:1130). Make it Home's headline figure, first on phone. layout, typeset.
- [P1] Guilt-free number looks final when it isn't or disappears silently: unsorted not counted, rolling periods drop the bar (159-161), plan load failure hidden (364), no stale mark when reconnect needed. harden.
- [P2] Ring total "$X this month" (HomePage.vue:999-1003) sums all buckets incl. savings/investments. clarify.
- [P2] Accessibility/touch: UserMenu focus ring uses 5% alpha (UserMenu.vue:62); unnamed tabindex lists (764, 843, 891); #9e9e9e meta at ~2.7:1; phone rows 19-24px tall. audit, adapt.
- [P2] Recurring card hint text with Plaid jargon (839-840, 888-890), uncapped candidates on phone, all buckets start open (182). distill.

## Persona Red Flags
Alex: one-at-a-time candidates, answeringKey locks all, no shortcuts, expand state resets.
Sam: invisible avatar focus, nameless list tab stops, ring total aria-hidden, role=alert notices interrupt on load.
Casey: key number 3-4 screens down; 19-24px rows; small side-by-side Yes/No; no tap feedback.
Priya (new invited friend): no explanation of buckets/guilt-free/Not sorted; "tracked", "Plaid"; ring total reads savings as spending; rows don't show Jev's bucket or that they're tappable.

## Minor Observations
Greeting h1 says nothing; hard-coded #ececec and #171717 in HomePage.vue; duplicate select CSS; three inner scrollers on desktop; scope mix (ring one account, bar all accounts); SameInstitutionNotice mentions net worth on Home.

## Questions to Consider
Why is Balance the headline when the plan never uses it? Should the ring show where money went or what's left? Should recurring questions live on Home? How does a new user trust Jev if bucket choices are invisible on Home rows?
