package dev.matthewsawyer.finance_dashboard.plan;

import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanItem;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket.FIXED_COSTS;
import static dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket.INVESTMENTS;
import static dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket.SAVINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same cases and numbers the web's plan tests used before the math moved here. */
class PlanEvaluationTests {

    @Test
    void bucketTotalsAddTypedAmountsAndBrokenDownLinesSkippingBlanks() {
        PlanEvaluation evaluation = PlanEvaluation.of(null, null, List.of(
                line(INVESTMENTS, "500"),
                line(INVESTMENTS, null),
                lineWithItems(INVESTMENTS, false, "100.1", "50.2")));

        assertMoney("650.3", evaluation.buckets().get(INVESTMENTS).amount());
    }

    @Test
    void addsUpOnlyTheLinesTakenFromThePaycheck() {
        PlanEvaluation evaluation = PlanEvaluation.of(money("5200"), null, List.of(
                paycheckLine("500"),
                lineWithItems(INVESTMENTS, true, "100"),
                line(INVESTMENTS, "583")));

        assertMoney("600", evaluation.fromPaycheck());
    }

    @Test
    void addsPaycheckContributionsBackOntoTakeHomePayForPlanIncome() {
        assertMoney("5700", PlanEvaluation.of(money("5200"), null, List.of(paycheckLine("500"))).income());
        assertMoney("5200", PlanEvaluation.of(money("5200"), null, List.of()).income());
        assertNull(PlanEvaluation.of(null, null, List.of(paycheckLine("500"))).income());
    }

    @Test
    void countsAPaycheck401kTowardInvestmentsWithoutShrinkingGuiltFreeSpending() {
        PlanEvaluation evaluation = PlanEvaluation.of(money("5200"), money("0"), List.of(
                line(FIXED_COSTS, "2800"),
                paycheckLine("500"),
                line(SAVINGS, "300")));

        assertMoney("2100", evaluation.guiltFree().amount());
        assertEquals(9, evaluation.buckets().get(INVESTMENTS).share());
    }

    @Test
    void bufferIsAPercentOfFixedCostsRoundedToCentsAndNothingWhenBlank() {
        List<SpendingPlanLine> fixedCosts = List.of(line(FIXED_COSTS, "1477.48"));

        assertMoney("221.62", PlanEvaluation.of(null, money("15"), fixedCosts).buffer());
        assertMoney("0", PlanEvaluation.of(null, money("0"), fixedCosts).buffer());
        assertMoney("0", PlanEvaluation.of(null, null, fixedCosts).buffer());
    }

    @Test
    void bufferCountsTowardFixedCostsAndSoComesOutOfGuiltFreeSpending() {
        PlanEvaluation evaluation = PlanEvaluation.of(money("5000"), money("15"), List.of(
                line(FIXED_COSTS, "2000"), line(FIXED_COSTS, null)));

        assertMoney("2000", evaluation.fixedCostSubtotal());
        assertMoney("300", evaluation.buffer());
        assertMoney("2300", evaluation.buckets().get(FIXED_COSTS).amount());
        assertMoney("2700", evaluation.guiltFree().amount());
    }

    @Test
    void guiltFreeSpendingIsWhatPlanIncomeLeavesAfterTheBuckets() {
        assertMoney("1099.5", simplePlan("5000", "2900.5", "500", "500").guiltFree().amount());
        assertMoney("-400", simplePlan("3000", "2400", "500", "500").guiltFree().amount());
    }

    @Test
    void guiltFreeSpendingIsUnknownUntilTakeHomePayIsEntered() {
        PlanEvaluation.GuiltFreeEvaluation guiltFree = simplePlan(null, "1200", "0", "0").guiltFree();

        assertNull(guiltFree.amount());
        assertNull(guiltFree.share());
        assertNull(guiltFree.status());
        assertFalse(guiltFree.flagged());
    }

    @Test
    void measuresEachPartAsARoundedPercentOfPlanIncome() {
        PlanEvaluation evaluation = simplePlan("3600", "1800", "360", "180");

        assertEquals(50, evaluation.buckets().get(FIXED_COSTS).share());
        assertEquals(10, evaluation.buckets().get(INVESTMENTS).share());
        assertEquals(5, evaluation.buckets().get(SAVINGS).share());
        assertEquals(35, evaluation.guiltFree().share());
    }

    @Test
    void hasNoShareUntilThereIsPlanIncomeToMeasureAgainst() {
        assertNull(simplePlan(null, "1800", "0", "0").buckets().get(FIXED_COSTS).share());
        assertNull(simplePlan("0", "1800", "0", "0").buckets().get(FIXED_COSTS).share());
    }

    @Test
    void placesEachShareBelowWithinOrAboveItsTarget() {
        PlanEvaluation evaluation = simplePlan("1000", "700", "100", "20");

        assertEquals(new PlanEvaluation.Target(50, 60), evaluation.buckets().get(FIXED_COSTS).target());
        assertEquals(PlanEvaluation.Status.ABOVE, evaluation.buckets().get(FIXED_COSTS).status());
        assertEquals(PlanEvaluation.Status.WITHIN, evaluation.buckets().get(INVESTMENTS).status());
        assertEquals(PlanEvaluation.Status.BELOW, evaluation.buckets().get(SAVINGS).status());
        assertEquals(PlanEvaluation.Status.BELOW, evaluation.guiltFree().status());
        assertEquals(new PlanEvaluation.Target(20, 35), evaluation.guiltFree().target());
    }

    @Test
    void flagsFixedCostsAboveTheirTarget() {
        assertTrue(simplePlan("1000", "610", "100", "50").buckets().get(FIXED_COSTS).flagged());
        assertFalse(simplePlan("1000", "600", "100", "50").buckets().get(FIXED_COSTS).flagged());
    }

    @Test
    void flagsGuiltFreeSpendingBelowZero() {
        assertTrue(simplePlan("1000", "900", "100", "50").guiltFree().flagged());
        assertFalse(simplePlan("1000", "900", "100", "0").guiltFree().flagged());
    }

    @Test
    void doesNotFlagOtherPartsOutsideTheirTargets() {
        PlanEvaluation evaluation = simplePlan("1000", "300", "300", "0");

        assertEquals(PlanEvaluation.Status.ABOVE, evaluation.buckets().get(INVESTMENTS).status());
        assertFalse(evaluation.buckets().get(INVESTMENTS).flagged());
        assertEquals(PlanEvaluation.Status.BELOW, evaluation.buckets().get(SAVINGS).status());
        assertFalse(evaluation.buckets().get(SAVINGS).flagged());
        assertEquals(PlanEvaluation.Status.ABOVE, evaluation.guiltFree().status());
        assertFalse(evaluation.guiltFree().flagged());
    }

    @Test
    void roundsHalfASharePointUpTowardPositiveLikeTheWebDid() {
        // 125 of 1,000 is 12.5%; -125 is -12.5%, which the web's Math.round made -12.
        assertEquals(13, simplePlan("1000", "775", "50", "50").guiltFree().share());
        assertEquals(-12, simplePlan("1000", "1025", "50", "50").guiltFree().share());
    }

    @Test
    void roundsAHalfCentBufferUp() {
        // 15% of 1.50 is 0.225.
        assertMoney("0.23", PlanEvaluation.of(null, money("15"), List.of(line(FIXED_COSTS, "1.5"))).buffer());
    }

    /** One typed amount per bucket and no buffer, for share and target checks. */
    static PlanEvaluation simplePlan(String takeHome, String fixedCosts, String investments, String savings) {
        return PlanEvaluation.of(money(takeHome), money("0"), List.of(
                line(FIXED_COSTS, fixedCosts), line(INVESTMENTS, investments), line(SAVINGS, savings)));
    }

    static SpendingPlanLine line(SpendingPlanBucket bucket, String amount) {
        return new SpendingPlanLine(bucket, "Line", money(amount), false, null, List.of());
    }

    static SpendingPlanLine paycheckLine(String amount) {
        return new SpendingPlanLine(INVESTMENTS, "401(k)", money(amount), true, null, List.of());
    }

    static SpendingPlanLine lineWithItems(SpendingPlanBucket bucket, boolean fromPaycheck, String... amounts) {
        List<SpendingPlanItem> items = Arrays.stream(amounts)
                .map(amount -> new SpendingPlanItem("Item", money(amount), null))
                .toList();
        // A line with items is their sum; its own amount is ignored.
        return new SpendingPlanLine(bucket, "Line", new BigDecimal("99"), fromPaycheck, null, items);
    }

    static BigDecimal money(String amount) {
        return amount == null ? null : new BigDecimal(amount);
    }

    static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " but was " + actual);
    }
}
