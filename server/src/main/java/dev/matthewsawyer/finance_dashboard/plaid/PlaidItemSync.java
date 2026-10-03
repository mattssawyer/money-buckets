package dev.matthewsawyer.finance_dashboard.plaid;

import dev.matthewsawyer.finance_dashboard.history.BalanceHistory;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

/**
 * Keeps a Plaid item's stored accounts, transactions and recurring streams up to date, records
 * the day's balance snapshots, and queues new transactions for sorting.
 *
 * <p>Syncs of the same item never overlap: concurrent syncs would race each other's
 * transactions cursor. Failures are logged rather than thrown, because Plaid notifies again on
 * its next update and that sync picks up whatever this one missed.
 */
@Service
public class PlaidItemSync {

    private static final Logger log = LoggerFactory.getLogger(PlaidItemSync.class);

    private static final String TRANSACTIONS = "TRANSACTIONS";
    private static final String SYNC_UPDATES_AVAILABLE = "SYNC_UPDATES_AVAILABLE";
    private static final String RECURRING_TRANSACTIONS = "RECURRING_TRANSACTIONS";
    private static final String RECURRING_TRANSACTIONS_UPDATE = "RECURRING_TRANSACTIONS_UPDATE";
    private static final String HOLDINGS = "HOLDINGS";
    private static final String DEFAULT_UPDATE = "DEFAULT_UPDATE";

    /**
     * Waits between checks for an item's recurring streams while Plaid has none yet. Plaid
     * detects streams in the background after an item's history arrives, so a check right after
     * linking finds nothing.
     */
    private static final List<Duration> RECURRING_RETRY_DELAYS = List.of(
            Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(2));

    /** How much of an item a sync brings up to date; each scope includes the ones before it. */
    private enum Scope { ACCOUNTS, RECURRING, EVERYTHING }

    private final PlaidItemRepository plaidItemRepository;
    private final AccountsSync accountsSync;
    private final TransactionsSync transactionsSync;
    private final RecurringStreamsSync recurringStreamsSync;
    private final BucketSorting bucketSorting;
    private final BalanceHistory balanceHistory;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final Executor executor;
    private final TaskScheduler retryScheduler;
    private final Map<String, Object> itemLocks = new ConcurrentHashMap<>();
    /** Recurring re-checks scheduled so far for items that have no streams yet. */
    private final Map<String, Integer> recurringRetries = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingRecurringRetries = new ConcurrentHashMap<>();

    PlaidItemSync(
            PlaidItemRepository plaidItemRepository,
            AccountsSync accountsSync,
            TransactionsSync transactionsSync,
            RecurringStreamsSync recurringStreamsSync,
            BucketSorting bucketSorting,
            BalanceHistory balanceHistory,
            Clock clock,
            TransactionTemplate transactionTemplate,
            @Qualifier("plaidSyncExecutor") Executor executor,
            @Qualifier("plaidRetryScheduler") TaskScheduler retryScheduler
    ) {
        this.plaidItemRepository = plaidItemRepository;
        this.accountsSync = accountsSync;
        this.transactionsSync = transactionsSync;
        this.recurringStreamsSync = recurringStreamsSync;
        this.bucketSorting = bucketSorting;
        this.balanceHistory = balanceHistory;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.executor = executor;
        this.retryScheduler = retryScheduler;
    }

    /** Syncs a newly linked or relinked item before returning, so its data is there right away. */
    public void linked(PlaidItem item) {
        syncUnderLock(item.getItemId(), Scope.EVERYTHING);
    }

    /**
     * Queues a full sync of every item that hasn't been removed, on startup. Webhooks Plaid sent
     * while the server was down are lost, so this picks up what they would have.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUp() {
        for (PlaidItem item : plaidItemRepository.findAllByRemovedOnIsNull()) {
            String itemId = item.getItemId();
            try {
                executor.execute(() -> syncUnderLock(itemId, Scope.EVERYTHING));
            } catch (RejectedExecutionException e) {
                log.warn("Sync queue is full; item {} waits for Plaid's next webhook", itemId);
            }
        }
    }

    /** Refreshes an item's accounts and details from Plaid before returning. */
    public void refreshAccounts(String itemId) {
        syncUnderLock(itemId, Scope.ACCOUNTS);
    }

    /**
     * Asks Plaid for an item's recurring streams again before returning.
     *
     * @throws RuntimeException when the recurring streams couldn't be fetched or stored
     */
    public void recheckRecurring(String itemId) {
        RuntimeException failure = syncUnderLock(itemId, Scope.RECURRING);
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Stops syncing an item the user removed, and deletes its transactions and recurring streams.
     * Its accounts are dropped rather than deleted, so net worth keeps its history. All of it
     * happens in one transaction, which ends before the lock is released.
     *
     * @throws RuntimeException if removal fails; the database transaction rolls back
     */
    public void removed(String itemId) {
        synchronized (itemLocks.computeIfAbsent(itemId, key -> new Object())) {
            LocalDate today = LocalDate.now(clock);
            transactionTemplate.executeWithoutResult(status -> {
                plaidItemRepository.markRemoved(itemId, today);
                accountsSync.dropAll(itemId, today);
                transactionsSync.forget(itemId);
                recurringStreamsSync.forget(itemId);
            });
        }
    }

    /**
     * Handles a Plaid webhook for an item. Syncs run off the calling thread so webhook responses
     * stay fast; webhooks that need no sync, and items we don't store, are ignored.
     */
    public void notified(String itemId, String webhookType, String webhookCode) {
        Scope scope;
        if (TRANSACTIONS.equals(webhookType) && SYNC_UPDATES_AVAILABLE.equals(webhookCode)) {
            scope = Scope.EVERYTHING;
        } else if (RECURRING_TRANSACTIONS.equals(webhookType) && RECURRING_TRANSACTIONS_UPDATE.equals(webhookCode)) {
            scope = Scope.RECURRING;
        } else if (HOLDINGS.equals(webhookType) && DEFAULT_UPDATE.equals(webhookCode)) {
            // New holdings mean new investment balances, which the accounts list carries.
            scope = Scope.ACCOUNTS;
        } else {
            log.info("Ignoring Plaid webhook {}/{} for item {}", webhookType, webhookCode, itemId);
            return;
        }
        if (itemId == null) {
            log.warn("Ignoring Plaid webhook {}/{} without an item", webhookType, webhookCode);
            return;
        }

        log.info("Queuing sync for item {} after Plaid webhook {}/{}", itemId, webhookType, webhookCode);
        executor.execute(() -> syncUnderLock(itemId, scope));
    }

    /**
     * Accounts always sync first, so the snapshot recorded last sees today's balances. Recurring
     * streams sync whenever transactions do: new transactions can change which streams Plaid
     * detects. Returns why the recurring stream sync failed, or null.
     */
    private RuntimeException syncUnderLock(String itemId, Scope scope) {
        synchronized (itemLocks.computeIfAbsent(itemId, key -> new Object())) {
            // Load inside the lock so the sync starts from what the previous one stored.
            PlaidItem item = plaidItemRepository.findById(itemId).orElse(null);
            if (item == null) {
                log.warn("Skipping sync for unknown item {}", itemId);
                return null;
            }
            if (item.isRemoved()) {
                log.info("Skipping sync for removed item {}", itemId);
                return null;
            }
            LocalDate today = LocalDate.now(clock);

            boolean hasTransactions = syncAccounts(item, today);
            if (hasTransactions && scope == Scope.EVERYTHING) {
                run("Transactions", item, () -> transactionsSync.sync(item));
                bucketSorting.sortLater(item.getUserId());
            }
            RuntimeException recurringFailure = null;
            if (hasTransactions && scope != Scope.ACCOUNTS) {
                recurringFailure = syncRecurring(item);
            }
            run("Balance snapshot", item, () -> balanceHistory.record(itemId, today));
            return recurringFailure;
        }
    }

    /**
     * Returns whether the item has the transactions product. When Plaid can't list the accounts,
     * this assumes it does and lets the transactions sync find out.
     */
    private boolean syncAccounts(PlaidItem item, LocalDate today) {
        try {
            return accountsSync.sync(item, today);
        } catch (RuntimeException e) {
            log.warn("Accounts sync failed for item {}", item.getItemId(), e);
            return true;
        }
    }

    /**
     * Until Plaid has streams for the item, checks again later, waiting longer each time. Then it
     * stops and waits for Plaid's next webhook, since an account may have no recurring payments.
     * At most one re-check is pending per item. Runs under the item's lock; returns why the sync
     * failed, or null.
     */
    private RuntimeException syncRecurring(PlaidItem item) {
        String itemId = item.getItemId();
        int stored = 0;
        RuntimeException failure = null;
        try {
            stored = recurringStreamsSync.sync(item);
        } catch (RuntimeException e) {
            log.warn("Recurring stream sync failed for item {}", itemId, e);
            failure = e;
        }
        if (stored > 0) {
            recurringRetries.remove(itemId);
            ScheduledFuture<?> pending = pendingRecurringRetries.remove(itemId);
            if (pending != null) {
                pending.cancel(false);
            }
            return null;
        }
        if (pendingRecurringRetries.containsKey(itemId)) {
            return failure;
        }
        int retry = recurringRetries.merge(itemId, 1, Integer::sum);
        if (retry > RECURRING_RETRY_DELAYS.size()) {
            recurringRetries.remove(itemId);
            log.info("Still no recurring streams for item {}; waiting for Plaid", itemId);
            return failure;
        }
        Duration delay = RECURRING_RETRY_DELAYS.get(retry - 1);
        log.info("No recurring streams for item {} yet; checking again in {}", itemId, delay);
        pendingRecurringRetries.put(itemId, retryScheduler.schedule(() -> {
            pendingRecurringRetries.remove(itemId);
            syncUnderLock(itemId, Scope.RECURRING);
        }, clock.instant().plus(delay)));
        return failure;
    }

    private static void run(String kind, PlaidItem item, Runnable sync) {
        try {
            sync.run();
        } catch (RuntimeException e) {
            log.warn("{} sync failed for item {}", kind, item.getItemId(), e);
        }
    }
}
