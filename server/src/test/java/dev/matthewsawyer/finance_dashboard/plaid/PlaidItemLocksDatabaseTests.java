package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Run against an isolated PostgreSQL database as documented in docs/plaid-api-usage.md. */
@SpringBootTest(properties = {"PLAID_CLIENT_ID=test-client-id", "PLAID_SANDBOX_SECRET=test-secret"})
class PlaidItemLocksDatabaseTests {
    @Autowired DataSource source;
    @MockitoBean PlaidApi plaidApi;

    @Test
    void independentWorkersShareTheDatabaseLockAndReleaseItAfterFailure() throws Exception {
        try (var connection = source.getConnection()) {
            Assumptions.assumeTrue("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()));
        }
        PlaidItemLocks first = new PlaidItemLocks(source);
        PlaidItemLocks second = new PlaidItemLocks(source);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var running = executor.submit(() -> assertThrows(IllegalStateException.class, () -> first.run("shared-item", () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                throw new IllegalStateException("simulated failed sync");
            })));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(second.run("shared-item", () -> fail("Overlapping database workers")));
                assertTrue(second.run("different-item", () -> {}));
                assertTimeout(Duration.ofSeconds(1), () -> assertFalse(second.run("shared-item",
                        Duration.ofMillis(100), () -> fail("Busy advisory lock"))));
                var waiting = executor.submit(() -> second.run("shared-item", Duration.ofSeconds(2), () -> {}));
                assertThrows(TimeoutException.class, () -> waiting.get(50, TimeUnit.MILLISECONDS));
                release.countDown();
                assertTrue(waiting.get(3, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            running.get(5, TimeUnit.SECONDS);
            assertTrue(second.run("shared-item", () -> {}));
        }
    }
}
