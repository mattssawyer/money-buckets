package dev.matthewsawyer.finance_dashboard.payees;

import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.repository.PayeeCorrectionRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class PayeeCorrectionsTests {

    @Autowired private PayeeCorrections corrections;
    @Autowired private PayeeCorrectionRepository correctionRepository;
    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private EntityManager entityManager;

    private final UUID userId = UUID.randomUUID();

    @Test
    void movesThePayeesChargesToTheBucketStraightAway() {
        charge("rent-sep", "Sterling Group", "1554.91", Bucket.GUILT_FREE);
        charge("rent-aug", "Sterling Group", "1554.91", Bucket.GUILT_FREE);
        charge("coffee", "Starbucks", "6.45", Bucket.GUILT_FREE);

        corrections.correct(userId, "sterling group", Bucket.FIXED_COSTS, "RENT_AND_UTILITIES");

        assertEquals(Bucket.FIXED_COSTS, bucket("rent-sep"));
        assertEquals(Bucket.FIXED_COSTS, bucket("rent-aug"));
        assertEquals(Bucket.GUILT_FREE, bucket("coffee"));
        assertEquals("RENT_AND_UTILITIES", corrections.byPayee(userId).get("sterling group").getCategory());
    }

    @Test
    void replacesAnEarlierCorrection() {
        corrections.correct(userId, "sterling group", Bucket.SAVINGS, null);
        corrections.correct(userId, "sterling group", Bucket.FIXED_COSTS, "RENT_AND_UTILITIES");

        assertEquals(1, correctionRepository.findAllByUserId(userId).size());
        assertEquals(Bucket.FIXED_COSTS, corrections.byPayee(userId).get("sterling group").getBucket());
    }

    @Test
    void leavesTheChargesForSortingToDecideAgainOnUndo() {
        charge("rent", "Sterling Group", "1554.91", Bucket.GUILT_FREE);
        corrections.correct(userId, "sterling group", Bucket.FIXED_COSTS, null);

        corrections.undo(userId, "sterling group");

        assertTrue(corrections.byPayee(userId).isEmpty());
        assertNull(bucket("rent"));
    }

    @Test
    void keepsTheSortedBucketWhenOnlyTheCategoryWasCorrected() {
        charge("rent", "Sterling Group", "1554.91", Bucket.FIXED_COSTS);
        corrections.correct(userId, "sterling group", null, "RENT_AND_UTILITIES");

        corrections.undo(userId, "sterling group");

        assertEquals(Bucket.FIXED_COSTS, bucket("rent"));
    }

    @Test
    void correctsOnlySpendingNotPay() {
        PlaidTransaction pay = new PlaidTransaction("pay", "item", userId, "checking", new BigDecimal("-2400"),
                LocalDate.now()).name("ACME PAYROLL").personalFinanceCategory("INCOME", "INCOME_WAGES");

        assertNull(PayeeCorrections.payeeKey(pay));
    }

    private void charge(String id, String merchant, String amount, Bucket bucket) {
        transactions.saveAndFlush(new PlaidTransaction(id, "item", userId, "checking", new BigDecimal(amount),
                LocalDate.now()).merchantName(merchant));
        entityManager.clear();
        transactions.updateBucket(id, transactions.findById(id).orElseThrow().getUpdatedAt(), bucket);
        entityManager.clear();
    }

    private Bucket bucket(String id) {
        entityManager.clear();
        return transactions.findById(id).orElseThrow().getBucket();
    }
}
