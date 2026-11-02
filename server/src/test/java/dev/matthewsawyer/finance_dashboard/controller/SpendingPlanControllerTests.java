package dev.matthewsawyer.finance_dashboard.controller;

import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.ItemRequest;
import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.LineRequest;
import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.SpendingPlanRequest;
import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.SpendingPlanResponse;
import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.GuiltFreeLeftResponse;
import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.PlanEvaluationResponse;
import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.CategoryHistoryResponse;
import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.LineDefaultsResponse;
import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.PlanSuggestionsResponse;
import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.RecurringPaymentResponse;
import dev.matthewsawyer.finance_dashboard.controller.SpendingPlanController.SpendingHistoryResponse;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanItem;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringCandidate;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringCandidates;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayees;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayments;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.sorting.PlanLines;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.service.SpendingPlanService;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.payees.PayeeLookup;
import dev.matthewsawyer.finance_dashboard.spending.Spending;
import dev.matthewsawyer.finance_dashboard.transactions.TransactionExclusions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpendingPlanControllerTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private SpendingPlanService planService;

    @Mock
    private PlaidAccountRepository accountRepository;

    @Mock
    private BucketSorting bucketSorting;

    @Mock
    private UserService userService;

    @Mock
    private Spending spending;

    @Mock
    private PayeeLookup payeeLookup;

    @Mock
    private PlaidRecurringStreamRepository streamRepository;

    @Mock
    private RecurringPayees recurringPayees;

    @Mock
    private RecurringCandidates recurringCandidates;

    private SpendingPlanController controller;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        RecurringPayments recurringPayments = new RecurringPayments(
                streamRepository, new TrackedAccounts(accountRepository), recurringPayees, recurringCandidates,
                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));
        controller = new SpendingPlanController(
                planService, accountRepository, bucketSorting, userService, spending, payeeLookup, recurringPayments);
        jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user_123").build();
        User user = new User("user_123");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
    }

    @Test
    void returnsNotFoundBeforeAPlanIsSaved() {
        when(planService.find(eq(USER_ID), any())).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.getSpendingPlan(jwt));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void savesTheWholePlanForTheSignedInUser() {
        when(accountRepository.existsByAccountIdAndUserId("checking", USER_ID)).thenReturn(true);
        ArgumentCaptor<List<SpendingPlanLine>> lines = ArgumentCaptor.forClass(List.class);
        when(planService.save(eq(USER_ID), eq("checking"), eq(new BigDecimal("5200")), eq(new BigDecimal("8000")),
                eq(new BigDecimal("10")),
                lines.capture(), any()))
                .thenAnswer(invocation -> {
                    SpendingPlan plan = new SpendingPlan(USER_ID);
                    plan.replace("checking", new BigDecimal("5200.0000"), new BigDecimal("8000.0000"), new BigDecimal("10.00"),
                            lines.getValue());
                    return ((Function<SpendingPlan, SpendingPlanResponse>) invocation.getArgument(6)).apply(plan);
                });

        SpendingPlanResponse response = controller.saveSpendingPlan(jwt, new SpendingPlanRequest(
                "checking",
                new BigDecimal("5200"),
                new BigDecimal("8000"),
                new BigDecimal("10"),
                List.of(
                        new LineRequest(SpendingPlanBucket.FIXED_COSTS, "  Subscriptions ", null, false, null, List.of(
                                new ItemRequest("Netflix", new BigDecimal("15.49"), "stream-netflix"),
                                new ItemRequest(null, null, " "))),
                        new LineRequest(SpendingPlanBucket.INVESTMENTS, "401(k)",
                                new BigDecimal("600.0000"), true, new BigDecimal("7.5"), null))));

        assertEquals("Subscriptions", lines.getValue().get(0).getName());
        assertEquals("", lines.getValue().get(0).getItems().get(1).getName());
        assertNull(lines.getValue().get(0).getItems().get(1).getStreamId());
        assertTrue(lines.getValue().get(1).getItems().isEmpty());

        assertEquals("checking", response.accountId());
        assertEquals("5200", response.takeHome().toPlainString());
        assertEquals("10", response.fixedCostBufferPercent().toPlainString());
        assertEquals("600", response.lines().get(1).amount().toPlainString());
        assertTrue(response.lines().get(1).fromPaycheck());
        assertEquals("8000", response.grossPay().toPlainString());
        assertEquals("7.5", response.lines().get(1).percentOfGross().toPlainString());
        assertNull(response.lines().get(0).percentOfGross());
        assertEquals("stream-netflix", response.lines().get(0).items().get(0).streamId());
        assertEquals("15.49", response.lines().get(0).items().get(0).amount().toPlainString());
    }

    @Test
    void savesAPlanWithoutAnAccount() {
        when(planService.save(eq(USER_ID), eq(null), eq(null), eq(null), eq(SpendingPlan.DEFAULT_BUFFER_PERCENT),
                anyList(), any())).thenReturn(
                new SpendingPlanResponse(null, null, null, SpendingPlan.DEFAULT_BUFFER_PERCENT, List.of(), Instant.now(), null));

        // A plan saved without a buffer gets the default 15%.
        controller.saveSpendingPlan(jwt, new SpendingPlanRequest("", null, null, null, null));

        verifyNoInteractions(accountRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasSortingCompareThePlanLinesFromBeforeTheSave() {
        PlanLines linesBefore = new PlanLines(List.of("Rent"), List.of(), List.of("Vacations"));
        when(planService.find(eq(USER_ID), any())).thenAnswer(invocation -> {
            SpendingPlan plan = new SpendingPlan(USER_ID);
            plan.replace(null, null, null, SpendingPlan.DEFAULT_BUFFER_PERCENT, List.of(
                    new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, "Rent", null, false, null, List.of()),
                    new SpendingPlanLine(SpendingPlanBucket.SAVINGS, "Vacations", null, false, null, List.of())));
            return Optional.of(((Function<SpendingPlan, ?>) invocation.getArgument(1)).apply(plan));
        });
        when(planService.save(eq(USER_ID), eq(null), eq(null), eq(null), eq(SpendingPlan.DEFAULT_BUFFER_PERCENT),
                anyList(), any())).thenReturn(
                new SpendingPlanResponse(null, null, null, SpendingPlan.DEFAULT_BUFFER_PERCENT, List.of(), Instant.now(), null));

        controller.saveSpendingPlan(jwt, new SpendingPlanRequest(null, null, null, null, List.of()));

        verify(bucketSorting).planSaved(USER_ID, linesBefore);
    }

    @Test
    void rejectsAnAccountTheUserDoesNotOwn() {
        when(accountRepository.existsByAccountIdAndUserId("someone-elses", USER_ID)).thenReturn(false);

        assertBadRequest(new SpendingPlanRequest("someone-elses", null, null, null, List.of()));
        verifyNoInteractions(planService);
    }

    @Test
    void rejectsNegativeAndOversizedAmounts() {
        assertBadRequest(new SpendingPlanRequest(null, new BigDecimal("-1"), null, null, List.of()));
        assertBadRequest(planWith(new LineRequest(SpendingPlanBucket.SAVINGS, "Gifts",
                new BigDecimal("1e15"), false, null, List.of())));
        assertBadRequest(planWith(new LineRequest(SpendingPlanBucket.SAVINGS, "Gifts", null, false, null,
                List.of(new ItemRequest("Birthday", new BigDecimal("-5"), null)))));
    }

    @Test
    void keepsTheBufferBetweenZeroAndOneHundredPercent() {
        assertBadRequest(new SpendingPlanRequest(null, null, null, new BigDecimal("-1"), List.of()));
        assertBadRequest(new SpendingPlanRequest(null, null, null, new BigDecimal("100.01"), List.of()));
    }

    @Test
    void rejectsPaycheckContributionsOutsideInvestments() {
        assertBadRequest(planWith(new LineRequest(SpendingPlanBucket.FIXED_COSTS, "Rent/mortgage",
                new BigDecimal("1450"), true, null, List.of())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void worksOutAPercentLinesAmountFromGrossPayRatherThanTrustingTheOneSent() {
        ArgumentCaptor<List<SpendingPlanLine>> lines = ArgumentCaptor.forClass(List.class);
        when(planService.save(eq(USER_ID), eq(null), eq(null), eq(new BigDecimal("8000")),
                eq(SpendingPlan.DEFAULT_BUFFER_PERCENT), lines.capture(), any())).thenReturn(null);

        controller.saveSpendingPlan(jwt, new SpendingPlanRequest(null, null, new BigDecimal("8000"), null,
                List.of(new LineRequest(SpendingPlanBucket.INVESTMENTS, "401(k)",
                        new BigDecimal("1"), true, new BigDecimal("6.125"), List.of()))));

        // Keep the entered precision and derive the dollar amount from it.
        SpendingPlanLine line = lines.getValue().get(0);
        assertEquals("6.125", line.getPercentOfGross().toPlainString());
        assertEquals("490.00", line.getAmount().toPlainString());
    }

    @Test
    void needsGrossPayForAPercentOfIt() {
        assertBadRequest(new SpendingPlanRequest(null, null, null, null, List.of(
                new LineRequest(SpendingPlanBucket.INVESTMENTS, "401(k)",
                        new BigDecimal("480"), true, new BigDecimal("6"), List.of()))));
        assertBadRequest(new SpendingPlanRequest(null, null, BigDecimal.ZERO, null, List.of(
                new LineRequest(SpendingPlanBucket.INVESTMENTS, "401(k)",
                        new BigDecimal("480"), true, new BigDecimal("6"), List.of()))));
    }

    @Test
    void allowsAPercentOfGrossPayOnlyOnPaycheckLinesWithoutItems() {
        assertBadRequest(planWith(new LineRequest(SpendingPlanBucket.INVESTMENTS, "Roth IRA",
                null, false, new BigDecimal("5"), List.of())));
        assertBadRequest(planWith(new LineRequest(SpendingPlanBucket.INVESTMENTS, "401(k)",
                null, true, new BigDecimal("5"), List.of(new ItemRequest("Match", BigDecimal.ONE, null)))));
        assertBadRequest(planWith(new LineRequest(SpendingPlanBucket.INVESTMENTS, "401(k)",
                null, true, new BigDecimal("100.01"), List.of())));
        assertBadRequest(new SpendingPlanRequest(null, null, new BigDecimal("-1"), null, List.of()));
    }

    @Test
    void rejectsLinesWithoutABucketAndOverlongNames() {
        assertBadRequest(planWith(new LineRequest(null, "Rent", null, false, null, List.of())));
        assertBadRequest(planWith(new LineRequest(SpendingPlanBucket.FIXED_COSTS, "x".repeat(256),
                null, false, null, List.of())));
    }

    @Test
    void capsHowManyLinesAndItemsAPlanCanHave() {
        LineRequest line = new LineRequest(SpendingPlanBucket.SAVINGS, "Gifts", null, false, null, List.of());
        assertBadRequest(new SpendingPlanRequest(null, null, null, null,
                Collections.nCopies(SpendingPlanController.MAX_LINES + 1, line)));
        assertBadRequest(planWith(new LineRequest(SpendingPlanBucket.SAVINGS, "Gifts", null, false, null,
                Collections.nCopies(SpendingPlanController.MAX_ITEMS_PER_LINE + 1,
                        new ItemRequest("Gift", BigDecimal.ONE, null)))));
    }

    @Test
    void returnsTheSavedPlan() {
        SpendingPlanResponse saved = new SpendingPlanResponse("checking", BigDecimal.TEN, null, BigDecimal.TEN, List.of(),
                Instant.now(), null);
        when(planService.find(eq(USER_ID), any())).thenReturn(Optional.of(saved));

        assertEquals(saved, controller.getSpendingPlan(jwt));
        verify(planService).find(eq(USER_ID), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsTheSavedPlanWithHowItSplitsItsIncome() {
        when(planService.find(eq(USER_ID), any())).thenAnswer(invocation -> {
            SpendingPlan plan = new SpendingPlan(USER_ID);
            plan.replace(null, new BigDecimal("4000.0000"), null, new BigDecimal("10.00"), List.of(
                    new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, "Rent", new BigDecimal("2000"), false, null, List.of()),
                    new SpendingPlanLine(SpendingPlanBucket.INVESTMENTS, "401(k)", new BigDecimal("500"), true, null, List.of()),
                    new SpendingPlanLine(SpendingPlanBucket.SAVINGS, "Emergency fund", new BigDecimal("300"), false, null, List.of())));
            return Optional.of(((Function<SpendingPlan, ?>) invocation.getArgument(1)).apply(plan));
        });

        PlanEvaluationResponse evaluation = controller.getSpendingPlan(jwt).evaluation();

        // 4,000 take-home + 500 from the paycheck, less 2,200 fixed with the buffer, 500 and 300.
        assertEquals("4500", evaluation.planIncome().toPlainString());
        assertEquals("200", evaluation.buffer().toPlainString());
        assertEquals("2200", evaluation.buckets().get(SpendingPlanBucket.FIXED_COSTS).amount().toPlainString());
        assertEquals(BigInteger.valueOf(49), evaluation.buckets().get(SpendingPlanBucket.FIXED_COSTS).share());
        assertEquals("below", evaluation.buckets().get(SpendingPlanBucket.FIXED_COSTS).status());
        assertEquals("1500", evaluation.guiltFree().amount().toPlainString());
        assertEquals(BigInteger.valueOf(33), evaluation.guiltFree().share());
        assertEquals(20, evaluation.guiltFree().target().min());
    }

    @Test
    void evaluatesAPlanBeingEditedWithoutSavingIt() {
        PlanEvaluationResponse evaluation = controller.evaluateSpendingPlan(jwt, new SpendingPlanRequest(
                null, new BigDecimal("3000"), new BigDecimal("8000"), BigDecimal.ZERO, List.of(
                        new LineRequest(SpendingPlanBucket.FIXED_COSTS, "Rent", new BigDecimal("2400"), false, null, List.of()),
                        // The amount sent is ignored: 6% of 8,000 gross pay is 480.
                        new LineRequest(SpendingPlanBucket.INVESTMENTS, "401(k)",
                                new BigDecimal("1"), true, new BigDecimal("6"), List.of()),
                        new LineRequest(SpendingPlanBucket.SAVINGS, "Gifts", new BigDecimal("1200"), false, null, List.of()))));

        assertEquals("480", evaluation.fromPaycheck().toPlainString());
        assertEquals("3480", evaluation.planIncome().toPlainString());
        assertEquals("-600", evaluation.guiltFree().amount().toPlainString());
        assertTrue(evaluation.guiltFree().flagged());
        verify(planService, never()).save(any(), any(), any(), any(), any(), anyList(), any());
        verifyNoInteractions(bucketSorting);
    }

    @Test
    void treatsABlankBufferAsNoBufferWhenEvaluating() {
        PlanEvaluationResponse evaluation = controller.evaluateSpendingPlan(jwt, new SpendingPlanRequest(
                null, null, null, null, List.of(
                        new LineRequest(SpendingPlanBucket.FIXED_COSTS, "Rent", new BigDecimal("2000"), false, null, List.of()))));

        assertEquals("0", evaluation.buffer().toPlainString());
        assertNull(evaluation.guiltFree().amount());
    }

    @Test
    void checksAPlanBeingEvaluatedLikeOneBeingSaved() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.evaluateSpendingPlan(jwt, planWith(new LineRequest(
                        SpendingPlanBucket.FIXED_COSTS, "Rent", new BigDecimal("1450"), true, null, List.of()))));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void takesTheMonthsGuiltFreeSpendingAcrossEveryTrackedAccountFromWhatThePlanLeaves() {
        planWithTakeHome("4000");
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 31);
        // Every tracked account, at the user's share: Spending works that out when no account is given.
        when(spending.between(eq(USER_ID), eq(start), eq(end), isNull())).thenReturn(List.of(
                spent(Bucket.GUILT_FREE, "FOOD_AND_DRINK", "400.00", 100),
                spent(Bucket.GUILT_FREE, "ENTERTAINMENT", "212.40", 50),
                spent(Bucket.FIXED_COSTS, "RENT_AND_UTILITIES", "2100.00", 100),
                spent(null, "GENERAL_MERCHANDISE", "35.50", 100)));

        GuiltFreeLeftResponse left = controller.getGuiltFreeLeft(jwt, start, end);

        assertEquals("1500", left.planned().toPlainString());
        assertEquals("612.4", left.spent().toPlainString());
        assertEquals("887.6", left.left().toPlainString());
        assertEquals("35.5", left.unsorted().toPlainString());
    }

    @Test
    void goesBelowZeroOnceGuiltFreeSpendingPassesTheBudget() {
        planWithTakeHome("4000");
        when(spending.between(eq(USER_ID), any(), any(), isNull())).thenReturn(List.of(
                spent(Bucket.GUILT_FREE, "FOOD_AND_DRINK", "1620", 100)));

        GuiltFreeLeftResponse left = controller.getGuiltFreeLeft(
                jwt, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertEquals("-120", left.left().toPlainString());
    }

    @Test
    void leavesOutGuiltFreeCategoriesThatRefundsBringToZeroOrBelowLikeTheSpendingChart() {
        planWithTakeHome("4000");
        when(spending.between(eq(USER_ID), any(), any(), isNull())).thenReturn(List.of(
                spent(Bucket.GUILT_FREE, "FOOD_AND_DRINK", "100", 100),
                spent(Bucket.GUILT_FREE, "GENERAL_MERCHANDISE", "50", 100),
                spent(Bucket.GUILT_FREE, "GENERAL_MERCHANDISE", "-80", 100)));

        GuiltFreeLeftResponse left = controller.getGuiltFreeLeft(
                jwt, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertEquals("100", left.spent().toPlainString());
    }

    @Test
    void hasNoGuiltFreeLeftUntilThePlanHasTakeHomePay() {
        planWithTakeHome(null);
        when(spending.between(eq(USER_ID), any(), any(), isNull())).thenReturn(List.of(
                spent(Bucket.GUILT_FREE, "FOOD_AND_DRINK", "50", 100)));

        GuiltFreeLeftResponse left = controller.getGuiltFreeLeft(
                jwt, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertNull(left.planned());
        assertNull(left.left());
        assertEquals("50", left.spent().toPlainString());
    }

    @Test
    void hasNoGuiltFreeLeftWithoutAPlan() {
        when(planService.find(eq(USER_ID), any())).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.getGuiltFreeLeft(jwt, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void measuresGuiltFreeLeftOverAtMostAMonth() {
        ResponseStatusException backwards = assertThrows(ResponseStatusException.class,
                () -> controller.getGuiltFreeLeft(jwt, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1)));
        ResponseStatusException tooLong = assertThrows(ResponseStatusException.class,
                () -> controller.getGuiltFreeLeft(jwt, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 1)));

        assertEquals(HttpStatus.BAD_REQUEST, backwards.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, tooLong.getStatusCode());
    }

    @Test
    void suggestsTheSpreadsheetsBlankLinesAndNoTakeHomeWithoutRecurringPayments() {
        when(accountRepository.findAllByUserIdAndTracksSpendingIsTrue(USER_ID)).thenReturn(List.of());

        PlanSuggestionsResponse suggestions = controller.getPlanSuggestions(jwt, true);

        assertNull(suggestions.takeHome());
        assertEquals(17, suggestions.lines().size());
        assertEquals("Rent/mortgage", suggestions.lines().get(0).name());
        assertTrue(suggestions.lines().stream().allMatch(line -> line.items().isEmpty() && line.amount() == null));
        assertTrue(suggestions.recurring().isEmpty());
        assertTrue(suggestions.unplaced().isEmpty());
        assertEquals(0, suggestions.suggestedCandidates());
    }

    @Test
    void suggestsTakeHomeAndBillsFromPlaidStreamsAndConfirmedCandidatesButNotUnansweredOnes() {
        trackChecking();
        when(streamRepository.findAllByUserIdAndAccountIdIn(USER_ID, Set.of("checking"))).thenReturn(List.of(
                stream("pay", "checking", "Payroll", "-2400", "BIWEEKLY", true),
                stream("rent", "checking", "Landlord", "1450", "MONTHLY", false)));
        RecurringPayee landlord = new RecurringPayee(USER_ID, new RecurringMerchant(RecurringKind.BILL, "landlord"));
        landlord.judged(null, null, SpendingPlanBucket.FIXED_COSTS, "Rent/mortgage", "state", Instant.now());
        when(recurringPayees.judged(USER_ID)).thenReturn(Map.of(landlord.payee(), landlord));
        when(recurringCandidates.find(USER_ID, null)).thenReturn(List.of(
                candidate("netflix", "15.49", RecurringCandidate.Status.CONFIRMED, "Subscriptions"),
                candidate("gym", "40", RecurringCandidate.Status.SUGGESTED, "Subscriptions"),
                candidate("doordash", "30", RecurringCandidate.Status.DISMISSED, null)));

        PlanSuggestionsResponse suggestions = controller.getPlanSuggestions(jwt, true);

        assertEquals("5200", suggestions.takeHome().toPlainString());
        assertEquals(List.of("Landlord"), itemNames(suggestions, "Rent/mortgage"));
        assertEquals(List.of("netflix"), itemNames(suggestions, "Subscriptions"));
        assertEquals("15.49", line(suggestions, "Subscriptions").items().get(0).amount().toPlainString());
        assertEquals(List.of("pay", "rent", candidate("netflix", "15.49", RecurringCandidate.Status.CONFIRMED, null).streamId()),
                suggestions.recurring().stream().map(RecurringPaymentResponse::streamId).toList());
        assertEquals("CONFIRMED", suggestions.recurring().get(2).status().name());
        assertEquals("1450", suggestions.recurring().get(1).planItem().amount().toPlainString());
        assertEquals(1, suggestions.suggestedCandidates());
    }

    @Test
    void countsABillFromASharedAccountAtTheUsersShare() {
        when(accountRepository.findAllByUserIdAndTracksSpendingIsTrue(USER_ID)).thenReturn(List.of(account("joint", 50)));
        when(streamRepository.findAllByUserIdAndAccountIdIn(USER_ID, Set.of("joint"))).thenReturn(List.of(
                stream("electric", "joint", "Con Ed", "180.25", "MONTHLY", false)));

        PlanSuggestionsResponse suggestions = controller.getPlanSuggestions(jwt, true);

        assertEquals("90.13", suggestions.unplaced().get(0).planItem().amount().toPlainString());
        assertEquals(50, suggestions.unplaced().get(0).sharePercent());
    }

    @Test
    void leavesBillsAlreadyOnTheSavedPlanOffTheListToPlaceUnlessStartingANewPlan() {
        trackChecking();
        when(streamRepository.findAllByUserIdAndAccountIdIn(USER_ID, Set.of("checking"))).thenReturn(List.of(
                stream("repairs", "checking", "Handyman", "200", "MONTHLY", false),
                stream("storage", "checking", "Storage Co", "90", "MONTHLY", false)));
        savedPlanWithItem("storage");

        assertEquals(List.of("repairs"), controller.getPlanSuggestions(jwt, false).unplaced().stream()
                .map(RecurringPaymentResponse::streamId).toList());
        assertEquals(List.of("repairs", "storage"), controller.getPlanSuggestions(jwt, true).unplaced().stream()
                .map(RecurringPaymentResponse::streamId).toList());
    }

    @Test
    void hasNoSpendingHistoryWithoutSpending() {
        payeesWithoutCorrections();
        when(spending.between(USER_ID, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31), null))
                .thenReturn(List.of());

        SpendingHistoryResponse history = controller.getSpendingHistory(jwt, YearMonth.of(2026, 11), 3);

        assertEquals(LocalDate.of(2026, 8, 1), history.start());
        assertEquals(LocalDate.of(2026, 10, 31), history.end());
        assertTrue(history.months().isEmpty());
        assertTrue(history.groups().isEmpty());
    }

    @Test
    void averagesSpendingHistoryAcrossEveryTrackedAccountAtTheUsersShare() {
        payeesWithoutCorrections();
        // A year back from January takes in all of last year.
        when(spending.between(USER_ID, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), null))
                .thenReturn(List.of(
                        spentOn(Bucket.FIXED_COSTS, "RENT_AND_UTILITIES", "600", 50, LocalDate.of(2025, 11, 3)),
                        spentOn(Bucket.FIXED_COSTS, "RENT_AND_UTILITIES", "600", 50, LocalDate.of(2025, 12, 3)),
                        spentOn(Bucket.GUILT_FREE, "FOOD_AND_DRINK", "45.50", 100, LocalDate.of(2025, 12, 9))));

        SpendingHistoryResponse history = controller.getSpendingHistory(jwt, YearMonth.of(2026, 1), 12);

        assertEquals(List.of("2025-11", "2025-12"), history.months());
        assertEquals("FIXED_COSTS", history.groups().get(0).bucket());
        CategoryHistoryResponse rent = history.groups().get(0).categories().get(0);
        assertEquals("CATEGORY", rent.kind().name());
        assertEquals("RENT_AND_UTILITIES", rent.category());
        assertEquals("600", rent.average().toPlainString());
        assertEquals("ALL", history.groups().get(1).categories().get(0).kind().name());
        assertEquals("22.75", history.groups().get(1).average().toPlainString());
    }

    @Test
    void looksBackOneToTwelveWholeMonths() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.getSpendingHistory(jwt, YearMonth.of(2026, 11), 0)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.getSpendingHistory(jwt, YearMonth.of(2026, 11), 13)).getStatusCode());
    }

    @Test
    void defaultsEachLineToItsAverageOverTheLastThreeWholeMonths() {
        RecurringPayee grocer = new RecurringPayee(USER_ID, new RecurringMerchant(RecurringKind.BILL, "whole foods"));
        grocer.judged(null, null, SpendingPlanBucket.FIXED_COSTS, "Groceries", "state", Instant.now());
        when(payeeLookup.forUser(USER_ID)).thenReturn(new PayeeLookup.Payees(
                Map.of(), Map.of(), Map.of(grocer.payee(), grocer), Set.of(), new TransactionExclusions.Snapshot(List.of())));
        when(spending.between(USER_ID, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31), null))
                .thenReturn(List.of(
                        groceries("300", LocalDate.of(2026, 8, 4)),
                        groceries("240", LocalDate.of(2026, 9, 11)),
                        groceries("101", LocalDate.of(2026, 10, 20))));

        LineDefaultsResponse defaults = controller.getLineDefaults(jwt, YearMonth.of(2026, 11));

        assertEquals(Map.of("Groceries", new BigDecimal("214")), defaults.lines());
    }

    @Test
    void hasNoLineDefaultsWithoutSpending() {
        payeesWithoutCorrections();
        when(spending.between(eq(USER_ID), any(), any(), isNull())).thenReturn(List.of());

        assertTrue(controller.getLineDefaults(jwt, YearMonth.of(2026, 11)).lines().isEmpty());
    }

    private void trackChecking() {
        when(accountRepository.findAllByUserIdAndTracksSpendingIsTrue(USER_ID)).thenReturn(List.of(account("checking", 100)));
    }

    private void payeesWithoutCorrections() {
        when(payeeLookup.forUser(USER_ID)).thenReturn(new PayeeLookup.Payees(
                Map.of(), Map.of(), Map.of(), Set.of(), new TransactionExclusions.Snapshot(List.of())));
    }

    @SuppressWarnings("unchecked")
    private void savedPlanWithItem(String streamId) {
        when(planService.find(eq(USER_ID), any())).thenAnswer(invocation -> {
            SpendingPlan plan = new SpendingPlan(USER_ID);
            plan.replace(null, null, null, SpendingPlan.DEFAULT_BUFFER_PERCENT, List.of(
                    new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, "Storage", null, false, null, List.of(
                            new SpendingPlanItem("Storage Co", new BigDecimal("90"), streamId)))));
            return Optional.of(((Function<SpendingPlan, ?>) invocation.getArgument(1)).apply(plan));
        });
    }

    private static List<String> itemNames(PlanSuggestionsResponse suggestions, String line) {
        return line(suggestions, line).items().stream().map(SpendingPlanController.ItemResponse::name).toList();
    }

    private static SpendingPlanController.LineResponse line(PlanSuggestionsResponse suggestions, String name) {
        return suggestions.lines().stream().filter(line -> line.name().equals(name)).findFirst().orElseThrow();
    }

    private static PlaidAccount account(String accountId, int sharePercent) {
        PlaidAccount account = new PlaidAccount(accountId, "item-id", USER_ID);
        account.updateSnapshot(accountId, null, null, "depository", "checking", null, null, null, "USD", null);
        account.updateTracking(true, true, sharePercent);
        return account;
    }

    private static PlaidRecurringStream stream(
            String streamId, String accountId, String merchant, String amount, String frequency, boolean inflow) {
        return new PlaidRecurringStream(streamId, "item-id", USER_ID, accountId, new BigDecimal(amount), frequency, inflow)
                .merchantName(merchant)
                .description(merchant.toUpperCase())
                .isoCurrencyCode("USD")
                .nextDate(LocalDate.of(2026, 10, inflow ? 17 : 20 + streamId.length()))
                .lastDate(LocalDate.of(2026, 10, 3))
                .category(inflow ? "INCOME" : "GENERAL_SERVICES")
                .categoryDetailed(inflow ? "INCOME_WAGES" : "GENERAL_SERVICES_OTHER");
    }

    private static RecurringCandidate candidate(
            String merchantKey, String amount, RecurringCandidate.Status status, String planLine) {
        return new RecurringCandidate(RecurringKind.BILL, merchantKey, merchantKey, "checking", new BigDecimal(amount),
                "USD", RecurringFrequency.MONTHLY, false, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1),
                "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES", 100, new BigDecimal("0.9"),
                planLine == null ? null : SpendingPlanBucket.FIXED_COSTS, planLine, status);
    }

    private static Spending.Spent groceries(String amount, LocalDate date) {
        PlaidTransaction transaction = new PlaidTransaction(
                "txn-" + ++transactions, "item-id", USER_ID, "account-1", new BigDecimal(amount), date)
                .personalFinanceCategory("FOOD_AND_DRINK", "FOOD_AND_DRINK_GROCERIES");
        transaction.merchantName("Whole Foods");
        ReflectionTestUtils.setField(transaction, "bucket", Bucket.FIXED_COSTS);
        return new Spending.Spent(transaction, 100);
    }

    private static Spending.Spent spentOn(Bucket bucket, String category, String amount, int sharePercent, LocalDate date) {
        Spending.Spent spent = spent(bucket, category, amount, sharePercent);
        ReflectionTestUtils.setField(spent.transaction(), "transactionDate", date);
        return spent;
    }

    /** 4,000 take-home gives 1,500 guilt-free: 500 from the paycheck, less 2,200 fixed with the buffer, 500 and 300. */
    @SuppressWarnings("unchecked")
    private void planWithTakeHome(String takeHome) {
        when(planService.find(eq(USER_ID), any())).thenAnswer(invocation -> {
            SpendingPlan plan = new SpendingPlan(USER_ID);
            plan.replace(null, takeHome == null ? null : new BigDecimal(takeHome), null, new BigDecimal("10"), List.of(
                    new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, "Rent", new BigDecimal("2000"), false, null, List.of()),
                    new SpendingPlanLine(SpendingPlanBucket.INVESTMENTS, "401(k)", new BigDecimal("500"), true, null, List.of()),
                    new SpendingPlanLine(SpendingPlanBucket.SAVINGS, "Emergency fund", new BigDecimal("300"), false, null, List.of())));
            return Optional.of(((Function<SpendingPlan, ?>) invocation.getArgument(1)).apply(plan));
        });
        when(payeeLookup.forUser(USER_ID)).thenReturn(new PayeeLookup.Payees(
                Map.of(), Map.of(), Map.of(), Set.of(), new TransactionExclusions.Snapshot(List.of())));
    }

    private static int transactions;

    private static Spending.Spent spent(Bucket bucket, String category, String amount, int sharePercent) {
        // amount is the user's part; at a 50% share, the transaction is twice that.
        BigDecimal whole = new BigDecimal(amount).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(sharePercent));
        PlaidTransaction transaction = new PlaidTransaction(
                "txn-" + ++transactions, "item-id", USER_ID, "account-1", whole, LocalDate.of(2026, 10, 5))
                .personalFinanceCategory(category, category + "_OTHER");
        ReflectionTestUtils.setField(transaction, "bucket", bucket);
        return new Spending.Spent(transaction, sharePercent);
    }

    private void assertBadRequest(SpendingPlanRequest request) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.saveSpendingPlan(jwt, request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    private static SpendingPlanRequest planWith(LineRequest line) {
        return new SpendingPlanRequest(null, null, null, null, List.of(line));
    }
}
