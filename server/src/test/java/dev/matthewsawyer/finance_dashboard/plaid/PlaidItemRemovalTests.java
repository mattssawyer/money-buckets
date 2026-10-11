package dev.matthewsawyer.finance_dashboard.plaid;

import dev.matthewsawyer.finance_dashboard.history.BalanceHistory;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PlaidItemRemovalTests {

    @Test
    void rollsBackEveryStepWhenOneFails() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        RecurringStreamsSync recurringStreamsSync = mock(RecurringStreamsSync.class);
        doThrow(new IllegalStateException("database unavailable")).when(recurringStreamsSync).forget("item-id");
        PlaidItemRepository items = mock(PlaidItemRepository.class);
        PlaidItemLocks locks = mock(PlaidItemLocks.class);
        doAnswer(invocation -> { invocation.<Runnable>getArgument(1).run(); return true; }).when(locks).run(any(), any());
        PlaidItemSync itemSync = new PlaidItemSync(
                items, mock(AccountsSync.class), mock(TransactionsSync.class), recurringStreamsSync,
                mock(BucketSorting.class), mock(BalanceHistory.class), Clock.systemUTC(),
                new TransactionTemplate(transactionManager), Runnable::run, mock(PlaidSyncJobs.class), locks, mock(com.plaid.client.request.PlaidApi.class), mock(PlaidTokenEncryption.class));

        assertThrows(IllegalStateException.class, () -> itemSync.removed("item-id"));

        verify(items).markRemoved(any(), any());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }
}
