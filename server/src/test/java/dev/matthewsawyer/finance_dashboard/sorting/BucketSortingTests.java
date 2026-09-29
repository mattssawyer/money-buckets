package dev.matthewsawyer.finance_dashboard.sorting;

import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    @Autowired private PlaidRecurringStreamRepository streams;
    @Autowired private SpendingPlanService planService;
    @Autowired private EntityManager entityManager;

    private final TypeSafeClient typeSafe = mock(TypeSafeClient.class);
    private BucketSorting sorting;
    private UUID userId;

    @BeforeEach
    void setUp() {
        // Jobs and questions run inline so each test sees the finished sort.
        sorting = new BucketSorting(
                transactions, streams, planService, new BucketClassifier(typeSafe), new RecurringJudge(typeSafe),
                Runnable::run, Runnable::run);
        userId = UUID.randomUUID();
        when(typeSafe.isConfigured()).thenReturn(true);
    }

    @Test
    void storesTheBucketTypeSafeChooses() {
        store(transaction("rent", "Rent ACH", "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"));
        store(transaction("coffee", "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        answer(Map.of("Rent ACH", "fixed_costs", "Starbucks", "guilt_free"));

        sorting.sortLater(userId);

        assertEquals(Bucket.FIXED_COSTS, bucket("rent"));
        assertEquals(Bucket.GUILT_FREE, bucket("coffee"));
    }

    @Test
    void neverSortsPayOrAsksAboutCardPayments() {
        store(pay("pay", "ACME payroll"));
        store(transaction("card", "Card autopay", "LOAN_PAYMENTS", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT"));
        answer(Map.of());

        sorting.sortLater(userId);

        assertEquals(List.of(Set.of(RecurringJudge.REPEATS, RecurringJudge.USUAL_FREQUENCY)), askedQuestionIds());
        assertNull(bucket("pay"));
        assertNull(bucket("card"));
    }

    @Test
    void asksWhetherSpendingRepeatsInTheSortingRequest() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"), 0.92);

        sorting.sortLater(userId);

        assertEquals(List.of(Set.of("bucket", RecurringJudge.REPEATS, RecurringJudge.USUAL_FREQUENCY)),
                askedQuestionIds());
        PlaidTransaction netflix = stored("netflix");
        assertEquals(Bucket.FIXED_COSTS, netflix.getBucket());
        assertEquals(new BigDecimal("0.9200"), netflix.getRecurringProbability());
        assertEquals(RecurringFrequency.MONTHLY, netflix.getUsualFrequency());
        assertNotNull(netflix.getRecurringJudgedAt());
    }

    @Test
    void asksWhetherPayRepeatsAsAPaycheck() {
        store(pay("pay", "ACME payroll"));
        answer(Map.of(), 0.97);

        sorting.sortLater(userId);

        assertEquals("money in", askedState().transaction().direction());
        assertEquals(new BigDecimal("0.9700"), stored("pay").getRecurringProbability());
    }

    @Test
    void neverAsksWhetherAMerchantPlaidDetectsRepeats() {
        streams.saveAndFlush(new PlaidRecurringStream(
                "netflix-stream", "item", userId, "checking", new BigDecimal("15.49"), "MONTHLY", false)
                .merchantName("NETFLIX"));
        streams.saveAndFlush(new PlaidRecurringStream(
                "pay-stream", "item", userId, "checking", new BigDecimal("-4000"), "BIWEEKLY", true)
                .description("ACME PAYROLL"));
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        store(pay("pay", "ACME payroll"));
        answer(Map.of("Netflix", "fixed_costs"), 0.9);

        sorting.sortLater(userId);

        assertEquals(List.of(Set.of("bucket")), askedQuestionIds());
        assertEquals(Bucket.FIXED_COSTS, bucket("netflix"));
        assertNull(stored("netflix").getRecurringJudgedAt());
        assertNull(stored("pay").getRecurringJudgedAt());
    }

    @Test
    void asksAboutAMerchantPlaidDetectsOnlyInAnotherAccount() {
        streams.saveAndFlush(new PlaidRecurringStream(
                "netflix-stream", "item", userId, "credit-card", new BigDecimal("15.49"), "MONTHLY", false)
                .merchantName("Netflix"));
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"), 0.9);

        sorting.sortLater(userId);

        assertEquals(List.of(Set.of("bucket", RecurringJudge.REPEATS, RecurringJudge.USUAL_FREQUENCY)),
                askedQuestionIds());
    }

    @Test
    void judgesSpendingSortedBeforeRecurringJudgmentsExisted() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        transactions.updateBucket("netflix", stored("netflix").getUpdatedAt(), Bucket.FIXED_COSTS);
        answer(Map.of(), 0.9);

        sorting.sortLater(userId);

        assertEquals(List.of(Set.of(RecurringJudge.REPEATS, RecurringJudge.USUAL_FREQUENCY)), askedQuestionIds());
        assertEquals(new BigDecimal("0.9000"), stored("netflix").getRecurringProbability());
        assertEquals(Bucket.FIXED_COSTS, stored("netflix").getBucket());
    }

    @Test
    void neverAsksWhetherMovesBetweenOwnAccountsOrRefundsRepeat() {
        store(transaction("to-savings", "Transfer to savings", "TRANSFER_OUT", "TRANSFER_OUT_ACCOUNT_TRANSFER"));
        transactions.updateBucket("to-savings", stored("to-savings").getUpdatedAt(), Bucket.NOT_COUNTED);
        store(new PlaidTransaction("refund", "item", userId, "checking", new BigDecimal("-20"), LocalDate.now())
                .name("Target refund")
                .personalFinanceCategory("GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_SUPERSTORES"));
        answer(Map.of("Target refund", "guilt_free"));

        sorting.sortLater(userId);

        assertEquals(List.of(Set.of("bucket")), askedQuestionIds());
        assertNull(stored("to-savings").getRecurringJudgedAt());
        assertNull(stored("refund").getRecurringJudgedAt());
    }

    @Test
    void keepsTheRecurringJudgmentWhenAPlanSaveSortsAgain() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "guilt_free"), 0.9);
        sorting.sortLater(userId);

        savePlan("Subscriptions");
        answer(Map.of("Netflix", "fixed_costs"), 0.1);
        sorting.planSaved(userId, PlanLines.NONE);

        assertEquals(List.of(Set.of("bucket")), askedQuestionIds().subList(1, 2));
        assertEquals(Bucket.FIXED_COSTS, stored("netflix").getBucket());
        assertEquals(new BigDecimal("0.9000"), stored("netflix").getRecurringProbability());
    }

    @Test
    void givesTypeSafeThePlanLinesOnceAPlanIsSaved() {
        savePlan("Subscriptions");
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "fixed_costs"));

        sorting.sortLater(userId);

        BucketClassifier.State state = askedState();
        assertEquals(List.of("Subscriptions"), state.spendingPlan().fixedCosts().lines());
        assertEquals("entertainment: tv and movies", state.transaction().plaidCategory());
        assertEquals(new BigDecimal("15.49"), state.transaction().amountUsd());
        assertEquals("money out", state.transaction().direction());
    }

    @Test
    void asksWithoutPlanContextBeforeAPlanIsSaved() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "guilt_free"));

        sorting.sortLater(userId);

        assertNull(askedState().spendingPlan());
        assertEquals(Bucket.GUILT_FREE, bucket("netflix"));
    }

    @Test
    void leavesATransactionUnsortedWhenTypeSafeFails() {
        store(transaction("rent", "Rent ACH", "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"));
        store(transaction("coffee", "Starbucks", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        when(typeSafe.ask(any(), any())).thenAnswer(invocation -> {
            if (description(invocation.getArgument(0)).equals("Starbucks")) {
                throw new IllegalStateException("TypeSafe unavailable");
            }
            return answers(invocation.getArgument(1), "fixed_costs", 0.1);
        });

        sorting.sortLater(userId);

        assertEquals(Bucket.FIXED_COSTS, bucket("rent"));
        assertNull(bucket("coffee"));
    }

    @Test
    void skipsSortingWithoutAnApiKey() {
        when(typeSafe.isConfigured()).thenReturn(false);
        store(transaction("rent", "Rent ACH", "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"));

        sorting.sortLater(userId);

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
            return answers(invocation.getArgument(1), "guilt_free", 0.1);
        });

        sorting.sortLater(userId);

        assertNull(bucket("coffee"));
        assertNull(stored("coffee").getRecurringJudgedAt());
    }

    @Test
    void sortsEverythingAgainWhenAPlanSaveChangesTheLines() {
        store(transaction("netflix", "Netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
        answer(Map.of("Netflix", "guilt_free"));
        sorting.sortLater(userId);

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
        sorting.sortLater(userId);

        sorting.planSaved(userId, new PlanLines(List.of("Subscriptions"), List.of(), List.of()));

        verify(typeSafe, times(1)).ask(any(), any());
    }

    @Test
    void readsPlaidCategoriesAsWords() {
        assertEquals("food and drink: coffee",
                BucketClassifier.readableCategory("FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        assertEquals("travel", BucketClassifier.readableCategory("TRAVEL", null));
        assertEquals("other transfer", BucketClassifier.readableCategory("TRANSFER_OUT", "OTHER_TRANSFER"));
        assertNull(BucketClassifier.readableCategory(null, null));
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
        planService.save(userId, null, null, SpendingPlan.DEFAULT_BUFFER_PERCENT, List.of(
                new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, fixedCostLine, null, false, List.of())),
                plan -> plan);
        entityManager.clear();
    }

    private PlaidTransaction pay(String id, String name) {
        return new PlaidTransaction(id, "item", userId, "checking", new BigDecimal("-4000"), LocalDate.now())
                .name(name)
                .personalFinanceCategory("INCOME", "INCOME_SALARY");
    }

    private void answer(Map<String, String> bucketByDescription) {
        answer(bucketByDescription, 0.1);
    }

    /** Answers every question asked: the bucket by description, and {@code repeats} for whether it repeats. */
    private void answer(Map<String, String> bucketByDescription, double repeats) {
        doAnswer(invocation -> answers(
                invocation.getArgument(1), bucketByDescription.get(description(invocation.getArgument(0))), repeats))
                .when(typeSafe).ask(any(), any());
    }

    private static TypeSafeClient.Answers answers(
            Map<String, Map<String, Object>> questions, String bucket, double repeats) {
        Map<String, TypeSafeClient.Answer> byQuestion = new HashMap<>();
        for (String questionId : questions.keySet()) {
            byQuestion.put(questionId, switch (questionId) {
                case RecurringJudge.REPEATS -> TypeSafeClient.Answer.noul(repeats);
                case RecurringJudge.USUAL_FREQUENCY -> TypeSafeClient.Answer.choice("monthly");
                default -> TypeSafeClient.Answer.choice(bucket);
            });
        }
        return new TypeSafeClient.Answers(byQuestion);
    }

    private BucketClassifier.State askedState() {
        ArgumentCaptor<Object> state = ArgumentCaptor.forClass(Object.class);
        verify(typeSafe).ask(state.capture(), any());
        return assertInstanceOf(BucketClassifier.State.class, state.getValue());
    }

    /** The question ids of every request, in the order they were asked. */
    @SuppressWarnings("unchecked")
    private List<Set<String>> askedQuestionIds() {
        ArgumentCaptor<Map<String, Map<String, Object>>> questions = ArgumentCaptor.forClass(Map.class);
        verify(typeSafe, atLeast(0)).ask(any(), questions.capture());
        return questions.getAllValues().stream().map(asked -> Set.copyOf(asked.keySet())).toList();
    }

    private static String description(Object state) {
        return ((BucketClassifier.State) state).transaction().description();
    }

    private Bucket bucket(String transactionId) {
        return stored(transactionId).getBucket();
    }

    private PlaidTransaction stored(String transactionId) {
        entityManager.clear();
        return transactions.findById(transactionId).orElseThrow();
    }
}
