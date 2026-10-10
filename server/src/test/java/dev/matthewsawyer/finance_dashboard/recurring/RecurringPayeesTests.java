package dev.matthewsawyer.finance_dashboard.recurring;

import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.repository.RecurringAnswerRepository;
import dev.matthewsawyer.finance_dashboard.repository.RecurringPayeeRepository;
import dev.matthewsawyer.finance_dashboard.service.SpendingPlanService;
import dev.matthewsawyer.finance_dashboard.sorting.TypeSafeClient;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
class RecurringPayeesTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final List<String> PRIMARY_CATEGORIES = List.of(
            "HOME_IMPROVEMENT", "FOOD_AND_DRINK", "ENTERTAINMENT", "PERSONAL_CARE", "TRANSFER_OUT");

    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private PlaidAccountRepository accounts;
    @Autowired private PlaidRecurringStreamRepository streams;
    @Autowired private RecurringPayeeRepository payees;
    @Autowired private RecurringAnswerRepository answers;
    @Autowired private SpendingPlanService planService;
    @Autowired private TrackedAccounts trackedAccounts;
    @Autowired private dev.matthewsawyer.finance_dashboard.transactions.TransactionExclusions exclusions;
    @Autowired private EntityManager entityManager;

    private final TypeSafeClient typeSafe = mock(TypeSafeClient.class);
    private final UUID userId = UUID.randomUUID();
    private RecurringPayees recurringPayees;
    // Jev's plan line for each payee's bills, by payee name; anything else fits no line.
    private final Map<String, String> lineByPayee = new HashMap<>();

    @BeforeEach
    void setUp() {
        recurringPayees = new RecurringPayees(transactions, streams, payees, answers, planService, trackedAccounts,
                new PayeeJudge(typeSafe, new ObjectMapper()), Runnable::run, Clock.systemDefaultZone(), exclusions);
        when(typeSafe.isConfigured()).thenReturn(true);
        doAnswer(invocation -> answers(invocation.getArgument(1)))
                .when(typeSafe).ask(any(), any());
        account("checking", true);
    }

    @Test
    void judgesEachPayeeOnceFromAllItsChargesInOneRequest() {
        // Rent Plaid calls home improvement: its repeating amount is what gives it away.
        charge("rent-sep", "Sterling Group", "1554.91", TODAY.minusDays(29), "HOME_IMPROVEMENT_REPAIR_AND_MAINTENANCE");
        charge("rent-aug", "Sterling Group", "1554.91", TODAY.minusDays(60), "HOME_IMPROVEMENT_REPAIR_AND_MAINTENANCE");
        charge("coffee", "Starbucks", "6.45", TODAY.minusDays(2), "FOOD_AND_DRINK_COFFEE");
        lineByPayee.put("Sterling Group", "fixed costs: Rent/mortgage");

        recurringPayees.judge(userId);

        verify(typeSafe, times(1)).ask(any(), any());
        List<PayeeJudge.Charge> rent = charges("Sterling Group");
        assertEquals(List.of(TODAY.minusDays(29).toString(), TODAY.minusDays(60).toString()),
                rent.stream().map(PayeeJudge.Charge::date).toList());
        assertEquals("home improvement: repair and maintenance", rent.get(0).plaidCategory());
        assertEquals(new BigDecimal("1554.91"), rent.get(0).amountUsd());

        RecurringPayee sterling = stored(RecurringKind.BILL, "sterling group");
        assertEquals(new BigDecimal("0.9000"), sterling.getProbability());
        assertEquals(RecurringFrequency.MONTHLY, sterling.getUsualFrequency());
        assertEquals(SpendingPlanBucket.FIXED_COSTS, sterling.getPlanBucket());
        assertEquals("Rent/mortgage", sterling.getPlanLine());
        assertNull(stored(RecurringKind.BILL, "starbucks").getPlanLine(), "Jev put coffee on no line");
    }

    @Test
    void putsEachPayeeInItsOwnQuestionsWithAnEmptyState() {
        charge("netflix", "Netflix", "15.49", TODAY.minusDays(5), "ENTERTAINMENT_TV_AND_MOVIES");

        recurringPayees.judge(userId);

        ArgumentCaptor<Object> state = ArgumentCaptor.forClass(Object.class);
        verify(typeSafe).ask(state.capture(), any());
        assertEquals(Map.of(), state.getValue());
        Map<String, Map<String, Object>> asked = askedQuestions().get(0);
        assertEquals(List.of("payee", "charges", "question", "guidance"),
                List.copyOf(instructions(asked.get("0." + PayeeJudge.REPEATS)).keySet()));
        assertEquals(List.of("payee", "charges", "question"),
                List.copyOf(instructions(asked.get("0." + PayeeJudge.USUAL_FREQUENCY)).keySet()));
    }

    @Test
    void asksAboutManyPayeesInSeveralRequests() {
        for (int i = 0; i <= PayeeJudge.PER_REQUEST; i++) {
            charge("shop-" + i, "Shop " + i, "20", TODAY.minusDays(i), "FOOD_AND_DRINK_COFFEE");
        }

        recurringPayees.judge(userId);

        verify(typeSafe, times(2)).ask(any(), any());
        assertEquals(PayeeJudge.PER_REQUEST + 1, payees.findAllByUserId(userId).size());
    }

    @Test
    void asksAgainOnlyWhenWhatJevWouldSeeChanges() {
        charge("netflix-sep", "Netflix", "15.49", TODAY.minusDays(5), "ENTERTAINMENT_TV_AND_MOVIES");

        recurringPayees.judge(userId);
        recurringPayees.judge(userId);
        verify(typeSafe, times(1)).ask(any(), any());

        charge("netflix-oct", "Netflix", "15.49", TODAY, "ENTERTAINMENT_TV_AND_MOVIES");
        recurringPayees.judge(userId);
        verify(typeSafe, times(2)).ask(any(), any());
    }

    @Test
    void asksOnlyForTheLineWhilePlaidDetectsABill() {
        streams.saveAndFlush(new PlaidRecurringStream(
                "stream", "item", userId, "checking", new BigDecimal("15.49"), "MONTHLY", false)
                .merchantName("Netflix"));
        charge("netflix", "Netflix", "15.49", TODAY.minusDays(5), "ENTERTAINMENT_TV_AND_MOVIES");
        lineByPayee.put("Netflix", "fixed costs: Subscriptions");

        recurringPayees.judge(userId);

        assertEquals(List.of(Set.of(PayeeJudge.LINE)), askedQuestionIds());
        RecurringPayee netflix = stored(RecurringKind.BILL, "netflix");
        assertNull(netflix.getProbability());
        assertEquals("Subscriptions", netflix.getPlanLine());
    }

    @Test
    void asksWhetherPayIsAPaycheckWithoutALine() {
        store(new PlaidTransaction("pay", "item", userId, "checking", new BigDecimal("-2400"), TODAY.minusDays(3))
                .name("ACME CORP PAYROLL")
                .personalFinanceCategory("INCOME", "INCOME_WAGES"));

        recurringPayees.judge(userId);

        assertEquals(List.of(Set.of(PayeeJudge.REPEATS, PayeeJudge.USUAL_FREQUENCY)), askedQuestionIds());
        @SuppressWarnings("unchecked")
        List<PayeeJudge.Charge> deposits = (List<PayeeJudge.Charge>) shown("ACME CORP PAYROLL").get("deposits");
        assertEquals(new BigDecimal("2400.00"), deposits.get(0).amountUsd());
        assertEquals(new BigDecimal("0.9000"), stored(RecurringKind.PAYCHECK, "acme corp payroll").getProbability());
    }

    @Test
    void asksNothingAboutPayPlaidDetects() {
        streams.saveAndFlush(new PlaidRecurringStream(
                "pay-stream", "item", userId, "checking", new BigDecimal("-2400"), "BIWEEKLY", true)
                .description("ACME CORP PAYROLL"));
        store(new PlaidTransaction("pay", "item", userId, "checking", new BigDecimal("-2400"), TODAY.minusDays(3))
                .name("ACME CORP PAYROLL")
                .personalFinanceCategory("INCOME", "INCOME_WAGES"));

        recurringPayees.judge(userId);

        verify(typeSafe, never()).ask(any(), any());
    }

    @Test
    void offersTheSavedPlansLinesOrTheSpreadsheetsBeforeThereIsOne() {
        charge("rent", "Sterling Group", "1554.91", TODAY.minusDays(29), "HOME_IMPROVEMENT_REPAIR_AND_MAINTENANCE");
        recurringPayees.judge(userId);

        planService.save(userId, null, null, null, SpendingPlan.DEFAULT_BUFFER_PERCENT, List.of(
                new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, "Rent", null, false, null, List.of()),
                new SpendingPlanLine(SpendingPlanBucket.SAVINGS, "Japan trip", null, false, null, List.of())),
                plan -> plan);
        recurringPayees.judge(userId);

        verify(typeSafe, times(2)).ask(any(), any());
        Set<String> before = lineOptions(askedQuestions().get(0));
        Set<String> after = lineOptions(askedQuestions().get(1));
        assertTrue(before.contains("fixed costs: Rent/mortgage"));
        assertTrue(before.contains("savings: Emergency fund"));
        assertEquals(List.of("fixed costs: Rent", "savings: Japan trip", "none"), List.copyOf(after));
    }

    @Test
    void leavesOutUntrackedAccountsUnsortedSpendingAndMovesBetweenOwnAccounts() {
        account("savings", false);
        store(new PlaidTransaction("gym", "item", userId, "savings", new BigDecimal("40"), TODAY.minusDays(5))
                .merchantName("Planet Fitness"));
        sort("gym", Bucket.FIXED_COSTS);
        store(new PlaidTransaction("new", "item", userId, "checking", new BigDecimal("12"), TODAY)
                .merchantName("Not sorted yet"));
        charge("to-savings", "Transfer to savings", "500", TODAY.minusDays(5), "TRANSFER_OUT_ACCOUNT_TRANSFER");
        sort("to-savings", Bucket.NOT_COUNTED);

        recurringPayees.judge(userId);

        verify(typeSafe, never()).ask(any(), any());
    }

    @Test
    void keepsTheLastJudgmentWhenAQuestionFails() {
        charge("netflix-sep", "Netflix", "15.49", TODAY.minusDays(5), "ENTERTAINMENT_TV_AND_MOVIES");
        recurringPayees.judge(userId);
        String judged = stored(RecurringKind.BILL, "netflix").getJudgedState();

        charge("netflix-oct", "Netflix", "15.49", TODAY, "ENTERTAINMENT_TV_AND_MOVIES");
        doThrow(new IllegalStateException("TypeSafe unavailable")).when(typeSafe).ask(any(), any());
        recurringPayees.judge(userId);

        assertEquals(judged, stored(RecurringKind.BILL, "netflix").getJudgedState());
    }

    @Test
    void showsJevAPayeesMostRecentCharges() {
        for (int month = 0; month < RecurringPayees.MAX_CHARGES + 3; month++) {
            charge("gym-" + month, "Planet Fitness", "24.99", TODAY.minusMonths(month), "PERSONAL_CARE_GYMS_AND_FITNESS_CENTERS");
        }

        recurringPayees.judge(userId);

        List<PayeeJudge.Charge> shown = charges("Planet Fitness");
        assertEquals(RecurringPayees.MAX_CHARGES, shown.size());
        assertEquals(TODAY.toString(), shown.get(0).date());
    }

    @Test
    void asksNothingWithoutAnApiKey() {
        when(typeSafe.isConfigured()).thenReturn(false);
        charge("netflix", "Netflix", "15.49", TODAY.minusDays(5), "ENTERTAINMENT_TV_AND_MOVIES");

        recurringPayees.judge(userId);

        verify(typeSafe, never()).ask(any(), any());
        assertTrue(payees.findAllByUserId(userId).isEmpty());
    }

    private TypeSafeClient.Answers answers(Map<String, Map<String, Object>> questions) {
        Map<String, TypeSafeClient.Answer> byQuestion = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> question : questions.entrySet()) {
            String questionId = question.getKey();
            Object payee = instructions(question.getValue()).get("payee");
            byQuestion.put(questionId, switch (questionId.substring(questionId.indexOf('.') + 1)) {
                case PayeeJudge.REPEATS -> TypeSafeClient.Answer.noul(0.9);
                case PayeeJudge.USUAL_FREQUENCY -> TypeSafeClient.Answer.choice("monthly");
                default -> TypeSafeClient.Answer.choice(lineByPayee.getOrDefault(payee, "none"));
            });
        }
        return new TypeSafeClient.Answers(byQuestion);
    }

    private void account(String accountId, boolean tracked) {
        PlaidAccount account = new PlaidAccount(accountId, "item", userId);
        account.updateSnapshot(accountId, null, null, "depository", tracked ? "checking" : "savings",
                null, null, null, "USD", null);
        account.updateTracking(tracked, true, 100);
        accounts.saveAndFlush(account);
    }

    /** Money out in the checking account, already sorted, as payees' charges are. */
    private void charge(String id, String merchant, String amount, LocalDate date, String detailedCategory) {
        store(new PlaidTransaction(id, "item", userId, "checking", new BigDecimal(amount), date)
                .name(merchant.toUpperCase())
                .merchantName(merchant)
                .personalFinanceCategory(PRIMARY_CATEGORIES.stream()
                        .filter(detailedCategory::startsWith)
                        .findFirst().orElseThrow(), detailedCategory));
        sort(id, Bucket.FIXED_COSTS);
    }

    private void store(PlaidTransaction transaction) {
        transactions.saveAndFlush(transaction);
        entityManager.clear();
    }

    private void sort(String id, Bucket bucket) {
        transactions.updateBucket(id, transactions.findById(id).orElseThrow().getUpdatedAt(), bucket);
        entityManager.clear();
    }

    private RecurringPayee stored(RecurringKind kind, String merchantKey) {
        return recurringPayees.judged(userId).get(new RecurringMerchant(kind, merchantKey));
    }

    /** The questions of each request, by question id. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Map<String, Object>>> askedQuestions() {
        ArgumentCaptor<Map<String, Map<String, Object>>> questions = ArgumentCaptor.forClass(Map.class);
        verify(typeSafe, atLeast(0)).ask(any(), questions.capture());
        return questions.getAllValues();
    }

    /** The question ids of each request, without the payee's number. */
    private List<Set<String>> askedQuestionIds() {
        return askedQuestions().stream()
                .map(asked -> asked.keySet().stream()
                        .map(id -> id.substring(id.indexOf('.') + 1))
                        .collect(Collectors.toSet()))
                .toList();
    }

    /** The instructions of the first question asked about a payee, which carry its charges. */
    private Map<String, Object> shown(String payee) {
        return askedQuestions().stream()
                .flatMap(asked -> asked.values().stream())
                .map(RecurringPayeesTests::instructions)
                .filter(instructions -> payee.equals(instructions.get("payee")))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<PayeeJudge.Charge> charges(String payee) {
        return (List<PayeeJudge.Charge>) shown(payee).get("charges");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> instructions(Map<String, Object> question) {
        return (Map<String, Object>) question.get("instructions");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> lineOptions(Map<String, Map<String, Object>> questions) {
        return ((Map<String, Object>) questions.get("0." + PayeeJudge.LINE).get("criteria")).keySet();
    }
}
