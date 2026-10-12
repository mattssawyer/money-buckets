package dev.matthewsawyer.finance_dashboard.plaid;

import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** PostgreSQL session locks serialize Items across replicas and release on connection/process loss. */
@Component
class PlaidItemLocks {
    private final DataSource dataSource;
    // Leave connections available for the short transactions performed while holding a session lock.
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(4);
    private final ReentrantLock[] local = new ReentrantLock[256];

    PlaidItemLocks(DataSource dataSource) {
        this.dataSource = dataSource;
        for (int i = 0; i < local.length; i++) local[i] = new ReentrantLock();
    }

    boolean run(String itemId, Runnable work) {
        return run(itemId, Duration.ZERO, work);
    }

    /** One contention budget shared by all three locks; it does not limit the work itself. */
    boolean run(String itemId, Duration wait, Runnable work) {
        if (wait.isNegative()) throw new IllegalArgumentException("Lock wait must not be negative");
        boolean immediate = wait.isZero();
        long deadline = System.nanoTime() + wait.toNanos();
        ReentrantLock lock = local[Math.floorMod(itemId.hashCode(), local.length)];
        boolean locked = false;
        boolean permitted = false;
        try {
            locked = immediate ? lock.tryLock() : lock.tryLock(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!locked) return false;
            permitted = immediate ? slots.tryAcquire() : slots.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!permitted || (!immediate && remaining(deadline) == 0)) return false;
            try (Connection connection = dataSource.getConnection()) {
                if (!immediate && remaining(deadline) == 0) return false;
                // H2 is used by the test suite; production uses PostgreSQL.
                if ("H2".equals(connection.getMetaData().getDatabaseProductName())) {
                    work.run();
                    return true;
                }
                while (!advisory(connection, "pg_try_advisory_lock", itemId)) {
                    long left = remaining(deadline);
                    if (immediate || left == 0) return false;
                    TimeUnit.NANOSECONDS.sleep(Math.min(left, TimeUnit.MILLISECONDS.toNanos(50)));
                    if (remaining(deadline) == 0) return false;
                }
                try {
                    if (!immediate && remaining(deadline) == 0) return false;
                    work.run();
                    return true;
                } finally {
                    try {
                        advisory(connection, "pg_advisory_unlock", itemId);
                    } catch (SQLException e) {
                        // Never put a session with an unknown lock state back into the pool.
                        connection.abort(Runnable::run);
                        throw e;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not lock Plaid item for sync", e);
        } finally {
            if (permitted) slots.release();
            if (locked) lock.unlock();
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private boolean advisory(Connection connection, String function, String itemId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT " + function + "(hashtextextended(?, 0))")) {
            statement.setString(1, "plaid-sync:" + itemId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
