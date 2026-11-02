## Destination

Agree on the product direction for Money Buckets' first iOS release: who it serves, its role alongside the web app, and a bounded initial product scope. Architecture and detailed interaction design are downstream.

## Notes

- Domain: Money Buckets product direction. Use the product language in `CONTEXT.md` (for example, “spending plan,” “bucket,” “line,” and “guilt-free spending”).
- The repo currently describes an invite-only service, the existing web app's task set, and a principle that phone and desktop support the same tasks.
- Starting direction from the charting conversation: serve the existing invited group first; run a private pilot before broad App Store availability; carry the existing Money Buckets experience with task parity; defer iOS-only additions.

## Decisions so far

- [What web tasks and user journeys must the first iOS release support to meet the agreed full-parity direction?](issues/01-ios-parity-scope.md): First release covers all current web workflows end to end; the layout and navigation may be iOS-specific.
- [What should the invited iOS pilot demonstrate before Money Buckets proceeds to broad App Store availability?](issues/02-invited-pilot-gate.md): Move to broader availability when invited users can complete the agreed workflows, trust the spending picture, and report no major usability blockers; no numeric adoption target.
- [When the app reaches broad App Store availability after the invited pilot, who should be able to create or access a Money Buckets account?](issues/03-broad-app-store-access.md): Broad App Store listing after the pilot, with account creation remaining invitation-only; pricing is separate.

## Not yet specified

None. All specified product-direction tickets are resolved.

## Out of scope

- iOS-specific additions such as widgets or push notifications in the first release; these were deferred for later consideration.
- iOS architecture, detailed interaction and navigation design, and implementation planning or execution.
- Pricing for a broader audience; the app's access remains invitation-only at the public App Store listing.
