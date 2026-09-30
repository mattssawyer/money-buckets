package dev.matthewsawyer.finance_dashboard.recurring;

import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringAnswer;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.repository.RecurringAnswerRepository;
import dev.matthewsawyer.finance_dashboard.repository.RecurringPayeeRepository;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class RecurringCandidatesTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private PlaidAccountRepository accounts;
    @Autowired private PlaidRecurringStreamRepository streams;
    @Autowired private RecurringAnswerRepository answers;
    @Autowired private RecurringPayeeRepository payees;
    @Autowired private RecurringPayees recurringPayees;
    @Autowired private TrackedAccounts trackedAccounts;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;

    private RecurringCandidates candidates;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        candidates = new RecurringCandidates(recurringPayees, answers, trackedAccounts, transactionTemplate, clock);
        account("checking", true, 100);
    }

    @Test
    void suggestsAPayeeJevJudgesLikelyToBeRegular() {
        charge("netflix", "Netflix", "15.49", TODAY.minusDays(10));
        judgeBill("netflix", 0.93, RecurringFrequency.MONTHLY);
        charge("coffee", "Starbucks", "6.00", TODAY.minusDays(2));
        judgeBill("starbucks", 0.08, RecurringFrequency.WEEKLY);

        List<RecurringCandidate> found = candidates.find(userId, null);

        assertEquals(1, found.size());
        RecurringCandidate netflix = found.get(0);
        assertEquals("netflix", netflix.merchantKey());
        assertEquals(RecurringKind.BILL, netflix.kind());
        assertEquals(RecurringCandidate.Status.SUGGESTED, netflix.status());
        assertEquals(new BigDecimal("15.4900"), netflix.amount());
        assertEquals(RecurringFrequency.MONTHLY, netflix.frequency());
        assertEquals(TODAY.minusDays(10).plusMonths(1), netflix.nextDate());
    }

    @Test
    void readsThePayeesScheduleFromItsChargeDates() {
        charge("spotify-1", "Spotify", "11.99", TODAY.minusDays(35));
        charge("spotify-2", "Spotify", "11.99", TODAY.minusDays(28));
        charge("spotify-3", "Spotify", "11.99", TODAY.minusDays(21));
        judgeBill("spotify", 0.9, RecurringFrequency.MONTHLY);

        RecurringCandidate spotify = candidates.find(userId, null).get(0);

        assertEquals(RecurringFrequency.WEEKLY, spotify.frequency());
        assertEquals(TODAY.minusDays(21), spotify.lastDate());
        assertNull(spotify.nextDate(), "the next charge was due a week ago");
    }

    @Test
    void pricesASubscriptionByItsRepeatingAmountRatherThanOneOffPurchases() {
        charge("ride-2", "Uber", "20.03", TODAY.minusDays(1));
        charge("one-2", "Uber", "9.99", TODAY.minusDays(3));
        charge("ride-1", "Uber", "18.62", TODAY.minusDays(17));
        charge("one-1", "Uber", "9.99", TODAY.minusDays(34));
        judgeBill("uber", 0.91, RecurringFrequency.WEEKLY);

        RecurringCandidate uber = candidates.find(userId, null).get(0);

        assertEquals(new BigDecimal("9.9900"), uber.amount());
        assertEquals(TODAY.minusDays(3), uber.lastDate());
        assertEquals(RecurringFrequency.MONTHLY, uber.frequency());
    }

    @Test
    void showsANewPriceOnceItRepeats() {
        charge("netflix-3", "Netflix", "17.99", TODAY.minusDays(1));
        charge("netflix-2", "Netflix", "17.99", TODAY.minusDays(31));
        charge("netflix-1", "Netflix", "15.49", TODAY.minusDays(61));
        charge("netflix-0", "Netflix", "15.49", TODAY.minusDays(91));
        charge("netflix-00", "Netflix", "15.49", TODAY.minusDays(121));
        judgeBill("netflix", 0.97, RecurringFrequency.MONTHLY);

        assertEquals(new BigDecimal("17.9900"), candidates.find(userId, null).get(0).amount());
    }

    @Test
    void carriesThePlanLineJevChoseForThePayee() {
        charge("rent", "Sterling Group", "1554.91", TODAY.minusDays(28));
        payees.saveAndFlush(payee(RecurringKind.BILL, "sterling group", 0.9, RecurringFrequency.MONTHLY,
                SpendingPlanBucket.FIXED_COSTS, "Rent/mortgage"));

        RecurringCandidate rent = candidates.find(userId, null).get(0);

        assertEquals(SpendingPlanBucket.FIXED_COSTS, rent.planBucket());
        assertEquals("Rent/mortgage", rent.planLine());
    }

    @Test
    void leavesOutPayeesNotJudgedYetUnlessConfirmed() {
        charge("netflix", "Netflix", "15.49", TODAY.minusDays(10));
        assertTrue(candidates.find(userId, null).isEmpty());

        candidates.answer(userId, RecurringKind.BILL, "netflix", true);

        RecurringCandidate netflix = candidates.find(userId, null).get(0);
        assertEquals(RecurringCandidate.Status.CONFIRMED, netflix.status());
        assertEquals(RecurringFrequency.MONTHLY, netflix.frequency());
    }

    @Test
    void suggestsPayAsAPaycheck() {
        store(new PlaidTransaction("pay", "item", userId, "checking", new BigDecimal("-2400"), TODAY.minusDays(4))
                .name("ACME CORP PAYROLL 092526")
                .personalFinanceCategory("INCOME", "INCOME_SALARY"));
        payees.saveAndFlush(payee(RecurringKind.PAYCHECK, "acme corp payroll", 0.97, RecurringFrequency.BIWEEKLY,
                null, null));

        RecurringCandidate pay = candidates.find(userId, null).get(0);

        assertEquals(RecurringKind.PAYCHECK, pay.kind());
        assertEquals("acme corp payroll", pay.merchantKey());
        assertEquals(RecurringFrequency.BIWEEKLY, pay.frequency());
    }

    @Test
    void leavesOutPayeesPlaidAlreadyDetects() {
        charge("netflix", "Netflix", "15.49", TODAY.minusDays(10));
        judgeBill("netflix", 0.93, RecurringFrequency.MONTHLY);
        streams.saveAndFlush(new PlaidRecurringStream(
                "stream", "item", userId, "checking", new BigDecimal("15.49"), "MONTHLY", false)
                .merchantName("NETFLIX"));

        assertTrue(candidates.find(userId, null).isEmpty());
    }

    @Test
    void leavesOutUntrackedAccountsAndMovesBetweenOwnAccounts() {
        account("savings", false, 100);
        store(new PlaidTransaction("gym", "item", userId, "savings", new BigDecimal("40"), TODAY.minusDays(5))
                .merchantName("Planet Fitness"));
        sort("gym", Bucket.FIXED_COSTS);
        judgeBill("planet fitness", 0.9, RecurringFrequency.MONTHLY);
        charge("to-savings", "Transfer to savings", "500", TODAY.minusDays(5));
        sort("to-savings", Bucket.NOT_COUNTED);
        judgeBill("transfer to savings", 0.8, RecurringFrequency.MONTHLY);

        assertTrue(candidates.find(userId, null).isEmpty());
    }

    @Test
    void remembersAnswersAndForgetsThemOnUndo() {
        charge("netflix", "Netflix", "15.49", TODAY.minusDays(10));
        judgeBill("netflix", 0.93, RecurringFrequency.MONTHLY);
        charge("hulu", "Hulu", "7.99", TODAY.minusDays(8));
        judgeBill("hulu", 0.9, RecurringFrequency.MONTHLY);

        candidates.answer(userId, RecurringKind.BILL, "netflix", true);
        candidates.answer(userId, RecurringKind.BILL, "hulu", false);

        assertEquals(RecurringCandidate.Status.CONFIRMED, status("netflix"));
        assertEquals(RecurringCandidate.Status.DISMISSED, status("hulu"));

        candidates.undo(userId, RecurringKind.BILL, "hulu");
        assertEquals(RecurringCandidate.Status.SUGGESTED, status("hulu"));
    }

    @Test
    void changesAnAnswerInPlace() {
        charge("netflix", "Netflix", "15.49", TODAY.minusDays(10));
        judgeBill("netflix", 0.93, RecurringFrequency.MONTHLY);

        candidates.answer(userId, RecurringKind.BILL, "netflix", false);
        candidates.answer(userId, RecurringKind.BILL, "netflix", true);

        assertEquals(1, answers.findAllByUserId(userId).size());
        assertEquals(RecurringCandidate.Status.CONFIRMED, status("netflix"));
    }

    @Test
    void updatesTheOtherAnswerWhenTwoAnswersForANewMerchantClash() {
        RecurringAnswerRepository clashing = mock(RecurringAnswerRepository.class);
        RecurringAnswer storedFirst = new RecurringAnswer(userId, RecurringKind.BILL, "netflix");
        storedFirst.answer(false, Instant.now());
        // Nothing is stored when this answer looks, but another request adds one before it saves.
        when(clashing.findByUserIdAndKindAndMerchantKey(userId, RecurringKind.BILL, "netflix"))
                .thenReturn(Optional.empty(), Optional.of(storedFirst));
        when(clashing.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("recurring_answers_unique"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        RecurringCandidates racing = new RecurringCandidates(recurringPayees, clashing, trackedAccounts,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.systemDefaultZone());

        racing.answer(userId, RecurringKind.BILL, "netflix", true);

        verify(clashing, times(2)).saveAndFlush(any());
        assertTrue(storedFirst.isConfirmed());
    }

    @Test
    void keepsAConfirmedPayeeWhenJevDoubtsIt() {
        charge("netflix", "Netflix", "15.49", TODAY.minusDays(10));
        judgeBill("netflix", 0.3, RecurringFrequency.MONTHLY);
        assertTrue(candidates.find(userId, null).isEmpty());

        candidates.answer(userId, RecurringKind.BILL, "netflix", true);

        assertEquals(RecurringCandidate.Status.CONFIRMED, status("netflix"));
    }

    @Test
    void appliesTheAccountShare() {
        account("joint", true, 50);
        store(new PlaidTransaction("power", "item", userId, "joint", new BigDecimal("120"), TODAY.minusDays(6))
                .merchantName("ConEd"));
        sort("power", Bucket.FIXED_COSTS);
        judgeBill("coned", 0.95, RecurringFrequency.MONTHLY);

        assertEquals(50, candidates.find(userId, null).get(0).sharePercent());
    }

    @Test
    void readsSchedulesFromTheGapsBetweenCharges() {
        assertEquals(RecurringFrequency.BIWEEKLY, RecurringCandidates.frequency(
                List.of(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 11)), RecurringFrequency.MONTHLY));
        assertEquals(RecurringFrequency.SEMI_MONTHLY, RecurringCandidates.frequency(
                List.of(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 15)),
                RecurringFrequency.MONTHLY));
        assertEquals(RecurringFrequency.MONTHLY, RecurringCandidates.frequency(
                List.of(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 8, 3)), RecurringFrequency.WEEKLY));
        assertEquals(RecurringFrequency.ANNUALLY, RecurringCandidates.frequency(
                List.of(LocalDate.of(2026, 9, 3)), RecurringFrequency.ANNUALLY));
        assertEquals(RecurringFrequency.QUARTERLY, RecurringCandidates.frequency(
                List.of(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4)), RecurringFrequency.QUARTERLY));
    }

    @Test
    void keysMerchantsWithoutPunctuationOrChangingNumbers() {
        assertEquals("netflix com", RecurringMerchant.key("Netflix.com", null));
        assertEquals("7 eleven", RecurringMerchant.key("7-Eleven", "7-ELEVEN #1234"));
        assertEquals("acme corp payroll ppd id", RecurringMerchant.key(" ", "ACME CORP PAYROLL PPD ID: 4411"));
        assertNull(RecurringMerchant.key(null, "1234"));
    }

    private void account(String accountId, boolean tracked, int sharePercent) {
        PlaidAccount account = new PlaidAccount(accountId, "item", userId);
        account.updateSnapshot(accountId, null, null, "depository", tracked ? "checking" : "savings",
                null, null, null, "USD", null);
        account.updateTracking(tracked, true, sharePercent);
        accounts.saveAndFlush(account);
    }

    /** Money out in the checking account, already sorted, as payees' charges are. */
    private void charge(String id, String merchant, String amount, LocalDate date) {
        store(new PlaidTransaction(id, "item", userId, "checking", new BigDecimal(amount), date)
                .merchantName(merchant)
                .personalFinanceCategory("ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"));
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

    private void judgeBill(String merchantKey, double probability, RecurringFrequency frequency) {
        payees.saveAndFlush(payee(RecurringKind.BILL, merchantKey, probability, frequency, null, null));
    }

    private RecurringPayee payee(RecurringKind kind, String merchantKey, double probability,
                                 RecurringFrequency frequency, SpendingPlanBucket bucket, String line) {
        RecurringPayee payee = new RecurringPayee(userId, new RecurringMerchant(kind, merchantKey));
        payee.judged(BigDecimal.valueOf(probability), frequency, bucket, line, "state", Instant.now());
        return payee;
    }

    private RecurringCandidate.Status status(String merchantKey) {
        return candidates.find(userId, null).stream()
                .filter(candidate -> candidate.merchantKey().equals(merchantKey))
                .findFirst()
                .orElseThrow()
                .status();
    }
}
