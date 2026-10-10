package dev.matthewsawyer.finance_dashboard.plaid;

import dev.matthewsawyer.finance_dashboard.history.BalanceHistory;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.mockito.stubbing.Answer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaidItemSyncLockTests {

    @Test
    void syncsOfTheSameItemNeverOverlap() throws InterruptedException {
        PlaidItem item = new PlaidItem("item-id", "encrypted", UUID.randomUUID());
        PlaidItemRepository items = mock(PlaidItemRepository.class);
        when(items.findById("item-id")).thenReturn(Optional.of(item));

        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        Answer<Object> slowSync = invocation -> {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(20);
            running.decrementAndGet();
            finished.incrementAndGet();
            return null;
        };
        TransactionsSync transactionsSync = mock(TransactionsSync.class);
        RecurringStreamsSync recurringStreamsSync = mock(RecurringStreamsSync.class);
        doAnswer(slowSync).when(transactionsSync).sync(any());
        doAnswer(slowSync).when(recurringStreamsSync).sync(any());

        AccountsSync accountsSync = mock(AccountsSync.class);
        when(accountsSync.sync(any(), any())).thenReturn(true);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        PlaidItemSync itemSync = new PlaidItemSync(
                items, accountsSync, transactionsSync, recurringStreamsSync, mock(BucketSorting.class),
                mock(BalanceHistory.class), Clock.systemUTC(),
                new TransactionTemplate(mock(PlatformTransactionManager.class)), executor, mock(TaskScheduler.class, RETURNS_MOCKS));

        for (int i = 0; i < 4; i++) {
            itemSync.notified("item-id", "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", null);
        }
        itemSync.linked(item);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(10, finished.get());
        assertEquals(1, mostAtOnce.get());
    }
}
