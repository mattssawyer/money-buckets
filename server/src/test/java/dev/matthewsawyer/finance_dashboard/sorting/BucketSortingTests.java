package dev.matthewsawyer.finance_dashboard.sorting;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.payees.PayeeCorrections;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayees;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.SpendingPlanService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class BucketSortingTests {

    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private SpendingPlanService planService;
    @Autowired private EntityManager entityManager;
    @Autowired private PayeeCorrections corrections;

    private final TypeSafeClient typeSafe = mock(TypeSafeClient.class);
    private final RecurringPayees payees = mock(RecurringPayees.class);
    private BucketSorting sorting;
    private UUID userId;

    @BeforeEach
    void setUp() {
        // Jobs and questions run inline so each test sees the finished sort.
        sorting = new BucketSorting(
                transactions, planService, new BucketClassifier(typeSafe), corrections, payees,
                Runnable::run, Runnable::run);
        userId = UUID.randomUUID();
        when(typeSafe.isConfigured()).thenReturn(true);
    }

    @Test
    void storesTheBucketTypeSafeChooses() {
        store(transaction("rent", "Rent ACH", "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"));
        store(transaction("coffee", "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        answer(Map.of("Rent ACH", "fixed_costs", "Starbucks", "guilt_free"));

        sorting.sortNow(userId);

        assertEquals(Bucket.FIXED_COSTS, bucket("rent"));
        assertEquals(Bucket.GUILT_FREE, bucket("coffee"));
    }

    @Test
    void asksAboutManyTransactionsInSeveralRequests() {
        for (int i = 0; i <= BucketClassifier.PER_REQUEST; i++) {
            store(transaction("coffee-" + i, "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        }
        answer(Map.of("Starbucks", "guilt_free"));

        sorting.sortNow(userId);

        verify(typeSafe, times(2)).ask(any(), any());
        assertEquals(Bucket.GUILT_FREE, bucket("coffee-0"));
        assertEquals(Bucket.GUILT_FREE, bucket("coffee-" + BucketClassifier.PER_REQUEST));
    }

    @Test
    void putsEachTransactionInItsOwnQuestionWithAnEmptyState() {
        savePlan("Subscriptions");
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"));

        sorting.sortNow(userId);

        ArgumentCaptor<Object> state = ArgumentCaptor.forClass(Object.class);
        verify(typeSafe).ask(state.capture(), any());
        assertEquals(Map.of(), state.getValue());
        assertEquals(List.of("transaction", "spending_plan", "question", "guidance"),
                List.copyOf(askedInstructions().keySet()));
    }

    @Test
    void keepsTheBucketTheUserChoseForAPayeeWithoutAskingJev() {
        corrections.correct(userId, "sterling group", Bucket.FIXED_COSTS, null);
        store(transaction("rent", "Sterling Group", "HOME_IMPROVEMENT", "HOME_IMPROVEMENT_REPAIR_AND_MAINTENANCE"));
        store(transaction("coffee", "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        answer(Map.of("Sterling Group", "guilt_free", "Starbucks", "guilt_free"));

        sorting.sortNow(userId);
        savePlan("Subscriptions");
        sorting.planSaved(userId, PlanLines.NONE);

        assertEquals(Bucket.FIXED_COSTS, bucket("rent"), "a plan save sorting everything again keeps it too");
        assertEquals(Bucket.GUILT_FREE, bucket("coffee"));
        verify(typeSafe, times(2)).ask(any(), any());
    }

    @Test
    void neverSortsPayOrCardPayments() {
        store(pay("pay", "ACME payroll"));
        store(transaction("card", "Card autopay", "LOAN_PAYMENTS", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT"));

        sorting.sortNow(userId);

        verify(typeSafe, never()).ask(any(), any());
        assertNull(bucket("pay"));
        assertNull(bucket("card"));
    }

    @Test
    void judgesRecurringPayeesAfterSorting() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"));
        // Payees are read from sorted spending, so the bucket must be stored first.
        doAnswer(invocation -> {
            assertEquals(Bucket.FIXED_COSTS, bucket("netflix"));
            return null;
        }).when(payees).judge(userId);

        sorting.sortNow(userId);

        verify(payees).judge(userId);
    }

    @Test
    void judgesRecurringPayeesEvenWhenNothingNeedsSorting() {
        sorting.sortNow(userId);

        verify(payees).judge(userId);
    }

    @Test
    void sortsEveryoneDaily() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"));

        sorting.sortDaily();

        assertEquals(Bucket.FIXED_COSTS, bucket("netflix"));
        verify(payees).judge(userId);
    }

    @Test
    void sortsANewlyLinkedBanksTransactionsRightAway() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"));

        sorting.itemSynced(userId, "item");

        assertEquals(Bucket.FIXED_COSTS, bucket("netflix"));
    }

    @Test
    void leavesNewTransactionsFromABankSortedBeforeForTheDailyJob() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs", "Starbucks", "guilt_free"));
        sorting.itemSynced(userId, "item");

        store(transaction("coffee", "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        sorting.itemSynced(userId, "item");

        assertNull(bucket("coffee"));
        verify(typeSafe, times(1)).ask(any(), any());
    }

    @Test
    void givesTypeSafeThePlanLinesOnceAPlanIsSaved() {
        savePlan("Subscriptions");
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"));

        sorting.sortNow(userId);

        Map<String, Object> asked = askedInstructions();
        assertEquals(List.of("Subscriptions"),
                ((BucketClassifier.PlanState) asked.get("spending_plan")).fixedCosts().lines());
        BucketClassifier.TransactionState transaction = (BucketClassifier.TransactionState) asked.get("transaction");
        assertEquals("entertainment: tv and movies", transaction.plaidCategory());
        assertEquals(new BigDecimal("15.49"), transaction.amountUsd());
        assertEquals("money out", transaction.direction());
    }

    @Test
    void asksWithoutPlanContextBeforeAPlanIsSaved() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "guilt_free"));

        sorting.sortNow(userId);

        assertNull(askedInstructions().get("spending_plan"));
        assertEquals(Bucket.GUILT_FREE, bucket("netflix"));
    }

    @Test
    void leavesAFailedRequestsTransactionsUnsorted() {
        for (int i = 0; i <= BucketClassifier.PER_REQUEST; i++) {
            store(transaction("coffee-" + i, "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        }
        when(typeSafe.ask(any(), any())).thenAnswer(invocation -> {
            Map<String, Map<String, Object>> questions = invocation.getArgument(1);
            if (questions.size() > 1) {
                throw new IllegalStateException("TypeSafe unavailable");
            }
            return answers(questions, Map.of("Starbucks", "guilt_free"));
        });

        sorting.sortNow(userId);

        assertNull(bucket("coffee-0"));
        assertEquals(1, transactions.findToSort(userId, true, BucketSorting.NOT_PLAN_MONEY).stream()
                .filter(transaction -> transaction.getBucket() == Bucket.GUILT_FREE)
                .count());
    }

    @Test
    void sortsCorrectedPayeesEvenWithoutAnApiKey() {
        when(typeSafe.isConfigured()).thenReturn(false);
        corrections.correct(userId, "sterling group", Bucket.FIXED_COSTS, null);
        store(transaction("rent", "Sterling Group", "HOME_IMPROVEMENT", "HOME_IMPROVEMENT_REPAIR_AND_MAINTENANCE"));
        store(transaction("coffee", "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));

        sorting.sortNow(userId);

        verify(typeSafe, never()).ask(any(), any());
        assertEquals(Bucket.FIXED_COSTS, bucket("rent"));
        assertNull(bucket("coffee"));
    }

    @Test
    void skipsSortingWithoutAnApiKey() {
        when(typeSafe.isConfigured()).thenReturn(false);
        store(transaction("rent", "Rent ACH", "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"));

        sorting.sortNow(userId);

        verify(typeSafe, never()).ask(any(), any());
        assertNull(bucket("rent"));
    }

    @Test
    void dropsAnAnswerWhenSyncChangedTheTransactionWhileItWasAsked() {
        store(transaction("coffee", "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        when(typeSafe.ask(any(), any())).thenAnswer(invocation -> {
            // Plaid sends a new copy of the transaction mid-sort. A bulk update stands in for sync
            // so the copy sorting already read stays as it was, like it would outside this test.
            entityManager.createQuery("""
                    UPDATE PlaidTransaction t SET t.name = 'Starbucks Reserve', t.updatedAt = :now
                    WHERE t.transactionId = 'coffee'
                    """)
                    .setParameter("now", Instant.now().plusSeconds(1))
                    .executeUpdate();
            return answers(invocation.getArgument(1), Map.of("Starbucks", "guilt_free"));
        });

        sorting.sortNow(userId);

        assertNull(bucket("coffee"));
    }

    @Test
    void sortsEverythingAgainWhenAPlanSaveChangesTheLines() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "guilt_free"));
        sorting.sortNow(userId);

        savePlan("Subscriptions");
        answer(Map.of("Netflix", "fixed_costs"));
        sorting.planSaved(userId, PlanLines.NONE);

        assertEquals(Bucket.FIXED_COSTS, bucket("netflix"));
    }

    @Test
    void leavesSortedTransactionsAloneWhenAPlanSaveKeepsTheLines() {
        savePlan("Subscriptions");
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"));
        sorting.sortNow(userId);

        sorting.planSaved(userId, new PlanLines(List.of("Subscriptions"), List.of(), List.of()));

        verify(typeSafe, times(1)).ask(any(), any());
    }

    private PlaidTransaction transaction(String id, String name, String primary, String detailed) {
        return new PlaidTransaction(id, "item", userId, "checking", new BigDecimal("15.4900"), LocalDate.now())
                .name(name)
                .personalFinanceCategory(primary, detailed);
    }

    /** Saves and detaches, so sorting reads the transaction back from the database like it would in a job. */
    private void store(PlaidTransaction transaction) {
        transactions.saveAndFlush(transaction);
        entityManager.clear();
    }

    private void savePlan(String fixedCostLine) {
        planService.save(userId, null, null, null, SpendingPlan.DEFAULT_BUFFER_PERCENT, List.of(
                new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, fixedCostLine, null, false, null, List.of())),
                plan -> plan);
        entityManager.clear();
    }

    private PlaidTransaction pay(String id, String name) {
        return new PlaidTransaction(id, "item", userId, "checking", new BigDecimal("-4000"), LocalDate.now())
                .name(name)
                .personalFinanceCategory("INCOME", "INCOME_SALARY");
    }

    /** Answers each transaction's question with the bucket for its description. */
    private void answer(Map<String, String> bucketByDescription) {
        doAnswer(invocation -> answers(invocation.getArgument(1), bucketByDescription))
                .when(typeSafe).ask(any(), any());
    }

    private static TypeSafeClient.Answers answers(
            Map<String, Map<String, Object>> questions, Map<String, String> bucketByDescription) {
        Map<String, TypeSafeClient.Answer> byQuestion = new HashMap<>();
        questions.forEach((id, question) -> byQuestion.put(id,
                TypeSafeClient.Answer.choice(bucketByDescription.get(description(question)))));
        return new TypeSafeClient.Answers(byQuestion);
    }

    /** The instructions of the only question asked. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> askedInstructions() {
        ArgumentCaptor<Map<String, Map<String, Object>>> questions = ArgumentCaptor.forClass(Map.class);
        verify(typeSafe).ask(any(), questions.capture());
        Map<String, Object> question = questions.getValue().values().iterator().next();
        return (Map<String, Object>) question.get("instructions");
    }

    @SuppressWarnings("unchecked")
    private static String description(Map<String, Object> question) {
        Map<String, Object> instructions = (Map<String, Object>) question.get("instructions");
        return ((BucketClassifier.TransactionState) instructions.get("transaction")).description();
    }

    private Bucket bucket(String transactionId) {
        return stored(transactionId).getBucket();
    }

    private PlaidTransaction stored(String transactionId) {
        entityManager.clear();
        return transactions.findById(transactionId).orElseThrow();
    }
}
