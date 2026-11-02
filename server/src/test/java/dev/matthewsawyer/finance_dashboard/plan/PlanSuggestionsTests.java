package dev.matthewsawyer.finance_dashboard.plan;

import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket.FIXED_COSTS;
import static dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket.INVESTMENTS;
import static dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket.SAVINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same cases and numbers the web's fromRecurring tests used before the rules moved here. */
class PlanSuggestionsTests {

    @Test
    void turnsEachCadenceIntoAMonthlyAmount() {
        assertMoney("2166.67", PlanSuggestions.monthlyAmount(pay("500", "WEEKLY")));
        assertMoney("5200", PlanSuggestions.monthlyAmount(pay("-2400", "BIWEEKLY")));
        assertMoney("4200", PlanSuggestions.monthlyAmount(pay("2100", "SEMI_MONTHLY")));
        assertMoney("1450", PlanSuggestions.monthlyAmount(pay("1450", "MONTHLY")));
        assertMoney("400", PlanSuggestions.monthlyAmount(pay("1200", "QUARTERLY")));
        assertMoney("200", PlanSuggestions.monthlyAmount(pay("1200", "SEMI_ANNUALLY")));
        assertMoney("100", PlanSuggestions.monthlyAmount(pay("1200", "ANNUALLY")));
        assertMoney("80", PlanSuggestions.monthlyAmount(pay("80", "UNKNOWN")));
    }

    @Test
    void sumsRecurringDepositsIntoMonthlyTakeHome() {
        PlanSuggestions suggestions = PlanSuggestions.of(List.of(
                pay("-2400", "BIWEEKLY"),
                payment("side").merchant("Consulting").amount("-400").build()), Set.of());

        assertMoney("5600", suggestions.takeHome());
    }

    @Test
    void leavesTransfersInOutOfTakeHomeSuchAsAHousematePayingIntoASharedAccount() {
        PlanSuggestions suggestions = PlanSuggestions.of(List.of(
                pay("-2400", "BIWEEKLY"),
                payment("housemate").merchant("Transfer from Sam").amount("-1200").share(50)
                        .category("TRANSFER_IN", "TRANSFER_IN_ACCOUNT_TRANSFER").build()), Set.of());

        assertMoney("5200", suggestions.takeHome());
    }

    @Test
    void hasNoTakeHomeWithoutDeposits() {
        assertNull(PlanSuggestions.of(List.of(bill("rent", "1450").build()), Set.of()).takeHome());
        assertNull(PlanSuggestions.of(List.of(), Set.of()).takeHome());
    }

    @Test
    void alwaysListsEverySpreadsheetLineInOrderWith401kFromThePaycheck() {
        List<PlanSuggestions.Line> lines = PlanSuggestions.of(List.of(), Set.of()).lines();

        assertEquals(List.of("Rent/mortgage", "Utilities", "Insurance", "Car payment", "Debt payments", "Groceries",
                "Clothes", "Phone", "Internet", "Subscriptions"), names(lines, FIXED_COSTS));
        assertEquals(List.of("401(k)", "Roth IRA", "Other investments"), names(lines, INVESTMENTS));
        assertEquals(List.of("Emergency fund", "Vacations", "Gifts", "House down payment"), names(lines, SAVINGS));
        assertEquals(List.of("401(k)"), lines.stream().filter(PlanSuggestions.Line::fromPaycheck)
                .map(PlanSuggestions.Line::name).toList());
        assertTrue(lines.stream().allMatch(line -> line.items().isEmpty()));
    }

    @Test
    void putsEachRecurringBillOnTheLineJevChoseAsAMonthlyItem() {
        List<PlanSuggestions.Line> lines = PlanSuggestions.of(List.of(
                bill("rent", "1450").onLine(FIXED_COSTS, "Rent/mortgage").build(),
                bill("netflix", "15.49").merchant("Netflix").onLine(FIXED_COSTS, "Subscriptions").build(),
                bill("gym", "120").merchant(null).description("PLANET FITNESS").frequency("ANNUALLY")
                        .onLine(FIXED_COSTS, "Subscriptions").build(),
                bill("insurance", "1200").frequency("ANNUALLY").onLine(FIXED_COSTS, "Insurance").build()),
                Set.of()).lines();

        assertEquals(List.of(new PlanSuggestions.Item("rent", money("1450.00"), "rent")),
                line(lines, "Rent/mortgage").items());
        assertEquals(List.of(
                new PlanSuggestions.Item("Netflix", money("15.49"), "netflix"),
                new PlanSuggestions.Item("PLANET FITNESS", money("10.00"), "gym")),
                line(lines, "Subscriptions").items());
        assertMoney("100", line(lines, "Insurance").items().get(0).amount());
        assertTrue(line(lines, "Utilities").items().isEmpty());
    }

    @Test
    void countsABillFromASharedAccountAtTheUsersShare() {
        List<PlanSuggestions.Line> lines = PlanSuggestions.of(List.of(
                bill("rent", "2400").share(50).onLine(FIXED_COSTS, "Rent/mortgage").build()), Set.of()).lines();

        assertMoney("1200", line(lines, "Rent/mortgage").items().get(0).amount());
    }

    @Test
    void namesABillFromItsCategoryWhenPlaidSentABlankName() {
        List<PlanSuggestions.Line> lines = PlanSuggestions.of(List.of(
                bill("rent", "1450").merchant("").description("   ")
                        .category("RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT")
                        .onLine(FIXED_COSTS, "Rent/mortgage").build(),
                bill("odd", "10").merchant(null).description(null).category("NEW_THING", null)
                        .onLine(FIXED_COSTS, "Phone").build(),
                bill("none", "5").merchant(null).description(null).category(null, null)
                        .onLine(FIXED_COSTS, "Internet").build()), Set.of()).lines();

        assertEquals("Rent & utilities", line(lines, "Rent/mortgage").items().get(0).name());
        assertEquals("New Thing", line(lines, "Phone").items().get(0).name());
        assertEquals("Recurring", line(lines, "Internet").items().get(0).name());
    }

    @Test
    void addsALineTheSpreadsheetDoesNotHaveAndLeavesPayOff() {
        List<PlanSuggestions.Line> lines = PlanSuggestions.of(List.of(
                bill("trip", "300").merchant("Japan fund").onLine(SAVINGS, "Japan trip").build(),
                payment("pay").onLine(FIXED_COSTS, "Rent/mortgage").build()), Set.of()).lines();

        PlanSuggestions.Line trip = lines.get(lines.size() - 1);
        assertEquals(new PlanSuggestions.Line(SAVINGS, "Japan trip", false,
                List.of(new PlanSuggestions.Item("Japan fund", money("300.00"), "trip"))), trip);
        assertTrue(line(lines, "Rent/mortgage").items().isEmpty());
    }

    @Test
    void offersBillsOnNoLineToPlaceButNotCardPaymentsPayOrBillsAlreadyOnTheSavedPlan() {
        RecurringPayment repairs = bill("repairs", "1554.91")
                .category("HOME_IMPROVEMENT", "HOME_IMPROVEMENT_REPAIR_AND_MAINTENANCE").build();
        RecurringPayment placedBefore = bill("placed", "40").build();

        PlanSuggestions suggestions = PlanSuggestions.of(List.of(
                repairs,
                placedBefore,
                bill("netflix", "15.49").onLine(FIXED_COSTS, "Subscriptions").build(),
                bill("card", "900").category("LOAN_PAYMENTS", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT").build(),
                pay("-2400", "MONTHLY")), Set.of("placed"));

        assertEquals(List.of(repairs), suggestions.unplaced());
    }

    @Test
    void offersABillWithALineNameButNoBucketToPlaceRatherThanLosingIt() {
        RecurringPayment halfJudged = bill("odd", "25").onLine(null, "Phone").build();

        PlanSuggestions suggestions = PlanSuggestions.of(List.of(halfJudged), Set.of());

        assertEquals(List.of(halfJudged), suggestions.unplaced());
        assertTrue(line(suggestions.lines(), "Phone").items().isEmpty());
    }

    private static List<String> names(List<PlanSuggestions.Line> lines, SpendingPlanBucket bucket) {
        return lines.stream().filter(line -> line.bucket() == bucket).map(PlanSuggestions.Line::name).toList();
    }

    private static PlanSuggestions.Line line(List<PlanSuggestions.Line> lines, String name) {
        return lines.stream().filter(line -> line.name().equals(name)).findFirst().orElseThrow();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static RecurringPayment pay(String amount, String frequency) {
        return payment("pay").amount(amount).frequency(frequency).build();
    }

    static Builder bill(String streamId, String amount) {
        return payment(streamId).merchant(streamId).amount(amount).bill()
                .category("GENERAL_SERVICES", "GENERAL_SERVICES_OTHER");
    }

    static Builder payment(String streamId) {
        return new Builder(streamId);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " but was " + actual);
    }

    /** A recurring payment, by default a monthly paycheck of 2,400 into checking. */
    static final class Builder {
        private final String streamId;
        private String accountId = "checking";
        private String merchantName = "Payroll";
        private String description = "PAYROLL";
        private BigDecimal amount = new BigDecimal("-2400");
        private String frequency = "MONTHLY";
        private LocalDate nextDate = LocalDate.of(2026, 10, 25);
        private LocalDate lastDate = LocalDate.of(2026, 9, 25);
        private boolean inflow = true;
        private String category = "INCOME";
        private String categoryDetailed = "INCOME_WAGES";
        private int sharePercent = 100;
        private SpendingPlanBucket planBucket;
        private String planLine;

        private Builder(String streamId) {
            this.streamId = streamId;
        }

        Builder merchant(String name) {
            merchantName = name;
            return this;
        }

        Builder description(String text) {
            description = text;
            return this;
        }

        Builder amount(String value) {
            amount = new BigDecimal(value);
            return this;
        }

        Builder frequency(String value) {
            frequency = value;
            return this;
        }

        Builder bill() {
            inflow = false;
            return this;
        }

        Builder category(String primary, String detailed) {
            category = primary;
            categoryDetailed = detailed;
            return this;
        }

        Builder share(int percent) {
            accountId = "joint";
            sharePercent = percent;
            return this;
        }

        Builder onLine(SpendingPlanBucket bucket, String line) {
            planBucket = bucket;
            planLine = line;
            return this;
        }

        Builder dates(LocalDate next, LocalDate last) {
            nextDate = next;
            lastDate = last;
            return this;
        }

        RecurringPayment build() {
            return new RecurringPayment(streamId, accountId, merchantName, description, amount, "USD", frequency,
                    false, nextDate, lastDate, inflow, category, categoryDetailed, sharePercent, planBucket,
                    planLine, null, null, RecurringPayment.Source.DETECTED);
        }
    }
}
