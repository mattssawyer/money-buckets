package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class PlaidTransactionRepositoryTests {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);

    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private EntityManager entityManager;

    private final UUID userId = UUID.randomUUID();

    @Test
    void findsSpendingButNotPayCardPaymentsOrMovesBetweenOwnAccounts() {
        store("rent", "3200", "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT", Bucket.FIXED_COSTS);
        store("coffee", "6", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE", null);
        store("no-category", "10", null, null, Bucket.GUILT_FREE);
        store("to-checking", "500", "TRANSFER_OUT", "TRANSFER_OUT_ACCOUNT_TRANSFER", Bucket.NOT_COUNTED);
        store("pay", "-4000", "INCOME", "INCOME_SALARY", null);
        store("card-payment", "900", "LOAN_PAYMENTS", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT", null);

        Set<String> found = transactions
                .findSpending(userId, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), Set.of("checking"),
                        BucketSorting.NOT_PLAN_MONEY)
                .stream()
                .map(PlaidTransaction::getTransactionId)
                .collect(Collectors.toSet());

        assertEquals(Set.of("rent", "coffee", "no-category"), found);
    }

    @Test
    void findsSpendingOnlyFromTheAccountsAsked() {
        store("coffee", "6", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE", null);
        store("groceries", "joint", "80", "FOOD_AND_DRINK", "FOOD_AND_DRINK_GROCERIES", null);
        store("broker-fee", "brokerage", "5", "BANK_FEES", "BANK_FEES_OTHER_BANK_FEES", null);

        Set<String> found = transactions
                .findSpending(userId, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), Set.of("checking", "joint"),
                        BucketSorting.NOT_PLAN_MONEY)
                .stream()
                .map(PlaidTransaction::getTransactionId)
                .collect(Collectors.toSet());

        assertEquals(Set.of("coffee", "groceries"), found);
    }

    @Test
    void findsMoneyTransferredIntoTheAccountsAsked() {
        store("from-checking", "joint", "-600", "TRANSFER_IN", "TRANSFER_IN_ACCOUNT_TRANSFER", null);
        store("into-brokerage", "brokerage", "-600", "TRANSFER_IN", "TRANSFER_IN_ACCOUNT_TRANSFER", null);
        store("pay", "-4000", "INCOME", "INCOME_SALARY", null);

        List<String> found = transactions
                .findTransfersIn(userId, Set.of("checking", "joint"), DAY.minusDays(3), DAY.plusDays(3))
                .stream()
                .map(PlaidTransaction::getTransactionId)
                .toList();

        assertEquals(List.of("from-checking"), found);
    }

    private void store(String id, String amount, String primary, String detailed, Bucket bucket) {
        store(id, "checking", amount, primary, detailed, bucket);
    }

    private void store(String id, String accountId, String amount, String primary, String detailed, Bucket bucket) {
        transactions.saveAndFlush(new PlaidTransaction(id, "item", userId, accountId, new BigDecimal(amount), DAY)
                .personalFinanceCategory(primary, detailed));
        entityManager.clear();
        if (bucket != null) {
            PlaidTransaction stored = transactions.findById(id).orElseThrow();
            transactions.updateBucket(id, stored.getUpdatedAt(), bucket);
        }
    }
}
