package dev.matthewsawyer.finance_dashboard.sorting;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PlaidCategory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import static dev.matthewsawyer.finance_dashboard.sorting.TypeSafeClient.ordered;

/**
 * Decides which bucket transactions belong to, using the user's plan lines to settle
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

    /**
     * Transactions asked about in one request. The eval matched one-at-a-time answers with up to
     * 80; 50 keeps a request well under the token limit.
     */
    static final int PER_REQUEST = 50;

    private static final String QUESTION =
            "Which part of the user's Conscious Spending Plan does `transaction` belong to?";

    private static final String GUIDANCE =
            "When `transaction` matches a line in `spending_plan`, choose the part that line is in.";

    private final TypeSafeClient typeSafe;

    BucketClassifier(TypeSafeClient typeSafe) {
        this.typeSafe = typeSafe;
    }

    boolean isAvailable() {
        return typeSafe.isConfigured();
    }

    /**
     * The bucket each transaction belongs in, in the same order, from one request. Each question
     * carries its own transaction and the plan, and the state is empty: the eval found answers
     * drift when questions share a state.
     */
    List<Bucket> classify(List<PlaidTransaction> transactions, PlanLines plan) {
        PlanState planState = plan.isEmpty() ? null : PlanState.of(plan);
        Map<String, Map<String, Object>> questions = new LinkedHashMap<>();
        for (int i = 0; i < transactions.size(); i++) {
            questions.put(questionId(i), question(TransactionState.of(transactions.get(i)), planState));
        }
        TypeSafeClient.Answers answers = typeSafe.ask(Map.of(), questions);
        return IntStream.range(0, transactions.size())
                .mapToObj(i -> Bucket.valueOf(answers.choice(questionId(i)).toUpperCase(Locale.ROOT)))
                .toList();
    }

    private static String questionId(int index) {
        return "t" + index;
    }

    private static Map<String, Object> question(TransactionState transaction, PlanState plan) {
        Object instructions = plan == null
                ? ordered("transaction", transaction, "question", QUESTION)
                : ordered("transaction", transaction, "spending_plan", plan, "question", QUESTION, "guidance", GUIDANCE);
        return ordered("type", "choice", "instructions", instructions, "criteria", CRITERIA);
    }

    record TransactionState(
            @JsonProperty("description") String description,
            @JsonProperty("merchant") String merchant,
            @JsonProperty("amount_usd") BigDecimal amountUsd,
            @JsonProperty("direction") String direction,
            @JsonProperty("plaid_category") String plaidCategory,
            @JsonProperty("payment_channel") String paymentChannel
    ) {
        // The model reads amounts better without a sign, so direction says which way money moved.
        static TransactionState of(PlaidTransaction transaction) {
            BigDecimal amount = transaction.getAmount();
            String direction = amount.signum() >= 0 ? "money out" : "refund";
            return new TransactionState(
                    transaction.getName(),
                    transaction.getMerchantName(),
                    amount.abs().setScale(2, RoundingMode.HALF_UP),
                    direction,
                    PlaidCategory.readable(
                            transaction.getPersonalFinanceCategoryPrimary(),
                            transaction.getPersonalFinanceCategoryDetailed()),
                    transaction.getPaymentChannel());
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

}
