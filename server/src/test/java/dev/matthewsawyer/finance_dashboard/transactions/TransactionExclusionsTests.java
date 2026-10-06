package dev.matthewsawyer.finance_dashboard.transactions;

import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.repository.TransactionCountingRepository;
import dev.matthewsawyer.finance_dashboard.spending.Spending;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"PLAID_CLIENT_ID=test-client-id", "PLAID_SANDBOX_SECRET=test-secret"})
@Transactional
class TransactionExclusionsTests {
    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private TransactionCountingRepository choices;
    @Autowired private EntityManager em;
    private final UUID user = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private TransactionExclusions exclusions;

    @BeforeEach
    void setup() {
        exclusions = new TransactionExclusions(transactions, choices, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void removesOnlyTheChosenTransactionFromSpendingAndCanRestoreIt() {
        charge("one", "Shop", NOW.minusSeconds(60));
        charge("two", "Shop", NOW.minusSeconds(60));
        exclusions.set(user, "one", true, false);
        TrackedAccounts accounts = mock(TrackedAccounts.class);
        when(accounts.shares(user, null)).thenReturn(Map.of("checking", 100));
        Spending spending = new Spending(transactions, accounts, exclusions);
        var found = spending.between(user, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
        assertEquals(List.of("two"), found.stream().map(s -> s.transaction().getTransactionId()).toList());
        assertEquals(0, BigDecimal.TEN.compareTo(found.get(0).amount()));
        assertEquals(2, transactions.findAllByUserId(user).size());
        exclusions.set(user, "one", false, false);
        assertFalse(excluded("one"));
    }

    @Test
    void appliesFutureRulesOnlyToNewMatchingTransactionsAndPreservesHistoryOnUndo() {
        charge("chosen", "Shop", NOW.minusSeconds(60));
        charge("earlier", "Shop", NOW.minusSeconds(60));
        exclusions.set(user, "chosen", true, true);
        charge("later", "Shop", NOW.plusSeconds(60));
        charge("other", "Another shop", NOW.plusSeconds(60));
        assertTrue(excluded("chosen"));
        assertFalse(excluded("earlier"));
        assertTrue(excluded("later"));
        assertFalse(excluded("other"));

        exclusions.set(user, "chosen", false, true);
        charge("after-undo", "Shop", NOW.plusSeconds(120));
        assertFalse(excluded("chosen"));
        assertTrue(excluded("later"));
        assertFalse(excluded("after-undo"));
    }

    @Test
    void restoringOneTransactionLeavesTheFutureRuleInPlace() {
        charge("chosen", "Shop", NOW.minusSeconds(60));
        exclusions.set(user, "chosen", true, true);
        charge("later", "Shop", NOW.plusSeconds(60));
        exclusions.set(user, "later", false, false);
        charge("next", "Shop", NOW.plusSeconds(120));
        assertFalse(excluded("later"));
        assertTrue(excluded("next"));
    }

    @Test
    void survivesSyncReplacementAndPendingToPostedIds() {
        charge("pending", "Shop", NOW.minusSeconds(60));
        exclusions.set(user, "pending", true, false);
        transactions.saveAndFlush(new PlaidTransaction("pending", "item", user, "checking", BigDecimal.ONE,
                LocalDate.of(2026, 10, 6)).merchantName("Shop"));
        em.clear();
        assertTrue(excluded("pending"));
        transactions.saveAndFlush(new PlaidTransaction("posted", "item", user, "checking", BigDecimal.ONE,
                LocalDate.of(2026, 10, 6)).merchantName("Shop").pendingTransactionId("pending"));
        transactions.deleteById("pending");
        em.flush();
        em.clear();
        assertTrue(excluded("posted"));
        exclusions.set(user, "posted", false, false);
        assertFalse(excluded("posted"));
    }

    @Test
    void scopesChoicesAndEditsToTheirOwner() {
        charge("one", "Shop", NOW.minusSeconds(60));
        UUID otherUser = UUID.randomUUID();
        var error = assertThrows(ResponseStatusException.class, () -> exclusions.set(otherUser, "one", true, true));
        assertEquals(404, error.getStatusCode().value());
        exclusions.set(user, "one", true, true);
        assertFalse(exclusions.forUser(otherUser).excluded(transactions.findById("one").orElseThrow()));
    }

    @Test
    void permitsUnnamedTransactionsButRejectsUnnamedFutureRules() {
        charge("unnamed", null, NOW.minusSeconds(60));
        exclusions.set(user, "unnamed", true, false);
        assertTrue(excluded("unnamed"));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> exclusions.set(user, "unnamed", true, true)).getStatusCode().value());
    }

    private boolean excluded(String id) {
        return exclusions.forUser(user).excluded(transactions.findById(id).orElseThrow());
    }

    private void charge(String id, String merchant, Instant createdAt) {
        transactions.saveAndFlush(new PlaidTransaction(id, "item", user, "checking", BigDecimal.TEN,
                LocalDate.of(2026, 10, 6)).merchantName(merchant).personalFinanceCategory("GENERAL_MERCHANDISE", null));
        transactions.setBuckets(List.of(id), Bucket.GUILT_FREE, createdAt);
        em.createNativeQuery("UPDATE transactions SET created_at = :created WHERE transaction_id = :id")
                .setParameter("created", createdAt).setParameter("id", id).executeUpdate();
        em.clear();
    }
}
