package dev.matthewsawyer.finance_dashboard.plaid;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaidItemSyncLockTests {
    @Test
    void busyItemsAreSkippedAndCanRunAgainAfterRelease() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        PlaidItemLocks locks = new PlaidItemLocks(source);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> locks.run("item", () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(locks.run("item", () -> fail("Concurrent sync")));
            } finally { release.countDown(); }
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(locks.run("item", () -> {}));
        }
    }
}
