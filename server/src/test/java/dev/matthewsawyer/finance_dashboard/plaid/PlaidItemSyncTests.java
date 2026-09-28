package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.Item;
import com.plaid.client.model.Products;
import com.plaid.client.model.RecurringTransactionFrequency;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionStream;
import com.plaid.client.model.TransactionStreamAmount;
import com.plaid.client.model.TransactionsRecurringGetRequest;
import com.plaid.client.model.TransactionsRecurringGetResponse;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.Point;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import jakarta.persistence.EntityManager;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class PlaidItemSyncTests {

    private static final String ITEM_ID = "sync-test-item";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T15:00:00Z"), ZoneOffset.UTC);

    @MockitoBean private PlaidApi plaidApi;

    @Autowired private AccountsSync accountsSync;
    @Autowired private TransactionsSync transactionsSync;
    @Autowired private RecurringStreamsSync recurringStreamsSync;
    @Autowired private PlaidTokenEncryption tokenEncryption;
    @Autowired private PlaidItemRepository items;
    @Autowired private PlaidAccountRepository accounts;
    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private PlaidRecurringStreamRepository streams;
    @Autowired private UserRepository users;
    @Autowired private BalanceHistory balanceHistory;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;

    /** Webhook syncs are queued here and run when the test says so. */
    private final List<Runnable> queued = new ArrayList<>();
    private final BucketSorting bucketSorting = mock(BucketSorting.class);
    /** Recurring re-checks, with how long each waits, held until the test runs them. */
    private final List<Runnable> retries = new ArrayList<>();
    private final List<Duration> retryDelays = new ArrayList<>();
    private final List<ScheduledFuture<?>> retryFutures = new ArrayList<>();
    private PlaidItemSync itemSync;
    private UUID userId;

    @BeforeEach
    void setUp() throws IOException {
        itemSync = new PlaidItemSync(
                items, accountsSync, transactionsSync, recurringStreamsSync, bucketSorting, balanceHistory,
                CLOCK, transactionTemplate, queued::add, retryScheduler());
        userId = users.saveAndFlush(new User("item-sync-test-user")).getId();
        items.saveAndFlush(new PlaidItem(
                ITEM_ID, tokenEncryption.encrypt("access-token", userId, ITEM_ID), userId));
        entityManager.clear();
        stubAccounts(List.of(Products.TRANSACTIONS));
    }

    @Test
    void storesAccountsTransactionsAndStreamsWhenAnItemIsLinked() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(recurringResponse());

        itemSync.linked(storedItem());

        verify(bucketSorting).sortLater(userId);
        assertEquals(List.of("checking"), accountIds());
        assertEquals(List.of("txn-1"), transactionIds());
        assertEquals(List.of("rent"), streamIds());
        PlaidItem item = storedItem();
        assertEquals("cursor-1", item.getTransactionsCursor());
        assertNotNull(item.getRecurringSyncedAt());
    }

    @Test
    void recordsTodaysBalancesAfterSyncing() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(recurringResponse());

        itemSync.linked(storedItem());

        List<Point> netWorth = balanceHistory.forUser(userId, null, TODAY).netWorth();
        assertEquals(1, netWorth.size());
        assertEquals(TODAY, netWorth.get(0).day());
        assertEquals(0, new BigDecimal("2500").compareTo(netWorth.get(0).value()));
    }

    @Test
    void anItemWithoutTransactionsOnlySyncsAccountsAndBalances() throws IOException {
        stubAccounts(List.of(Products.INVESTMENTS));

        itemSync.linked(storedItem());

        assertEquals(List.of("checking"), accountIds());
        assertEquals(1, balanceHistory.forUser(userId, null, TODAY).netWorth().size());
        verify(plaidApi, never()).transactionsSync(any());
        verify(plaidApi, never()).transactionsRecurringGet(any());
        verifyNoInteractions(bucketSorting);
    }

    @Test
    void syncsOnlyAccountsAndBalancesWhenPlaidReportsNewHoldings() throws IOException {
        itemSync.notified(ITEM_ID, "HOLDINGS", "DEFAULT_UPDATE");
        runQueued();

        assertEquals(List.of("checking"), accountIds());
        assertEquals(1, balanceHistory.forUser(userId, null, TODAY).netWorth().size());
        verify(plaidApi, never()).transactionsSync(any());
        verify(plaidApi, never()).transactionsRecurringGet(any());
    }

    @Test
    void stillSyncsTransactionsWhenPlaidCannotListTheAccounts() throws IOException {
        stubAccountsFailure();
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(recurringResponse());

        itemSync.linked(storedItem());

        assertEquals(List.of("txn-1"), transactionIds());
        assertEquals(List.of("rent"), streamIds());
    }

    @Test
    void aRemovedItemKeepsItsAccountsAsDroppedButLosesItsTransactionsAndStreams() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(recurringResponse());
        itemSync.linked(storedItem());

        itemSync.removed(ITEM_ID);

        entityManager.flush();
        entityManager.clear();
        assertEquals(TODAY, accounts.findById("checking").orElseThrow().getDroppedOn());
        assertTrue(transactionIds().isEmpty());
        assertTrue(streamIds().isEmpty());
        assertTrue(storedItem().isRemoved());
    }

    @Test
    void ignoresWebhooksForARemovedItem() throws IOException {
        itemSync.removed(ITEM_ID);

        itemSync.notified(ITEM_ID, "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE");
        runQueued();

        verifyNoInteractions(plaidApi);
    }

    @Test
    void stillSyncsStreamsWhenTheTransactionsSyncFails() throws IOException {
        stubTransactionsSyncFailure();
        stubRecurring(recurringResponse());

        itemSync.linked(storedItem());

        assertTrue(transactionIds().isEmpty());
        assertEquals(List.of("rent"), streamIds());
    }

    @Test
    void queuesBothSyncsWhenPlaidReportsNewTransactions() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(recurringResponse());

        itemSync.notified(ITEM_ID, "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE");
        verifyNoInteractions(plaidApi);
        runQueued();

        assertEquals(List.of("txn-1"), transactionIds());
        assertEquals(List.of("rent"), streamIds());
    }

    @Test
    void syncsOnlyStreamsWhenPlaidReportsARecurringUpdate() throws IOException {
        stubRecurring(recurringResponse());

        itemSync.notified(ITEM_ID, "RECURRING_TRANSACTIONS", "RECURRING_TRANSACTIONS_UPDATE");
        runQueued();

        assertEquals(List.of("rent"), streamIds());
        verify(plaidApi, never()).transactionsSync(any());
        verifyNoInteractions(bucketSorting);
    }

    @Test
    void ignoresWebhooksThatNeedNoSync() {
        itemSync.notified(ITEM_ID, "ITEM", "ERROR");
        // The legacy /transactions/get integration; this app uses /transactions/sync.
        itemSync.notified(ITEM_ID, "TRANSACTIONS", "DEFAULT_UPDATE");
        itemSync.notified(null, "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE");

        assertTrue(queued.isEmpty());
    }

    @Test
    void ignoresWebhooksForItemsWeDoNotStore() {
        itemSync.notified("unknown-item", "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE");
        runQueued();

        verifyNoInteractions(plaidApi);
    }

    @Test
    void recurringSyncHoldingAnOlderCopyOfTheItemKeepsTheNewerCursor() throws IOException {
        PlaidItem olderCopy = storedItem();
        entityManager.detach(olderCopy);
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(recurringResponse());

        transactionsSync.sync(storedItem());
        recurringStreamsSync.sync(olderCopy);

        PlaidItem item = storedItem();
        assertEquals("cursor-1", item.getTransactionsCursor());
        assertNotNull(item.getRecurringSyncedAt());
    }

    @Test
    void checksAgainLaterWhilePlaidHasNoRecurringStreams() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(noStreams());

        itemSync.linked(storedItem());

        assertTrue(streamIds().isEmpty());
        assertEquals(List.of(Duration.ofMinutes(2)), retryDelays);

        stubRecurring(recurringResponse());
        runRetries();

        assertEquals(List.of("rent"), streamIds());
        assertEquals(List.of(Duration.ofMinutes(2)), retryDelays);
    }

    @Test
    void checksAgainLaterWhenPlaidSaysRecurringStreamsAreNotReady() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurringNotReady();

        itemSync.linked(storedItem());

        assertEquals(List.of(Duration.ofMinutes(2)), retryDelays);
    }

    @Test
    void waitsLongerBetweenChecksAndThenStops() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(noStreams());

        itemSync.linked(storedItem());
        for (int i = 0; i < 5; i++) {
            runRetries();
        }

        assertEquals(List.of(Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30),
                Duration.ofHours(2)), retryDelays);
        assertTrue(retries.isEmpty());
    }

    @Test
    void keepsOneCheckPendingWhenAWebhookAlsoFindsNoStreams() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(noStreams());

        itemSync.linked(storedItem());
        itemSync.notified(ITEM_ID, "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE");
        runQueued();

        assertEquals(1, retries.size());
    }

    @Test
    void recheckingOnRequestStoresStreamsPlaidHasSinceFound() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(noStreams());
        itemSync.linked(storedItem());

        stubRecurring(recurringResponse());
        itemSync.recheckRecurring(ITEM_ID);

        assertEquals(List.of("rent"), streamIds());
        verify(plaidApi).transactionsSync(any());
    }

    @Test
    void recheckingOnRequestReportsWhenPlaidFails() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurringNotReady();

        assertThrows(PlaidRequestException.class, () -> itemSync.recheckRecurring(ITEM_ID));
        assertEquals(List.of(Duration.ofMinutes(2)), retryDelays);
    }

    @Test
    void cancelsThePendingCheckOnceStreamsTurnUp() throws IOException {
        stubTransactionsSync(transactionsPage("cursor-1"));
        stubRecurring(noStreams());
        itemSync.linked(storedItem());

        stubRecurring(recurringResponse());
        itemSync.recheckRecurring(ITEM_ID);

        verify(retryFutures.get(0)).cancel(false);
    }

    private void runQueued() {
        List<Runnable> tasks = List.copyOf(queued);
        queued.clear();
        tasks.forEach(Runnable::run);
    }

    /** Reads the item back from the database, after writing out anything still pending. */
    private PlaidItem storedItem() {
        entityManager.flush();
        entityManager.clear();
        return items.findById(ITEM_ID).orElseThrow();
    }

    private List<String> accountIds() {
        return accounts.findAllByUserIdOrderByNameAscAccountIdAsc(userId).stream()
                .map(account -> account.getAccountId())
                .toList();
    }

    private List<String> transactionIds() {
        return transactions.findRecent(userId, Set.of("checking"), Pageable.ofSize(10)).stream()
                .map(transaction -> transaction.getTransactionId())
                .toList();
    }

    private List<String> streamIds() {
        return streams.findAllByUserId(userId).stream()
                .map(PlaidRecurringStream::getStreamId)
                .toList();
    }

    private static TransactionsSyncResponse transactionsPage(String nextCursor) {
        return new TransactionsSyncResponse()
                .accounts(List.of(new AccountBase()
                        .accountId("checking")
                        .name("Checking")
                        .type(AccountType.DEPOSITORY)))
                .added(List.of(new Transaction()
                        .transactionId("txn-1")
                        .accountId("checking")
                        .amount(12.34)
                        .date(LocalDate.of(2026, 9, 1))))
                .nextCursor(nextCursor)
                .hasMore(false);
    }

    private TaskScheduler retryScheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(invocation -> {
            retries.add(invocation.getArgument(0));
            retryDelays.add(Duration.between(CLOCK.instant(), invocation.<Instant>getArgument(1)));
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            retryFutures.add(future);
            return future;
        }).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        return scheduler;
    }

    private void runRetries() {
        List<Runnable> due = List.copyOf(retries);
        retries.clear();
        due.forEach(Runnable::run);
    }

    private static TransactionsRecurringGetResponse noStreams() {
        return new TransactionsRecurringGetResponse().outflowStreams(List.of()).inflowStreams(List.of());
    }

    private static TransactionsRecurringGetResponse recurringResponse() {
        return new TransactionsRecurringGetResponse()
                .outflowStreams(List.of(new TransactionStream()
                        .streamId("rent")
                        .accountId("checking")
                        .merchantName("Landlord")
                        .lastAmount(new TransactionStreamAmount().amount(1450.0))
                        .frequency(RecurringTransactionFrequency.MONTHLY)
                        .isActive(true)))
                .inflowStreams(List.of());
    }

    @SuppressWarnings("unchecked")
    private void stubAccounts(List<Products> products) throws IOException {
        Call<AccountsGetResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(new AccountsGetResponse()
                .accounts(List.of(new AccountBase()
                        .accountId("checking")
                        .name("Checking")
                        .type(AccountType.DEPOSITORY)
                        .balances(new AccountBalance().current(2500.0).isoCurrencyCode("USD"))))
                .item(new Item().itemId(ITEM_ID).products(products))));
        when(plaidApi.accountsGet(any(AccountsGetRequest.class))).thenReturn(call);
    }

    @SuppressWarnings("unchecked")
    private void stubAccountsFailure() throws IOException {
        Call<AccountsGetResponse> call = mock(Call.class);
        when(call.execute()).thenThrow(new IOException("Plaid unreachable"));
        when(plaidApi.accountsGet(any(AccountsGetRequest.class))).thenReturn(call);
    }

    @SuppressWarnings("unchecked")
    private void stubTransactionsSync(TransactionsSyncResponse response) throws IOException {
        Call<TransactionsSyncResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(response));
        when(plaidApi.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(call);
    }

    @SuppressWarnings("unchecked")
    private void stubTransactionsSyncFailure() throws IOException {
        Call<TransactionsSyncResponse> call = mock(Call.class);
        when(call.execute()).thenThrow(new IOException("Plaid unreachable"));
        when(plaidApi.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(call);
    }

    @SuppressWarnings("unchecked")
    private void stubRecurringNotReady() throws IOException {
        Call<TransactionsRecurringGetResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.error(400, ResponseBody.create(
                "{\"error_type\":\"ITEM_ERROR\",\"error_code\":\"PRODUCT_NOT_READY\"}",
                MediaType.get("application/json"))));
        when(plaidApi.transactionsRecurringGet(any(TransactionsRecurringGetRequest.class)))
                .thenReturn(call);
    }

    @SuppressWarnings("unchecked")
    private void stubRecurring(TransactionsRecurringGetResponse response) throws IOException {
        Call<TransactionsRecurringGetResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(response));
        when(plaidApi.transactionsRecurringGet(any(TransactionsRecurringGetRequest.class)))
                .thenReturn(call);
    }
}
