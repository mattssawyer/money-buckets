package dev.matthewsawyer.finance_dashboard.plaid;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void boundedWaitAcquiresTheStripedLockAfterReleaseAndTimesOutOtherwise() throws Exception {
        PlaidItemLocks locks = h2Locks();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> locks.run("item", () -> awaitRelease(entered, release)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTimeout(Duration.ofSeconds(1), () ->
                        assertFalse(locks.run("item", Duration.ofMillis(50), () -> fail("Busy lock"))));
                var waiting = executor.submit(() -> locks.run("item", Duration.ofSeconds(2), () -> {}));
                assertThrows(TimeoutException.class, () -> waiting.get(50, TimeUnit.MILLISECONDS));
                release.countDown();
                assertTrue(waiting.get(3, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            assertTrue(holder.get(5, TimeUnit.SECONDS));
            assertTrue(locks.run("item", () -> {}));
        }
    }

    @Test
    void boundedWaitAlsoWaitsForACapacityPermitAndReleasesItsLockOnTimeout() throws Exception {
        PlaidItemLocks locks = h2Locks();
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(5)) {
            try {
                for (int i = 0; i < 4; i++) {
                    String id = "slot-" + i;
                    executor.submit(() -> locks.run(id, () -> awaitRelease(entered, release)));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(locks.run("slot-4", () -> fail("No free permit")));
                assertTimeout(Duration.ofSeconds(1), () ->
                        assertFalse(locks.run("slot-4", Duration.ofMillis(50), () -> fail("No free permit"))));
                var waiting = executor.submit(() -> locks.run("slot-4", Duration.ofSeconds(2), () -> {}));
                assertThrows(TimeoutException.class, () -> waiting.get(50, TimeUnit.MILLISECONDS));
                release.countDown();
                assertTrue(waiting.get(3, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
        assertTrue(locks.run("slot-4", () -> {}));
    }

    @Test
    void interruptedWaitPreservesInterruptStatusAndDoesNotRunWork() throws Exception {
        PlaidItemLocks locks = h2Locks();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean ran = new AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> locks.run("item", () -> awaitRelease(entered, release)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Thread waiting = new Thread(() -> {
                    locks.run("item", Duration.ofSeconds(2), () -> ran.set(true));
                    interrupted.set(Thread.currentThread().isInterrupted());
                });
                waiting.start();
                waiting.interrupt();
                waiting.join(1000);
                assertFalse(waiting.isAlive());
                assertTrue(interrupted.get());
                assertFalse(ran.get());
            } finally { release.countDown(); }
            assertTrue(holder.get(5, TimeUnit.SECONDS));
        }
        assertTrue(locks.run("item", () -> {}));
    }

    private static PlaidItemLocks h2Locks() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        return new PlaidItemLocks(source);
    }

    private static void awaitRelease(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
    }

}
