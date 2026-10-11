package dev.matthewsawyer.finance_dashboard.plaid;

import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
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
        ReentrantLock lock = local[Math.floorMod(itemId.hashCode(), local.length)];
        if (!lock.tryLock()) return false;
        if (!slots.tryAcquire()) { lock.unlock(); return false; }
        try (Connection connection = dataSource.getConnection()) {
            // H2 is used by the test suite; production uses PostgreSQL.
            if ("H2".equals(connection.getMetaData().getDatabaseProductName())) {
                work.run();
                return true;
            }
            if (!advisory(connection, "pg_try_advisory_lock", itemId)) return false;
            try {
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
        } catch (SQLException e) {
            throw new IllegalStateException("Could not lock Plaid item for sync", e);
        } finally {
            slots.release();
            lock.unlock();
        }
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
