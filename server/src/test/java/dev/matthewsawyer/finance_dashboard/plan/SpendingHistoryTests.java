package dev.matthewsawyer.finance_dashboard.plan;

import dev.matthewsawyer.finance_dashboard.plan.SpendingHistory.Category;
import dev.matthewsawyer.finance_dashboard.plan.SpendingHistory.Entry;
import dev.matthewsawyer.finance_dashboard.plan.SpendingHistory.Kind;
import dev.matthewsawyer.finance_dashboard.plan.SpendingHistory.Payee;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same cases and numbers the web's spending history tests used before the rules moved here. */
class SpendingHistoryTests {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private static final SpendingHistory HISTORY = SpendingHistory.of(List.of(
            entry("GUILT_FREE", "Dig Inn", "60", "2026-09-02", null, "FOOD_AND_DRINK", "FOOD_AND_DRINK_RESTAURANT"),
            entry("GUILT_FREE", "Whole Foods", "30", "2026-09-03", null, "FOOD_AND_DRINK", "FOOD_AND_DRINK_GROCERIES"),
            entry("FIXED_COSTS", "MTA", "390", "2026-08-01", null, "TRANSPORTATION", "TRANSPORTATION_PUBLIC_TRANSIT"),
            groceries("Whole Foods", "300", "2026-07-04"),
            groceries("Whole Foods", "240", "2026-08-11"),
            groceries("Trader Joe’s", "90", "2026-09-20"),
            entry("FIXED_COSTS", "Uniqlo", "150", "2026-08-15", "Clothes", "GENERAL_MERCHANDISE", null)),
            SEPTEMBER);

    @Test
    void listsFixedCostsByLineThenByCategoryForSpendingOnNoLineAndGuiltFreeAsOneTotal() {
        assertEquals(List.of(YearMonth.of(2026, 7), YearMonth.of(2026, 8), SEPTEMBER), HISTORY.months());
        assertEquals(List.of("FIXED_COSTS", "GUILT_FREE"),
                HISTORY.groups().stream().map(SpendingHistory.Group::bucket).toList());
        assertMoney("390", HISTORY.groups().get(0).average());
        assertMoney("30", HISTORY.groups().get(1).average());

        List<Category> fixed = HISTORY.groups().get(0).categories();
        assertEquals(List.of("Groceries", "Clothes"), List.of(fixed.get(0).line(), fixed.get(1).line()));
        assertEquals(List.of(Kind.LINE, Kind.LINE, Kind.CATEGORY), fixed.stream().map(Category::kind).toList());
        assertEquals("TRANSPORTATION", fixed.get(2).category());
        assertEquals("TRANSPORTATION_PUBLIC_TRANSIT", fixed.get(2).categoryDetailed());
        assertMoney("210", fixed.get(0).average());
        assertMoney("50", fixed.get(1).average());
        assertMoney("130", fixed.get(2).average());

        Category guiltFree = HISTORY.groups().get(1).categories().get(0);
        assertEquals(Kind.ALL, guiltFree.kind());
        assertEquals("GUILT_FREE/all", guiltFree.key());
        assertEquals(2, guiltFree.payees().size());
    }

    @Test
    void averagesALinesPayeesPerMonthLargestFirst() {
        Category groceries = HISTORY.groups().get(0).categories().get(0);

        assertEquals("FIXED_COSTS/line/Groceries", groceries.key());
        assertEquals(List.of(
                new Payee("FIXED_COSTS/line/Groceries|Whole Foods", "Whole Foods", new BigDecimal("180.00")),
                new Payee("FIXED_COSTS/line/Groceries|Trader Joe’s", "Trader Joe’s", new BigDecimal("30.00"))),
                groceries.payees());
    }

    @Test
    void averagesOnlyOverMonthsSinceTheFirstTransaction() {
        SpendingHistory recent = SpendingHistory.of(List.of(
                groceries("Whole Foods", "100", "2026-08-20"),
                groceries("Whole Foods", "300", "2026-09-11")), SEPTEMBER);

        assertEquals(List.of(YearMonth.of(2026, 8), SEPTEMBER), recent.months());
        assertMoney("200", recent.groups().get(0).average());
    }

    @Test
    void netsRefundsAndDropsWhatNetsToNothing() {
        SpendingHistory netted = SpendingHistory.of(List.of(
                groceries("Costco", "300", "2026-07-10"),
                entry("FIXED_COSTS", "Uniqlo", "90", "2026-07-12", "Clothes", "GENERAL_MERCHANDISE", null),
                entry("FIXED_COSTS", "Uniqlo", "-90", "2026-07-20", "Clothes", "GENERAL_MERCHANDISE", null)),
                SEPTEMBER);

        List<Category> categories = netted.groups().get(0).categories();
        assertEquals(1, categories.size());
        assertEquals("Groceries", categories.get(0).line());
        assertMoney("100", categories.get(0).average());
    }

    @Test
    void leavesOutAPlaidCategoryThatRefundsBringToNothingInABucketLikeTheSpendingChart() {
        // The chart drops a bucket's Plaid category that nets to nothing, lines and all.
        SpendingHistory netted = SpendingHistory.of(List.of(
                groceries("Whole Foods", "90", "2026-09-04"),
                entry("FIXED_COSTS", "Whole Foods", "-120", "2026-09-05", null, "FOOD_AND_DRINK", null),
                entry("FIXED_COSTS", "Uniqlo", "60", "2026-09-06", "Clothes", "GENERAL_MERCHANDISE", null)),
                SEPTEMBER);

        assertEquals(List.of(SEPTEMBER), netted.months());
        assertEquals(List.of("Clothes"),
                netted.groups().get(0).categories().stream().map(Category::line).toList());
    }

    @Test
    void roundsHalfCentsUp() {
        SpendingHistory uneven = SpendingHistory.of(List.of(
                groceries("Whole Foods", "0.03", "2026-08-04")), SEPTEMBER);

        // 3 cents over two months is 1.5 cents a month.
        assertMoney("0.02", uneven.groups().get(0).average());
    }

    @Test
    void isEmptyWithoutSpending() {
        SpendingHistory empty = SpendingHistory.of(List.of(), SEPTEMBER);

        assertTrue(empty.months().isEmpty());
        assertTrue(empty.groups().isEmpty());
        assertTrue(empty.lineDefaults().isEmpty());
    }

    @Test
    void givesEachPlanLineItsMonthlyAverageInWholeDollarsAndNothingOffALine() {
        assertEquals(Map.of("Groceries", new BigDecimal("210"), "Clothes", new BigDecimal("50")),
                HISTORY.lineDefaults());

        SpendingHistory uneven = SpendingHistory.of(List.of(
                groceries("Whole Foods", "100", "2026-07-04")), SEPTEMBER);
        assertEquals(new BigDecimal("33"), uneven.lineDefaults().get("Groceries"));

        SpendingHistory halfway = SpendingHistory.of(List.of(
                groceries("Whole Foods", "101", "2026-08-04")), SEPTEMBER);
        assertEquals(new BigDecimal("51"), halfway.lineDefaults().get("Groceries"));
    }

    private static Entry groceries(String payee, String amount, String date) {
        return entry("FIXED_COSTS", payee, amount, date, "Groceries", "FOOD_AND_DRINK", "FOOD_AND_DRINK_GROCERIES");
    }

    private static Entry entry(
            String bucket, String payee, String amount, String date, String line, String category, String detailed) {
        return new Entry(bucket, category, detailed, line, payee, LocalDate.parse(date), new BigDecimal(amount));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " but was " + actual);
    }
}
