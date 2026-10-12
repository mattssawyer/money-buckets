package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.ItemWebhookUpdateRequest;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.Reconnect;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static dev.matthewsawyer.finance_dashboard.plaid.PlaidSyncJob.Scope;

/** Webhook-driven, durable, independently scheduled work for each Plaid product. */
@Service
public class PlaidItemSync {
    private static final Logger log = LoggerFactory.getLogger(PlaidItemSync.class);
    private final PlaidItemRepository items;
    private final AccountsSync accounts;
    private final TransactionsSync transactions;
    private final RecurringStreamsSync recurring;
    private final BucketSorting sorting;
    private final BalanceHistory history;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Executor executor;
    private final PlaidSyncJobs jobs;
    private final PlaidItemLocks locks;
    private final PlaidApi plaidApi;
    private final PlaidTokenEncryption encryption;
    private final Set<String> dispatched = ConcurrentHashMap.newKeySet();

    @Value("${plaid.sync.recovery:PT24H}") private Duration recovery = Duration.ofHours(24);
    @Value("${plaid.sync.accounts-freshness:PT6H}") private Duration accountFreshness = Duration.ofHours(6);
    @Value("${plaid.sync.manual-cooldown:PT15M}") private Duration manualCooldown = Duration.ofMinutes(15);
    @Value("${plaid.webhook.url:}") private String webhookUrl = "";

    PlaidItemSync(PlaidItemRepository items, AccountsSync accounts, TransactionsSync transactions,
                  RecurringStreamsSync recurring, BucketSorting sorting, BalanceHistory history,
                  Clock clock, TransactionTemplate transaction,
                  @Qualifier("plaidSyncExecutor") Executor executor, PlaidSyncJobs jobs,
                  PlaidItemLocks locks, PlaidApi plaidApi, PlaidTokenEncryption encryption) {
        this.items = items;
        this.accounts = accounts;
        this.transactions = transactions;
        this.recurring = recurring;
        this.sorting = sorting;
        this.history = history;
        this.clock = clock;
        this.transaction = transaction;
        this.executor = executor;
        this.jobs = jobs;
        this.locks = locks;
        this.plaidApi = plaidApi;
        this.encryption = encryption;
    }

    @jakarta.annotation.PostConstruct
    void validateIntervals() {
        for (Duration interval : new Duration[]{recovery, accountFreshness, manualCooldown}) {
            if (interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("Plaid sync intervals must be positive");
            }
        }
    }

    public void linked(PlaidItem item) {
        items.clearLoginRequired(item.getItemId());
        request(item.getItemId(), Scope.ACCOUNTS, "link", null, true);
        request(item.getItemId(), Scope.TRANSACTIONS, "link", null, true);
        request(item.getItemId(), Scope.RECURRING, "link", recovery, false);
        ensureWebhook(items.findById(item.getItemId()).orElse(item));
        drain(item.getItemId());
    }

    /** Scans local state; Plaid is called only for overdue work or persisted pending requests. */
    @Scheduled(fixedDelayString = "${plaid.sync.every:PT1M}")
    public void syncAll() {
        for (PlaidItem item : items.findAllByRemovedOnIsNull()) {
            if (item.getReconnect() == Reconnect.LOGIN_REQUIRED) continue;
            String id = item.getItemId();
            request(id, Scope.ACCOUNTS, "recovery", accountFreshness, false);
            if (!Boolean.FALSE.equals(item.getTransactionsEnabled())) {
                request(id, Scope.TRANSACTIONS, "recovery", recovery, false);
                request(id, Scope.RECURRING, "recovery", recovery, false);
            }
            ensureWebhook(item);
            if (jobs.forItem(id).stream().anyMatch(job -> eligible(item, job.scope) && job.runnable(clock.instant()))) {
                dispatch(id);
            }
        }
    }

    public void refreshAccounts(String itemId) {
        request(itemId, Scope.ACCOUNTS, "manual", manualCooldown, false);
        drain(itemId);
    }

    public void recheckRecurring(String itemId) {
        if (!request(itemId, Scope.RECURRING, "manual", manualCooldown, false)) return;
        RuntimeException failure = drain(itemId);
        if (failure != null) throw failure;
    }

    public void removed(String itemId) {
        if (!locks.run(itemId, Duration.ofSeconds(30), () -> transaction.executeWithoutResult(status -> {
            items.markRemoved(itemId, LocalDate.now(clock));
            accounts.dropAll(itemId, LocalDate.now(clock));
            transactions.forget(itemId);
            recurring.forget(itemId);
            jobs.forget(itemId);
        }))) {
            throw new IllegalStateException("Item is syncing; retry removal shortly");
        }
    }

    public void notified(String itemId, String webhookType, String webhookCode, String errorCode) {
        notified(itemId, webhookType, webhookCode, errorCode, false);
    }

    public void notified(String itemId, String webhookType, String webhookCode, String errorCode,
                         boolean historicalUpdateComplete) {
        if (itemId == null) return;
        if ("ITEM".equals(webhookType)) {
            if ("ERROR".equals(webhookCode) && PlaidRequestException.ITEM_LOGIN_REQUIRED.equals(errorCode)) {
                items.markReconnect(itemId, Reconnect.LOGIN_REQUIRED);
                return;
            }
            if ("PENDING_DISCONNECT".equals(webhookCode) || "PENDING_EXPIRATION".equals(webhookCode)) {
                items.markExpiring(itemId);
                return;
            }
            if ("LOGIN_REPAIRED".equals(webhookCode)) {
                items.clearReconnect(itemId);
                for (Scope scope : new Scope[]{Scope.ACCOUNTS, Scope.TRANSACTIONS, Scope.RECURRING}) {
                    request(itemId, scope, "reconnect", null, true);
                }
                dispatch(itemId);
            }
            return;
        }
        if ("TRANSACTIONS".equals(webhookType) && "SYNC_UPDATES_AVAILABLE".equals(webhookCode)) {
            boolean historyJustReady = historicalUpdateComplete && items.findById(itemId)
                    .map(item -> !item.isTransactionHistoryReady()).orElse(false);
            request(itemId, Scope.TRANSACTIONS, "webhook", null, historyJustReady);
            request(itemId, Scope.ACCOUNTS, "webhook", accountFreshness, false);
        } else if ("TRANSACTIONS".equals(webhookType) && "RECURRING_TRANSACTIONS_UPDATE".equals(webhookCode)) {
            request(itemId, Scope.RECURRING, "webhook", null, false);
        } else if ("HOLDINGS".equals(webhookType) && "DEFAULT_UPDATE".equals(webhookCode)) {
            request(itemId, Scope.ACCOUNTS, "webhook", null, false);
        } else return;
        // Requests have committed before acknowledging the webhook. A full executor cannot lose them.
        dispatch(itemId);
    }

    private static boolean eligible(PlaidItem item, Scope scope) {
        // Unknown product state must be resolved before calling a potentially billable endpoint.
        return switch (scope) {
            case ACCOUNTS, WEBHOOK -> true;
            case TRANSACTIONS -> Boolean.TRUE.equals(item.getTransactionsEnabled());
            case RECURRING -> Boolean.TRUE.equals(item.getTransactionsEnabled()) && item.isTransactionHistoryReady();
        };
    }

    private boolean request(String id, Scope scope, String trigger, Duration freshness, boolean reset) {
        return jobs.request(id, scope, trigger, clock.instant(), freshness, reset);
    }

    private void ensureWebhook(PlaidItem item) {
        if (!webhookUrl.isBlank() && !webhookUrl.equals(item.getRegisteredWebhookUrl())) {
            request(item.getItemId(), Scope.WEBHOOK, "configuration", null, false);
        }
    }

    private void dispatch(String id) {
        if (!dispatched.add(id)) return;
        try {
            executor.execute(() -> {
                try { drain(id); }
                finally { dispatched.remove(id); }
            });
        } catch (RejectedExecutionException e) {
            dispatched.remove(id);
            log.info("Plaid work remains in database for item {}: executor full", id);
        }
    }

    /** One pass per Item; requests arriving during a pass are picked up by the next local scan. */
    private RuntimeException drain(String id) {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        locks.run(id, () -> {
            for (Scope scope : new Scope[]{Scope.WEBHOOK, Scope.ACCOUNTS, Scope.TRANSACTIONS, Scope.RECURRING}) {
                PlaidItem item = items.findById(id).orElse(null);
                if (item == null || item.isRemoved() || item.getReconnect() == Reconnect.LOGIN_REQUIRED) return;
                if ((scope == Scope.TRANSACTIONS || scope == Scope.RECURRING)
                        && Boolean.FALSE.equals(item.getTransactionsEnabled())) {
                    jobs.discard(id, scope);
                    continue;
                }
                if (!eligible(item, scope)) continue;
                PlaidSyncJob run = jobs.start(id, scope, clock.instant());
                if (run == null) continue;
                try (var ignored = MDC.putCloseable("plaid_trigger", run.trigger)) {
                    switch (scope) {
                        case WEBHOOK -> {
                            if (!webhookUrl.isBlank() && !webhookUrl.equals(item.getRegisteredWebhookUrl())) {
                                PlaidCalls.execute(plaidApi.itemWebhookUpdate(new ItemWebhookUpdateRequest()
                                        .accessToken(encryption.decrypt(item.getEncryptedAccessToken(), item.getUserId(), id))
                                        .webhook(webhookUrl)), "item webhook update");
                                items.markWebhookRegistered(id, webhookUrl);
                            }
                        }
                        case ACCOUNTS -> {
                            accounts.sync(item, LocalDate.now(clock));
                            history.record(id, LocalDate.now(clock));
                        }
                        case TRANSACTIONS -> {
                            transactions.sync(item);
                            sorting.itemSynced(item.getUserId(), id);
                            PlaidItem updated = items.findById(id).orElseThrow();
                            if (!updated.isTransactionHistoryReady()) {
                                // Initial data may be empty or partial. Bound readiness checks just like retries,
                                // and preserve their budget across restarts. A history-ready webhook wakes this early.
                                jobs.fail(run, clock.instant(), true);
                                continue;
                            }
                            if (updated.getRecurringSyncedAt() == null) {
                                request(id, Scope.RECURRING, "bootstrap", recovery, false);
                            }
                        }
                        case RECURRING -> recurring.sync(item);
                    }
                    jobs.succeed(run, clock.instant());
                } catch (RuntimeException e) {
                    if (e instanceof PlaidRequestException plaid && plaid.isLoginRequired()) {
                        items.markReconnect(id, Reconnect.LOGIN_REQUIRED);
                    }
                    jobs.fail(run, clock.instant(), !(e instanceof PlaidRequestException plaid) || plaid.isRetryable());
                    failure.compareAndSet(null, e);
                    log.warn("Plaid sync failed: item={} scope={} trigger={}", id, scope, run.trigger, e);
                }
            }
        });
        return failure.get();
    }
}
