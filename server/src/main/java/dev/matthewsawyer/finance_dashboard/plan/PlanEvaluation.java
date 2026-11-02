package dev.matthewsawyer.finance_dashboard.plan;

import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanItem;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a spending plan splits its income, and which parts of it need attention. The one place
 * these rules live; the web and iOS apps show what this works out.
 */
public record PlanEvaluation(
        /** Take-home pay plus paycheck contributions; null until take-home pay is known. */
        BigDecimal planIncome,
        /** Investments taken from the paycheck, added back onto take-home pay. */
        BigDecimal fromPaycheck,
        /** Fixed cost lines before the buffer. */
        BigDecimal fixedCostSubtotal,
        BigDecimal buffer,
        /** The three buckets that hold lines. Fixed costs include the buffer. */
        Map<SpendingPlanBucket, Share> buckets,
        /** The fourth bucket: what the other three leave, so it holds no lines. */
        Share guiltFree
) {

    /** Suggested share of plan income, in percent. */
    public record Target(int min, int max) {
    }

    public static final Map<SpendingPlanBucket, Target> BUCKET_TARGETS = Map.of(
            SpendingPlanBucket.FIXED_COSTS, new Target(50, 60),
            SpendingPlanBucket.INVESTMENTS, new Target(10, 10),
            SpendingPlanBucket.SAVINGS, new Target(5, 10));

    public static final Target GUILT_FREE_TARGET = new Target(20, 35);

    /** Where a share falls against its target. */
    public enum Status { BELOW, WITHIN, ABOVE }

    /**
     * A bucket's amount and how it measures against plan income.
     *
     * @param amount  for guilt-free spending, negative when the plan spends more than plan
     *                income, and null until take-home pay is known
     * @param share   percent of plan income, rounded; null until plan income is known. Unbounded,
     *                since a tiny take-home pay against large lines gives a huge percent
     * @param status  null until plan income is known
     * @param flagged needs the user's attention: fixed costs above target, or guilt-free spending below zero
     */
    public record Share(BigDecimal amount, BigInteger share, Target target, Status status, boolean flagged) {
    }

    /** A blank buffer percent means no buffer. */
    public static PlanEvaluation of(BigDecimal takeHome, BigDecimal bufferPercent, List<SpendingPlanLine> lines) {
        BigDecimal fixedCostSubtotal = total(lines, SpendingPlanBucket.FIXED_COSTS);
        BigDecimal percent = Objects.requireNonNullElse(bufferPercent, BigDecimal.ZERO);
        BigDecimal buffer = roundCents(fixedCostSubtotal.multiply(percent).movePointLeft(2));
        BigDecimal fromPaycheck = total(
                lines.stream().filter(SpendingPlanLine::isFromPaycheck).toList(), SpendingPlanBucket.INVESTMENTS);
        BigDecimal income = takeHome == null ? null : roundCents(takeHome.add(fromPaycheck));

        Map<SpendingPlanBucket, BigDecimal> amounts = new EnumMap<>(SpendingPlanBucket.class);
        for (SpendingPlanBucket bucket : SpendingPlanBucket.values()) {
            amounts.put(bucket, total(lines, bucket));
        }
        amounts.put(SpendingPlanBucket.FIXED_COSTS, roundCents(fixedCostSubtotal.add(buffer)));
        BigDecimal planned = amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal guiltFree = income == null ? null : roundCents(income.subtract(planned));

        Map<SpendingPlanBucket, Share> buckets = new EnumMap<>(SpendingPlanBucket.class);
        amounts.forEach((bucket, amount) -> {
            Target target = BUCKET_TARGETS.get(bucket);
            BigInteger share = share(amount, income);
            Status status = status(share, target);
            boolean flagged = bucket == SpendingPlanBucket.FIXED_COSTS && status == Status.ABOVE;
            buckets.put(bucket, new Share(amount, share, target, status, flagged));
        });
        BigInteger guiltFreePercent = share(guiltFree, income);
        Share guiltFreeBucket = new Share(
                guiltFree,
                guiltFreePercent,
                GUILT_FREE_TARGET,
                status(guiltFreePercent, GUILT_FREE_TARGET),
                guiltFree != null && guiltFree.signum() < 0);

        return new PlanEvaluation(income, fromPaycheck, fixedCostSubtotal, buffer, buckets, guiltFreeBucket);
    }

    /**
     * A line broken into items is their sum to the cent, with blank items as zero; otherwise its
     * typed amount.
     */
    public static BigDecimal lineAmount(SpendingPlanLine line) {
        if (line.getItems().isEmpty()) {
            return line.getAmount();
        }
        return roundCents(line.getItems().stream()
                .map(SpendingPlanItem::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Totals are kept to the cent, so the buckets and guilt-free spending add up when shown. */
    private static BigDecimal total(List<SpendingPlanLine> lines, SpendingPlanBucket bucket) {
        return roundCents(lines.stream()
                .filter(line -> line.getBucket() == bucket)
                .map(PlanEvaluation::lineAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigInteger share(BigDecimal amount, BigDecimal income) {
        if (amount == null || income == null || income.signum() <= 0) {
            return null;
        }
        BigDecimal percent = amount.movePointRight(2).divide(income, MathContext.DECIMAL64);
        return roundHalfUp(percent, 0).toBigIntegerExact();
    }

    private static Status status(BigInteger share, Target target) {
        if (share == null) {
            return null;
        }
        if (share.compareTo(BigInteger.valueOf(target.min())) < 0) {
            return Status.BELOW;
        }
        return share.compareTo(BigInteger.valueOf(target.max())) > 0 ? Status.ABOVE : Status.WITHIN;
    }

    private static BigDecimal roundCents(BigDecimal value) {
        return roundHalfUp(value, 2);
    }

    /**
     * Rounds halves toward positive infinity, like JavaScript's Math.round, so -12.5 becomes -12.
     * The web app rounded this way before the math moved here, though on floating-point values.
     */
    static BigDecimal roundHalfUp(BigDecimal value, int scale) {
        BigDecimal half = BigDecimal.ONE.movePointLeft(scale).divide(BigDecimal.TWO);
        return value.add(half).setScale(scale, RoundingMode.FLOOR);
    }
}
