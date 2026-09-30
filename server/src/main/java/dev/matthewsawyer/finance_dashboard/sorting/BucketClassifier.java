package dev.matthewsawyer.finance_dashboard.sorting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which bucket one transaction belongs to, using the user's plan lines to settle
 * cases that depend on the person, like whether a streaming subscription is a fixed cost.
 */
@Component
class BucketClassifier {

    // Option keys are what the model reads, so they're lower-case words rather than enum names.
    private static final Map<String, Object> CRITERIA = ordered(
            "fixed_costs", ordered(
                    "what", "Bills and necessities the user must pay to live: rent or mortgage, utilities, "
                            + "phone, internet, insurance, loan and car payments, groceries, commuting, "
                            + "basic clothing and committed subscriptions."),
            "guilt_free", ordered(
                    "what", "Optional spending the user chooses to enjoy: eating out, coffee, bars, "
                            + "entertainment, travel, hobbies, gifts, donations and non-essential shopping.",
                    "includes", "Cash withdrawals and payments sent to other people, since that money gets spent."),
            "savings", ordered(
                    "what", "Money moved into a savings account and set aside for a future goal, such as "
                            + "an emergency fund, a vacation or a house down payment.",
                    "not_for", "Purchases themselves, even ones a savings goal will pay for, like a hotel booking."),
            "investments", ordered(
                    "what", "Money moved into an investment or retirement account, such as a brokerage "
                            + "account, 401(k) or IRA."),
            "not_counted", ordered(
                    "what", "Money moving between the user's own everyday accounts, such as checking to "
                            + "checking, without being spent, saved or invested.",
                    "not_for", "Cash withdrawals or payments to other people."));

    private static final String BUCKET_QUESTION_ID = "bucket";

    private static final String QUESTION =
            "Which part of the user's Conscious Spending Plan does `transaction` belong to?";

    private static final Map<String, Object> WITH_PLAN = ordered(
            "type", "choice",
            "instructions", ordered(
                    "question", QUESTION,
                    "guidance", "When `transaction` matches a line in `spending_plan`, choose the part that line is in."),
            "criteria", CRITERIA);

    private static final Map<String, Object> WITHOUT_PLAN = ordered(
            "type", "choice",
            "instructions", QUESTION,
            "criteria", CRITERIA);

    private final TypeSafeClient typeSafe;

    BucketClassifier(TypeSafeClient typeSafe) {
        this.typeSafe = typeSafe;
    }

    boolean isAvailable() {
        return typeSafe.isConfigured();
    }

    /**
     * Sorts {@code transaction}. Given the payee's other charges, the same request also asks
     * whether it repeats, since the questions run in parallel and can't see each other's answers.
     *
     * @param otherCharges the user's other charges from the same payee, or null not to ask
     *     whether it repeats
     */
    Sorted classify(PlaidTransaction transaction, PlanLines plan, List<PlaidTransaction> otherCharges) {
        boolean judgeRecurring = otherCharges != null;
        Map<String, Map<String, Object>> questions = new LinkedHashMap<>();
        questions.put(BUCKET_QUESTION_ID, plan.isEmpty() ? WITHOUT_PLAN : WITH_PLAN);
        if (judgeRecurring) {
            questions.putAll(RecurringJudge.questions(transaction));
        }
        State state = new State(
                TransactionState.of(transaction, judgeRecurring),
                plan.isEmpty() ? null : PlanState.of(plan),
                judgeRecurring ? Charge.all(otherCharges) : null);

        TypeSafeClient.Answers answers = typeSafe.ask(state, questions);
        return new Sorted(
                Bucket.valueOf(answers.choice(BUCKET_QUESTION_ID).toUpperCase(Locale.ROOT)),
                judgeRecurring ? RecurringJudge.read(answers) : null);
    }

    /** A transaction's bucket, and whether it repeats when that was asked too. */
    record Sorted(Bucket bucket, RecurringJudge.RecurringJudgment recurring) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record State(
            @JsonProperty("transaction") TransactionState transaction,
            @JsonProperty("spending_plan") PlanState spendingPlan,
            @JsonProperty("other_charges_from_same_payee") List<Charge> otherChargesFromSamePayee
    ) {
    }

    /**
     * Only asking whether a transaction repeats needs its date, to compare with the payee's other
     * charges; sorting's state stays as it was evaluated.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TransactionState(
            @JsonProperty("date") String date,
            @JsonProperty("description") String description,
            @JsonProperty("merchant") String merchant,
            @JsonProperty("amount_usd") BigDecimal amountUsd,
            @JsonProperty("direction") String direction,
            @JsonProperty("plaid_category") String plaidCategory,
            @JsonProperty("payment_channel") String paymentChannel
    ) {
        // The model reads amounts better without a sign, so direction says which way money moved.
        static TransactionState of(PlaidTransaction transaction, boolean withDate) {
            BigDecimal amount = transaction.getAmount();
            String direction = amount.signum() >= 0 ? "money out"
                    : RecurringJudge.isPay(transaction) ? "money in" : "refund";
            return new TransactionState(
                    withDate ? transaction.getTransactionDate().toString() : null,
                    transaction.getName(),
                    transaction.getMerchantName(),
                    amount.abs().setScale(2, RoundingMode.HALF_UP),
                    direction,
                    readableCategory(
                            transaction.getPersonalFinanceCategoryPrimary(),
                            transaction.getPersonalFinanceCategoryDetailed()),
                    transaction.getPaymentChannel());
        }
    }

    /**
     * One of the payee's other charges. The same amount at a steady interval is what gives a bill
     * away when its name and category don't, such as rent paid to a property manager.
     */
    record Charge(
            @JsonProperty("date") String date,
            @JsonProperty("amount_usd") BigDecimal amountUsd
    ) {
        static List<Charge> all(List<PlaidTransaction> charges) {
            return charges.stream()
                    .map(charge -> new Charge(
                            charge.getTransactionDate().toString(),
                            charge.getAmount().abs().setScale(2, RoundingMode.HALF_UP)))
                    .toList();
        }
    }

    record PlanState(
            @JsonProperty("fixed_costs") Lines fixedCosts,
            @JsonProperty("investments") Lines investments,
            @JsonProperty("savings") Lines savings
    ) {
        static PlanState of(PlanLines plan) {
            return new PlanState(
                    new Lines(plan.fixedCosts()), new Lines(plan.investments()), new Lines(plan.savings()));
        }
    }

    record Lines(@JsonProperty("lines") List<String> lines) {
    }

    /** Turns FOOD_AND_DRINK / FOOD_AND_DRINK_COFFEE into "food and drink: coffee". */
    static String readableCategory(String primary, String detailed) {
        if (detailed == null) {
            return primary == null ? null : words(primary);
        }
        if (primary == null || !detailed.startsWith(primary + "_")) {
            return words(detailed);
        }
        return words(primary) + ": " + words(detailed.substring(primary.length() + 1));
    }

    private static String words(String category) {
        return category.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
