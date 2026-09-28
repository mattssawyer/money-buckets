package dev.matthewsawyer.finance_dashboard.history;

import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.AccountAdded;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.AccountDropped;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.AccountSeries;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.History;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.Point;
import dev.matthewsawyer.finance_dashboard.model.AccountDrop;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.repository.AccountDropRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class BalanceHistoryTests {

    private static final String ITEM = "history-item";
    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate WED = MON.plusDays(2);
    private static final LocalDate THU = MON.plusDays(3);

    @Autowired private BalanceHistory history;
    @Autowired private PlaidAccountRepository accounts;
    @Autowired private AccountDropRepository drops;

    private final UUID userId = UUID.randomUUID();

    @Test
    void carriesTheLastBalanceForwardOverDaysWithoutASync() {
        account("ira", "investment", "1000");
        history.record(ITEM, MON);
        account("ira", "investment", "1100");
        history.record(ITEM, THU);

        History result = history.forUser(userId, null, THU);

        assertEquals(List.of(
                point(MON, "1000"), point(TUE, "1000"), point(WED, "1000"), point(THU, "1100")
        ), rendered(onlyAccount(result).points()));
    }

    @Test
    void aLaterSyncTheSameDayReplacesThatDaysBalance() {
        account("ira", "investment", "1000");
        history.record(ITEM, MON);
        account("ira", "investment", "1050");
        history.record(ITEM, MON);

        History result = history.forUser(userId, null, MON);

        assertEquals(List.of(point(MON, "1050")), rendered(onlyAccount(result).points()));
    }

    @Test
    void netWorthSubtractsCardsAndLoansAndLeavesOutOtherCurrencies() {
        account("checking", "depository", "5000");
        account("ira", "investment", "20000");
        account("card", "credit", "700");
        account("car", "loan", "9000");
        account("euro-savings", "depository", "3000", "EUR");
        history.record(ITEM, MON);

        History result = history.forUser(userId, null, MON);

        assertEquals(List.of(point(MON, "15300")), rendered(result.netWorth()));
        assertEquals(List.of("euro-savings"), result.leftOutOfNetWorth());
    }

    @Test
    void netWorthLeavesOutAccountsTheUserExcludedButKeepsTheirSeries() {
        account("checking", "depository", "5000");
        account("employer-401k", "investment", "20000");
        history.record(ITEM, MON);
        choose("employer-401k", false, 100);

        History result = history.forUser(userId, null, MON);

        assertEquals(List.of(point(MON, "5000")), rendered(result.netWorth()));
        assertTrue(result.leftOutOfNetWorth().isEmpty());
        assertEquals(List.of("employer-401k"),
                result.investmentAccounts().stream().map(AccountSeries::accountId).toList());
    }

    @Test
    void netWorthCountsASharedAccountAtTheUsersShare() {
        account("checking", "depository", "5000");
        account("joint", "depository", "3000");
        account("joint-card", "credit", "400");
        history.record(ITEM, MON);
        choose("joint", true, 50);
        choose("joint-card", true, 50);

        History result = history.forUser(userId, null, MON);

        assertEquals(List.of(point(MON, "6300")), rendered(result.netWorth()));
    }

    @Test
    void onlyInvestmentAccountsGetTheirOwnSeries() {
        account("checking", "depository", "5000");
        account("ira", "investment", "20000");
        account("old-brokerage", "brokerage", "300");
        history.record(ITEM, MON);

        History result = history.forUser(userId, null, MON);

        assertEquals(List.of("ira", "old-brokerage"),
                result.investmentAccounts().stream().map(AccountSeries::accountId).toList());
    }

    @Test
    void anAccountLinkedLaterJoinsNetWorthOnItsFirstDayAndIsMarked() {
        account("checking", "depository", "5000");
        history.record(ITEM, MON);
        account("401k", "investment", "40000");
        history.record(ITEM, WED);

        History result = history.forUser(userId, null, WED);

        assertEquals(List.of(
                point(MON, "5000"), point(TUE, "5000"), point(WED, "45000")
        ), rendered(result.netWorth()));
        assertEquals(List.of(new AccountAdded(WED, "401k", "401k")), result.accountsAdded());
    }

    @Test
    void accountsPresentOnTheFirstDayAreNotMarkedAsAdded() {
        account("checking", "depository", "5000");
        account("ira", "investment", "20000");
        history.record(ITEM, MON);

        assertTrue(history.forUser(userId, null, MON).accountsAdded().isEmpty());
    }

    @Test
    void aRangeStartingAfterTheFirstSnapshotOpensOnTheBalanceCarriedIntoIt() {
        account("ira", "investment", "1000");
        history.record(ITEM, MON);
        account("401k", "investment", "40000");
        history.record(ITEM, TUE);

        History result = history.forUser(userId, WED, THU);

        assertEquals(List.of(point(WED, "41000"), point(THU, "41000")), rendered(result.netWorth()));
        assertTrue(result.accountsAdded().isEmpty());
    }

    @Test
    void aDroppedAccountLeavesNetWorthFromTheDayItWasDroppedAndLosesItsSeries() {
        account("checking", "depository", "5000");
        account("ira", "investment", "20000");
        history.record(ITEM, MON);
        drop("ira", WED);

        History result = history.forUser(userId, null, THU);

        assertEquals(List.of(
                point(MON, "25000"), point(TUE, "25000"), point(WED, "5000"), point(THU, "5000")
        ), rendered(result.netWorth()));
        assertTrue(result.investmentAccounts().isEmpty());
        assertEquals(List.of(new AccountDropped(WED, "ira", "ira")), result.accountsDropped());
    }

    @Test
    void aRestoredAccountIsLeftOutOnlyWhileItWasDropped() {
        account("checking", "depository", "5000");
        account("ira", "investment", "20000");
        history.record(ITEM, MON);
        drop("ira", TUE);
        restore("ira", THU);
        history.record(ITEM, THU);

        History result = history.forUser(userId, null, THU);

        assertEquals(List.of(
                point(MON, "25000"), point(TUE, "5000"), point(WED, "5000"), point(THU, "25000")
        ), rendered(result.netWorth()));
        assertEquals(List.of(point(MON, "20000"), point(THU, "20000")),
                rendered(onlyAccount(result).points()));
        assertEquals(List.of(new AccountDropped(TUE, "ira", "ira")), result.accountsDropped());
        assertEquals(List.of(new AccountAdded(THU, "ira", "ira")), result.accountsAdded());
    }

    @Test
    void aDroppedAccountIsNotListedAsLeftOutOfNetWorth() {
        account("euro-savings", "depository", "3000", "EUR");
        drop("euro-savings", MON);

        assertTrue(history.forUser(userId, null, MON).leftOutOfNetWorth().isEmpty());
    }

    @Test
    void accountsWithoutACurrentBalanceAreNotRecorded() {
        account("ira", "investment", null);
        history.record(ITEM, MON);

        History result = history.forUser(userId, null, MON);

        assertTrue(onlyAccount(result).points().isEmpty());
        assertTrue(result.netWorth().isEmpty());
    }

    @Test
    void doesNotRecordOtherItemsAccounts() {
        account("ira", "investment", "1000");
        history.record("some-other-item", MON);

        assertTrue(history.forUser(userId, null, MON).netWorth().isEmpty());
    }

    private void account(String id, String type, String currentBalance) {
        account(id, type, currentBalance, "USD");
    }

    private void account(String id, String type, String currentBalance, String currency) {
        PlaidAccount account = accounts.findById(id)
                .orElseGet(() -> new PlaidAccount(id, ITEM, userId));
        account.updateSnapshot(id, null, null, type, null, null,
                currentBalance == null ? null : new BigDecimal(currentBalance),
                null, currency, null);
        accounts.saveAndFlush(account);
    }

    private void choose(String id, boolean countsInNetWorth, int sharePercent) {
        PlaidAccount account = accounts.findById(id).orElseThrow();
        account.updateTracking(false, countsInNetWorth, sharePercent);
        accounts.saveAndFlush(account);
    }

    /** What accounts sync does when Plaid returns a dropped account again. */
    private void restore(String id, LocalDate day) {
        PlaidAccount account = accounts.findById(id).orElseThrow();
        drops.saveAndFlush(new AccountDrop(id, userId, account.getDroppedOn(), day));
        account.restore();
        accounts.saveAndFlush(account);
    }

    private void drop(String id, LocalDate day) {
        PlaidAccount account = accounts.findById(id).orElseThrow();
        account.drop(day);
        accounts.saveAndFlush(account);
    }

    private static String point(LocalDate day, String value) {
        return day + " " + value;
    }

    private static List<String> rendered(List<Point> points) {
        return points.stream()
                .map(p -> point(p.day(), p.value().stripTrailingZeros().toPlainString()))
                .toList();
    }

    private static AccountSeries onlyAccount(History result) {
        assertEquals(1, result.investmentAccounts().size());
        return result.investmentAccounts().get(0);
    }
}
