package dev.matthewsawyer.finance_dashboard.plan;

import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayment;
import dev.matthewsawyer.finance_dashboard.sorting.PlanLines;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a new spending plan starts from, worked out from the payments that count as recurring:
 * take-home pay from recurring pay, the spreadsheet's lines with the recurring bills Jev put on
 * them, and the bills on no line for the user to place or skip.
 *
 * @param takeHome monthly pay from recurring deposits; null when there are none
 * @param lines every bucket's lines, the spreadsheet's first, each bill on Jev's line as an item
 * @param unplaced bills Jev put on no line, or hasn't judged yet, that aren't on the saved plan
 */
public record PlanSuggestions(BigDecimal takeHome, List<Line> lines, List<RecurringPayment> unplaced) {

    /** A suggested line: blank, or the sum of its items. */
    public record Line(SpendingPlanBucket bucket, String name, boolean fromPaycheck, List<Item> items) {
    }

    /** A recurring payment as a breakdown item, at its monthly amount. */
    public record Item(String name, BigDecimal amount, String streamId) {
    }

    /** How many of each payment fall in a month, as a fraction. */
    private static final Map<String, BigDecimal[]> PER_MONTH = Map.of(
            "WEEKLY", fraction(52, 12),
            "BIWEEKLY", fraction(26, 12),
            "SEMI_MONTHLY", fraction(2, 1),
            "MONTHLY", fraction(1, 1),
            "QUARTERLY", fraction(1, 3),
            "SEMI_ANNUALLY", fraction(1, 6),
            "ANNUALLY", fraction(1, 12));

    /** Pay coming in from another of the user's accounts, or a housemate's part of a shared one. */
    private static final String TRANSFER_IN = "TRANSFER_IN";

    /** Card payments: their purchases already count on the card, so they never go on a line. */
    private static final String CARD_PAYMENT = "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT";

    /** Lines that start out taken from the paycheck. */
    private static final Set<String> PAYCHECK_LINES = Set.of("401(k)");

    /** How the web names Plaid's primary categories, for a payment Plaid named nothing. */
    private static final Map<String, String> CATEGORY_NAMES = Map.ofEntries(
            Map.entry("BANK_FEES", "Bank fees"),
            Map.entry("ENTERTAINMENT", "Entertainment"),
            Map.entry("FOOD_AND_DRINK", "Food & drink"),
            Map.entry("GENERAL_MERCHANDISE", "Shopping"),
            Map.entry("GENERAL_SERVICES", "Services"),
            Map.entry("GOVERNMENT_AND_NON_PROFIT", "Government & charity"),
            Map.entry("HOME_IMPROVEMENT", "Home improvement"),
            Map.entry("LOAN_PAYMENTS", "Loan payments"),
            Map.entry("MEDICAL", "Medical"),
            Map.entry("PERSONAL_CARE", "Personal care"),
            Map.entry("RENT_AND_UTILITIES", "Rent & utilities"),
            Map.entry("TRANSPORTATION", "Transportation"),
            Map.entry("TRANSFER_OUT", "Transfers out"),
            Map.entry("TRAVEL", "Travel"),
            Map.entry("UNCATEGORIZED", "Uncategorized"));

    /**
     * @param recurring the payments that count as recurring, in the order they're listed
     * @param onSavedPlan the stream ids already on the saved plan, so they aren't offered again
     */
    public static PlanSuggestions of(List<RecurringPayment> recurring, Set<String> onSavedPlan) {
        BigDecimal takeHome = recurring.stream()
                .filter(payment -> payment.inflow() && !TRANSFER_IN.equals(payment.category()))
                .map(PlanSuggestions::monthlyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<RecurringPayment> unplaced = recurring.stream()
                .filter(payment -> isUnplaced(payment) && !onSavedPlan.contains(payment.streamId()))
                .toList();
        return new PlanSuggestions(takeHome.signum() > 0 ? takeHome : null, lines(recurring), unplaced);
    }

    /**
     * The user's part of a payment, in a month, to the cent. A frequency Plaid has no word for
     * counts once a month.
     */
    public static BigDecimal monthlyAmount(RecurringPayment payment) {
        BigDecimal yours = TrackedAccounts.share(payment.amount(), payment.sharePercent()).abs();
        BigDecimal[] perMonth = payment.frequency() == null ? fraction(1, 1)
                : PER_MONTH.getOrDefault(payment.frequency(), fraction(1, 1));
        return yours.multiply(perMonth[0]).divide(perMonth[1], 2, RoundingMode.HALF_UP);
    }

    /** The payment as a plan item: named for who's paid, at its monthly amount. */
    public static Item item(RecurringPayment payment) {
        return new Item(name(payment), monthlyAmount(payment), payment.streamId());
    }

    /** A bill Jev put on no line, or hasn't judged yet. */
    private static boolean isUnplaced(RecurringPayment payment) {
        return !payment.inflow()
                && !isOnALine(payment)
                && !CARD_PAYMENT.equals(payment.categoryDetailed());
    }

    private static List<Line> lines(List<RecurringPayment> recurring) {
        List<Line> lines = new ArrayList<>();
        addDefaults(lines, SpendingPlanBucket.FIXED_COSTS, PlanLines.DEFAULT.fixedCosts());
        addDefaults(lines, SpendingPlanBucket.INVESTMENTS, PlanLines.DEFAULT.investments());
        addDefaults(lines, SpendingPlanBucket.SAVINGS, PlanLines.DEFAULT.savings());
        for (RecurringPayment payment : recurring) {
            if (payment.inflow() || !isOnALine(payment)) {
                continue;
            }
            Line line = lines.stream()
                    .filter(candidate -> candidate.bucket() == payment.planBucket()
                            && candidate.name().equals(payment.planLine()))
                    .findFirst()
                    .orElseGet(() -> {
                        Line added = new Line(payment.planBucket(), payment.planLine(), false, new ArrayList<>());
                        lines.add(added);
                        return added;
                    });
            line.items().add(item(payment));
        }
        // Fixed costs first, then investments and savings, each bucket's lines in the order added.
        return lines.stream()
                .sorted((a, b) -> a.bucket().compareTo(b.bucket()))
                .map(line -> new Line(line.bucket(), line.name(), line.fromPaycheck(), List.copyOf(line.items())))
                .toList();
    }

    private static void addDefaults(List<Line> lines, SpendingPlanBucket bucket, List<String> names) {
        for (String name : names) {
            boolean fromPaycheck = bucket == SpendingPlanBucket.INVESTMENTS && PAYCHECK_LINES.contains(name);
            lines.add(new Line(bucket, name, fromPaycheck, new ArrayList<>()));
        }
    }

    /** The merchant, else the description, else the category Plaid put it in. */
    private static String name(RecurringPayment payment) {
        if (!isBlank(payment.merchantName())) {
            return payment.merchantName();
        }
        if (!isBlank(payment.description())) {
            return payment.description();
        }
        if (payment.category() == null) {
            return "Recurring";
        }
        // Plaid can add primary categories, so a new one reads as its words.
        return CATEGORY_NAMES.getOrDefault(payment.category(), Arrays.stream(payment.category().split("_"))
                .map(word -> word.isEmpty() ? word
                        : word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" ")));
    }

    private static boolean isOnALine(RecurringPayment payment) {
        return payment.planBucket() != null && !isBlank(payment.planLine());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static BigDecimal[] fraction(int numerator, int denominator) {
        return new BigDecimal[] {BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator)};
    }
}
