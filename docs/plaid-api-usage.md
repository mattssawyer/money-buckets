# Plaid API usage

The app reads account, transaction, and recurring data from its own database. Plaid webhooks request independent account, transaction, or recurring jobs. A local scan recovers missed webhooks and unfinished work; opening a page does not fetch from Plaid.

## Scheduling

| Work | Trigger / default interval |
| --- | --- |
| Accounts and cached balances | Link/reconnect, holdings update, or six hours since last successful refresh |
| Transactions | Transaction webhook, link/reconnect, or 24 hours since last successful complete sync |
| Recurring streams | First completed transaction history, recurring webhook, or 24 hours since last success |
| Manual recurring check | At least 15 minutes since the last success or attempt; existing failure backoff still applies |
| Webhook registration | Once per Item when its recorded URL differs from the configured URL |
| Recovery scan | Every minute; scans local state, not a Plaid polling interval |

New transaction history can arrive in stages. Incomplete history and transient failures wait roughly 2, 10, 30, then 120 minutes, with up to 20% additional jitter. After those attempts, checks slow to daily. The budget survives restarts. A history-complete webhook can wake initialization early. A successful empty recurring response finishes its job normally. Login-required Items wait for reconnection or a login-repaired webhook.

`PLAID_SYNC_SCAN_INTERVAL`, `PLAID_SYNC_RECOVERY`, `PLAID_ACCOUNTS_FRESHNESS`, and `PLAID_MANUAL_SYNC_COOLDOWN` accept ISO-8601 durations. Defaults are PT1M, PT24H, PT6H, and PT15M. Keep intervals positive. `PLAID_WEBHOOK_URL` must point to the public HTTPS `/api/plaid/webhook` endpoint in production. Existing Items are registered automatically on a recovery scan. A blank URL is useful locally, but then freshness depends on fallback checks.

## Delivery, concurrency, and pagination

Migration V19 stores requested and completed generations for each Item/product. Existing Items with a non-null `recurring_synced_at` are backfilled as history-ready; a cursor alone does not establish readiness. The webhook receiver commits the request before acknowledging delivery. Duplicate requests waiting in the executor merge; a request arriving during execution remains pending for the next scan. Executor saturation, deployment, or a failed process leaves work recoverable in the database.

PostgreSQL session advisory locks serialize work for an Item across application instances. They release on session loss, so no timed lease can expire underneath a slow API call. Use a direct PostgreSQL connection or session-mode pooling, not transaction-mode PgBouncer. Each instance reserves at most four lock connections; the configured connection pool has twelve connections so workers can also commit data. H2 tests use local locks; a separate PostgreSQL integration test verifies independent workers. Busy background workers leave pending jobs for recovery immediately. Disconnect cleanup waits up to 30 seconds across local, capacity, and advisory lock contention before asking for a retry; `ITEM_NOT_FOUND` counts as an already-completed remote removal.

Transaction pages are buffered until the full batch has been retrieved, then changes and the final cursor commit together. A pagination mutation discards the batch and restarts from its original cursor, with at most three immediate attempts. Other failures leave that original cursor available for scheduled retry. Pages remain limited to 500 transactions, with a 200-page guard.

## Products and cost

New investment-only links request Investments alone. They no longer optionally activate Transactions. Adding Investments to an existing spending connection remains supported. Existing Items retain their subscribed products. Recurring remains enabled for transaction connections because the app uses it for bills and paychecks. Unknown product state must first be resolved through `/accounts/get`; a failed account lookup does not trigger potentially billable product activation.

Reducing fetches mainly reduces traffic and failure exposure. It does not cancel subscriptions already associated with an Item. Disconnect calls `/item/remove` before local cleanup, and account deletion stops if remote removal fails. Duplicate connections still require the user's review: two logins at the same institution can be legitimate. No existing Item is removed automatically by this change.

The code audit found no `/transactions/refresh`, `/investments/refresh`, or `/accounts/balance/get` calls. Contract rates, active billable Items, and production webhook delivery must be checked in the Plaid Dashboard; local tests cannot establish those facts.

## Observability and verification

`PlaidCalls` emits one result log per request with endpoint, trigger, HTTP status (or I/O failure), and elapsed time. Error logs include Plaid's error code and request ID, but no tokens or response bodies. Transaction result logs include page count and changed count; recurring logs include stream count. Group these logs to compare calls per endpoint and trigger before/after deployment.

`plaid_sync_jobs` records last attempt, last success, retry time, failure count, and pending generations. Use those fields to monitor overdue work. A transaction/recurring fetch timestamp measures when the app fetched Plaid's data, not when the bank updated it. Expected quiet, healthy transaction Item traffic is about six calls/day (four accounts, one transaction page, one recurring), excluding webhooks, startup migration, and initialization. Webhook activity can satisfy the daily fallback. This is an illustrative request budget, not a billing estimate.

Run the full server suite with `./gradlew test` from `server/`. For migration and PostgreSQL lock checks, create an isolated empty PostgreSQL database and run:

```sh
SPRING_CONFIG_IMPORT= \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/YOUR_TEST_DATABASE \
SPRING_DATASOURCE_USERNAME=YOUR_TEST_USER \
SPRING_DATASOURCE_PASSWORD=YOUR_TEST_PASSWORD \
SPRING_JPA_HIBERNATE_DDL_AUTO=validate \
SPRING_FLYWAY_ENABLED=true \
./gradlew test --tests '*PlaidItemSyncTests' --tests '*PlaidItemLocksDatabaseTests'
```

The default H2 suite skips the PostgreSQL-specific lock test. Tests mock Plaid; they do not spend production API calls. Coverage includes documented recurring webhook routing, history readiness, successful empty results, persistent retries, manual cooldowns, duplicate requests, restart recovery, executor rejection, login recovery, investment-only eligibility, pagination restart/failure, webhook URL repair, and removal retry.

## Documentation checked

Reviewed against Plaid documentation on 2026-10-11:

- [Transactions API](https://plaid.com/docs/api/products/transactions/): recurring webhook uses type `TRANSACTIONS`; initialize recurring after history completes; restart pagination from the original cursor on mutation.
- [Transaction integration](https://plaid.com/docs/transactions/): make the first sync call to activate sync webhooks; changes arrive asynchronously.
- [Webhook delivery](https://plaid.com/docs/api/webhooks/): handle duplicates and out-of-order events, acknowledge promptly, and retain recovery polling.
- [Product initialization](https://plaid.com/docs/link/initializing-products/): optional subscription products can incur charges even without endpoint use.
- [Billing](https://plaid.com/docs/account/billing/): Transactions, Recurring, and Investments use subscription pricing; account listing is free; stopping fetches does not terminate subscriptions.
- [Item API](https://plaid.com/docs/api/items/#itemwebhookupdate): existing webhook URLs can be updated through `/item/webhook/update`.
