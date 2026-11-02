package dev.matthewsawyer.finance_dashboard.plan;

import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.payees.PayeeLookup;
import dev.matthewsawyer.finance_dashboard.spending.Spending;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the user spent in each of the last few whole months, with the average per month: by
 * bucket, then by the plan line Jev put each payee on, then by payee. Spending at payees on no
 * line is listed by Plaid category, and guilt-free spending and what isn't sorted yet as one
 * total each. Plan setup shows it beside the plan, and a blank line's default amount is its
 * average.
 *
 * @param months the months averages are taken over, oldest first. They start at the first month
 *               with any spending, so an account linked recently isn't averaged over months it
 *               has no transactions for.
 * @param groups each bucket's spending, in the plan's order, with what isn't sorted yet last
 */
public record SpendingHistory(List<YearMonth> months, List<Group> groups) {

    /** One transaction's spending, the user's part of it. */
    public record Entry(
            /** The bucket's name, or {@link Spending#UNSORTED}. */
            String bucket,
            /** The Plaid primary category shown, the user's if they corrected it; may be null. */
            String category,
            /** Plaid's detailed category; null when the user corrected the category. */
            String categoryDetailed,
            /** The plan line Jev put the payee on, within the transaction's bucket; null for none. */
            String line,
            String payee,
            LocalDate date,
            BigDecimal amount
    ) {

        public static Entry of(Spending.Spent spent, PayeeLookup.Payees payees) {
            PlaidTransaction transaction = spent.transaction();
            var correction = payees.correction(transaction);
            boolean categoryCorrected = correction != null && correction.getCategory() != null;
            RecurringPayee line = payees.line(transaction);
            return new Entry(
                    Spending.bucketKey(transaction),
                    payees.category(transaction),
                    categoryCorrected ? null : transaction.getPersonalFinanceCategoryDetailed(),
                    line == null ? null : line.getPlanLine(),
                    payeeName(transaction),
                    transaction.getTransactionDate(),
                    spent.amount());
        }
    }

    /** A bucket's spending: its plan lines first, then the categories on no line, each largest first. */
    public record Group(String bucket, BigDecimal average, List<Category> categories) {
    }

    /** What a bucket's spending is listed by. */
    public enum Kind {
        /** A plan line, for payees Jev put on one. */
        LINE,
        /** A Plaid category, for spending on no line. */
        CATEGORY,
        /** All of a bucket's spending, for guilt-free spending and what isn't sorted yet. */
        ALL
    }

    /**
     * @param line the plan line's name, for {@link Kind#LINE}
     * @param category the Plaid primary category, for {@link Kind#CATEGORY}
     * @param categoryDetailed Plaid's detailed category within it, when there is one
     * @param payees largest first
     */
    public record Category(
            String key, Kind kind, String line, String category, String categoryDetailed, BigDecimal average,
            List<Payee> payees) {
    }

    /** {@code key} is unique across the history: a payee under two categories is listed under each. */
    public record Payee(String key, String name, BigDecimal average) {
    }

    /** The order buckets are listed in, following the plan, with what isn't sorted yet last. */
    private static final List<String> BUCKET_ORDER = List.of(
            Bucket.FIXED_COSTS.name(), Bucket.INVESTMENTS.name(), Bucket.SAVINGS.name(), Bucket.GUILT_FREE.name(),
            Spending.UNSORTED);

    /**
     * Buckets listed as one total. Guilt-free spending is what's left after the plan's lines, so
     * what it went on doesn't help choose an amount; neither does spending that isn't sorted yet.
     */
    private static final List<String> UNDIVIDED = List.of(Bucket.GUILT_FREE.name(), Spending.UNSORTED);

    /**
     * @param spent the spending over whole months, ending with {@code lastMonth}
     */
    public static SpendingHistory of(List<Entry> spent, YearMonth lastMonth) {
        List<Entry> counted = countedLikeTheSpendingChart(spent);
        if (counted.isEmpty()) {
            return new SpendingHistory(List.of(), List.of());
        }
        YearMonth first = counted.stream()
                .map(entry -> YearMonth.from(entry.date()))
                .min(Comparator.naturalOrder())
                .orElseThrow();
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(lastMonth); month = month.plusMonths(1)) {
            months.add(month);
        }

        // Category key → its first entry, for what it's listed by, and each payee's total.
        Map<String, Map<String, Listing>> byBucket = new HashMap<>();
        for (Entry entry : counted) {
            String key = categoryKey(entry);
            byBucket.computeIfAbsent(entry.bucket(), bucket -> new LinkedHashMap<>())
                    .computeIfAbsent(key, k -> new Listing(entry, new LinkedHashMap<>()))
                    .payees().merge(entry.payee(), entry.amount(), BigDecimal::add);
        }

        int monthCount = months.size();
        List<Group> groups = BUCKET_ORDER.stream()
                .filter(byBucket::containsKey)
                .map(bucket -> {
                    List<Category> categories = byBucket.get(bucket).entrySet().stream()
                            .map(listing -> listing.getValue().category(listing.getKey(), monthCount))
                            .filter(category -> category.average().signum() > 0)
                            .sorted(Comparator.comparing((Category category) -> category.kind() != Kind.LINE)
                                    .thenComparing(Category::average, Comparator.reverseOrder()))
                            .toList();
                    BigDecimal average = categories.stream()
                            .map(Category::average)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new Group(bucket, average, categories);
                })
                .filter(group -> !group.categories().isEmpty())
                .toList();
        return new SpendingHistory(List.copyOf(months), groups);
    }

    /**
     * What the user spends in a month on each plan line, by the line's name, in whole dollars:
     * an average isn't exact to the cent. Lines with no spending are left out.
     */
    public Map<String, BigDecimal> lineDefaults() {
        Map<String, BigDecimal> averages = new LinkedHashMap<>();
        groups.stream()
                .flatMap(group -> group.categories().stream())
                .filter(category -> category.kind() == Kind.LINE)
                .forEach(category -> averages.merge(category.line(), category.average(), BigDecimal::add));
        Map<String, BigDecimal> defaults = new LinkedHashMap<>();
        averages.forEach((line, average) -> {
            BigDecimal dollars = PlanEvaluation.roundHalfUp(average, 0);
            if (dollars.signum() > 0) {
                defaults.put(line, dollars);
            }
        });
        return defaults;
    }

    /** Who was paid: the merchant, or the description when Plaid found no merchant. */
    static String payeeName(PlaidTransaction transaction) {
        for (String name : new String[] {transaction.getMerchantName(), transaction.getName()}) {
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return "Transaction";
    }

    /**
     * The spending the spending chart counts: a bucket's Plaid category that refunds net to
     * nothing or below is left out, as a chart can't show it.
     */
    private static List<Entry> countedLikeTheSpendingChart(List<Entry> spent) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (Entry entry : spent) {
            totals.merge(chartKey(entry), entry.amount(), BigDecimal::add);
        }
        return spent.stream()
                .filter(entry -> BUCKET_ORDER.contains(entry.bucket()))
                .filter(entry -> Spending.countsTowardBucket(totals.get(chartKey(entry))))
                .toList();
    }

    private static String chartKey(Entry entry) {
        return entry.bucket() + "/" + Spending.categoryKey(entry.category());
    }

    private static String categoryKey(Entry entry) {
        if (UNDIVIDED.contains(entry.bucket())) {
            return entry.bucket() + "/all";
        }
        if (entry.line() != null) {
            return entry.bucket() + "/line/" + entry.line();
        }
        return entry.bucket() + "/category/" + Spending.categoryKey(entry.category()) + "/"
                + Objects.requireNonNullElse(entry.categoryDetailed(), "");
    }

    /** A category's spending so far: the entry that says what it's listed by, and payee totals. */
    private record Listing(Entry first, Map<String, BigDecimal> payees) {

        Category category(String key, int months) {
            List<Payee> listed = payees.entrySet().stream()
                    .map(payee -> new Payee(key + "|" + payee.getKey(), payee.getKey(),
                            perMonth(payee.getValue(), months)))
                    .filter(payee -> payee.average().signum() > 0)
                    .sorted(Comparator.comparing(Payee::average, Comparator.reverseOrder()))
                    .toList();
            BigDecimal total = payees.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            Kind kind = UNDIVIDED.contains(first.bucket()) ? Kind.ALL
                    : first.line() != null ? Kind.LINE : Kind.CATEGORY;
            return new Category(
                    key,
                    kind,
                    kind == Kind.LINE ? first.line() : null,
                    kind == Kind.CATEGORY ? Spending.categoryKey(first.category()) : null,
                    kind == Kind.CATEGORY ? first.categoryDetailed() : null,
                    perMonth(total, months),
                    listed);
        }

        private static BigDecimal perMonth(BigDecimal total, int months) {
            // Enough places that rounding to the cent afterwards rounds the exact value.
            return PlanEvaluation.roundHalfUp(total.divide(BigDecimal.valueOf(months), 10, RoundingMode.DOWN), 2);
        }
    }
}
