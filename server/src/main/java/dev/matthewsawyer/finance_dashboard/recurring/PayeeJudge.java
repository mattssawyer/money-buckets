package dev.matthewsawyer.finance_dashboard.recurring;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.model.PlaidCategory;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.sorting.PlanLines;
import dev.matthewsawyer.finance_dashboard.sorting.TypeSafeClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import static dev.matthewsawyer.finance_dashboard.sorting.TypeSafeClient.ordered;

/**
 * Asks Jev about payees, each from all of its charges at once: whether the user pays it (or is
 * paid by it) regularly, how often, and which plan line a bill belongs under. Several payees go
 * in one request. The questions are the ones evals/recurring_payees.py measures; change them
 * there first.
 */
@Component
class PayeeJudge {

    static final String REPEATS = "repeats";
    static final String USUAL_FREQUENCY = "usual_frequency";
    static final String LINE = "line";

    private static final String NO_LINE = "none";

    /**
     * Payees asked about in one request. A payee with a full year of charges and all three
     * questions is about 2,000 tokens, so 15 stay well under the request limit.
     */
    static final int PER_REQUEST = 15;

    private static final Map<String, Object> BILL = ordered(
            "type", "noul",
            "instructions", ordered(
                    "question", "Does the user pay `payee` for a bill or subscription on a regular schedule?",
                    "guidance", "`charges` lists the user's charges from this payee, newest first. A well-known "
                            + "subscription or bill is recurring even from a single charge. Otherwise, the same or "
                            + "a similar amount at a steady interval suggests a bill, while charges of varying "
                            + "amounts at irregular times suggest shopping."),
            "criteria", ordered(
                    "true", "A bill or subscription charged on a schedule: rent, utilities, phone, internet, "
                            + "insurance, loan payments, streaming, software, memberships or gym fees.",
                    "false", "One-off or occasional purchases, even from a store the user visits often: "
                            + "shopping, groceries, eating out, travel, cash withdrawals or payments to a person."));

    private static final Map<String, Object> PAYCHECK = ordered(
            "type", "noul",
            "instructions", ordered(
                    "question", "Does `payee` pay the user wages from a job on a regular schedule?",
                    "guidance", "`deposits` lists the money the user received from this payee, newest first. "
                            + "Payroll from an employer is pay even from a single deposit."),
            "criteria", ordered(
                    "true", "Salary or wages from an employer, paid by payroll or direct deposit.",
                    "false", "Other money coming in: refunds, interest, tax refunds, reimbursements, money from "
                            + "other people or one-off payments."));

    // Option keys are what the model reads; they match RecurringFrequency once upper-cased.
    private static final Map<String, Object> FREQUENCY = ordered(
            "type", "choice",
            "instructions", "If payments like these repeat, how often are they usually made?",
            "criteria", ordered(
                    "weekly", "Every week",
                    "biweekly", "Every two weeks",
                    "semi_monthly", "Twice a month, such as on the 1st and the 15th",
                    "monthly", "Once a month",
                    "quarterly", "Every three months",
                    "semi_annually", "Twice a year",
                    "annually", "Once a year"));

    private static final Object LINE_INSTRUCTIONS = ordered(
            "question", "If the user pays `payee` regularly, which line of their Conscious Spending Plan do "
                    + "those payments belong under?",
            "guidance", "Judge from who is paid, how much and how often; `plaid_category` is the bank's "
                    + "automatic guess and is often wrong for bills paid by bank transfer. A large monthly "
                    + "payment to a company that is not clearly something else is usually rent paid to a "
                    + "landlord or property manager.");

    // Everyday spending gets a line too when one names it, since plan setup shows what the user
    // spends on each line: a grocery store goes on Groceries without being a bill.
    private static final String NO_LINE_CRITERION = "Fits none of the lines: money moved between the user's own "
            + "checking accounts, credit card payments, payments to friends or family, or spending no line "
            + "names, such as eating out or general shopping.";

    // What each bucket holds, so lines with bare names still say what goes in them.
    private static final Map<SpendingPlanBucket, String> BUCKET_WORDS = Map.of(
            SpendingPlanBucket.FIXED_COSTS, "fixed costs",
            SpendingPlanBucket.INVESTMENTS, "investments",
            SpendingPlanBucket.SAVINGS, "savings");
    private static final Map<SpendingPlanBucket, String> BUCKET_MEANINGS = Map.of(
            SpendingPlanBucket.FIXED_COSTS, "bills and necessities",
            SpendingPlanBucket.INVESTMENTS, "money moved into an investment or retirement account",
            SpendingPlanBucket.SAVINGS, "money moved into a savings account for a goal");

    private final TypeSafeClient typeSafe;
    private final ObjectMapper objectMapper;

    PayeeJudge(TypeSafeClient typeSafe, ObjectMapper objectMapper) {
        this.typeSafe = typeSafe;
        this.objectMapper = objectMapper;
    }

    boolean isAvailable() {
        return typeSafe.isConfigured();
    }

    /**
     * What to ask about a payee, or null when there's nothing to ask: Plaid already detects its
     * pay. Whether it repeats isn't asked while Plaid detects it; a bill's line always is.
     *
     * @param charges the payee's charges, newest first
     */
    Request request(RecurringMerchant payee, List<PlaidTransaction> charges, boolean detectedByPlaid, PlanLines plan) {
        boolean pay = payee.kind() == RecurringKind.PAYCHECK;
        Map<String, Map<String, Object>> questions = new LinkedHashMap<>();
        if (!detectedByPlaid) {
            questions.put(REPEATS, pay ? PAYCHECK : BILL);
            questions.put(USUAL_FREQUENCY, FREQUENCY);
        }
        Map<String, Line> lineByOption = pay ? Map.of() : options(plan);
        if (!lineByOption.isEmpty()) {
            Map<String, Object> criteria = new LinkedHashMap<>();
            lineByOption.forEach((option, line) -> criteria.put(option,
                    "The " + line.name() + " line, for " + BUCKET_MEANINGS.get(line.bucket())));
            criteria.put(NO_LINE, NO_LINE_CRITERION);
            questions.put(LINE, ordered("type", "choice", "instructions", LINE_INSTRUCTIONS, "criteria", criteria));
        }
        if (questions.isEmpty()) {
            return null;
        }

        List<Charge> shown = charges.stream().map(Charge::of).toList();
        Map<String, Object> state = ordered("payee", displayName(charges.get(0)), pay ? "deposits" : "charges", shown);
        return new Request(payee, state, questions, lineByOption, fingerprint(state, questions));
    }

    /**
     * What Jev said about each payee, in the same order, from one request. Each question carries
     * its payee and charges ahead of its own instructions, and the state is empty, so questions
     * about one payee can't be read as being about another.
     */
    List<Judgment> ask(List<Request> requests) {
        Map<String, Map<String, Object>> questions = new LinkedHashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            for (Map.Entry<String, Map<String, Object>> question : request.questions().entrySet()) {
                questions.put(questionId(i, question.getKey()), contained(request.state(), question.getValue()));
            }
        }
        TypeSafeClient.Answers answers = typeSafe.ask(Map.of(), questions);
        return IntStream.range(0, requests.size())
                .mapToObj(i -> judgment(requests.get(i), id -> questionId(i, id), answers))
                .toList();
    }

    private static String questionId(int payee, String question) {
        return payee + "." + question;
    }

    // The payee's state goes first, then the question, as if it were asked about that state alone.
    private static Map<String, Object> contained(Map<String, Object> state, Map<String, Object> question) {
        Map<String, Object> instructions = new LinkedHashMap<>(state);
        if (question.get("instructions") instanceof Map<?, ?> own) {
            own.forEach((key, value) -> instructions.put((String) key, value));
        } else {
            instructions.put("question", question.get("instructions"));
        }
        Map<String, Object> contained = new LinkedHashMap<>(question);
        contained.put("instructions", instructions);
        return contained;
    }

    private static Judgment judgment(Request request, UnaryOperator<String> id, TypeSafeClient.Answers answers) {
        boolean askedRepeats = request.questions().containsKey(REPEATS);
        Line line = request.questions().containsKey(LINE)
                ? request.lineByOption().get(answers.choice(id.apply(LINE)))
                : null;
        return new Judgment(
                askedRepeats
                        ? BigDecimal.valueOf(answers.noul(id.apply(REPEATS))).setScale(4, RoundingMode.HALF_UP)
                        : null,
                askedRepeats
                        ? RecurringFrequency.valueOf(answers.choice(id.apply(USUAL_FREQUENCY)).toUpperCase(Locale.ROOT))
                        : null,
                line);
    }

    static String displayName(PlaidTransaction transaction) {
        String merchant = transaction.getMerchantName();
        return merchant != null && !merchant.isBlank() ? merchant : transaction.getName();
    }

    // Option keys are what the model reads, so each names its bucket and line.
    private static Map<String, Line> options(PlanLines plan) {
        Map<String, Line> lineByOption = new LinkedHashMap<>();
        addOptions(lineByOption, SpendingPlanBucket.FIXED_COSTS, plan.fixedCosts());
        addOptions(lineByOption, SpendingPlanBucket.INVESTMENTS, plan.investments());
        addOptions(lineByOption, SpendingPlanBucket.SAVINGS, plan.savings());
        return lineByOption;
    }

    private static void addOptions(Map<String, Line> lineByOption, SpendingPlanBucket bucket, List<String> names) {
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                lineByOption.putIfAbsent(BUCKET_WORDS.get(bucket) + ": " + name.strip(), new Line(bucket, name.strip()));
            }
        }
    }

    /**
     * Everything Jev is shown, hashed, so a payee is asked again only when that changes. Every map
     * in it keeps its order: Map.of's changes between JVM runs, which would re-ask every payee on
     * each restart.
     */
    private String fingerprint(Map<String, Object> state, Map<String, Map<String, Object>> questions) {
        try {
            byte[] shown = objectMapper.writeValueAsBytes(ordered("state", state, "questions", questions));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(shown));
        } catch (JacksonException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Couldn't fingerprint a payee's question", e);
        }
    }

    record Request(
            RecurringMerchant payee,
            Map<String, Object> state,
            Map<String, Map<String, Object>> questions,
            Map<String, Line> lineByOption,
            String fingerprint
    ) {
    }

    /**
     * What Jev said. Probability and frequency are null when they weren't asked; the line is null
     * for pay and when the payments fit no line.
     */
    record Judgment(BigDecimal probability, RecurringFrequency usualFrequency, Line line) {
    }

    record Line(SpendingPlanBucket bucket, String name) {
    }

    // The model reads amounts better without a sign; "charges" or "deposits" says which way money moved.
    record Charge(
            @JsonProperty("date") String date,
            @JsonProperty("description") String description,
            @JsonProperty("amount_usd") BigDecimal amountUsd,
            @JsonProperty("plaid_category") String plaidCategory,
            @JsonProperty("payment_channel") String paymentChannel
    ) {
        static Charge of(PlaidTransaction transaction) {
            return new Charge(
                    transaction.getTransactionDate().toString(),
                    transaction.getName(),
                    transaction.getAmount().abs().setScale(2, RoundingMode.HALF_UP),
                    PlaidCategory.readable(
                            transaction.getPersonalFinanceCategoryPrimary(),
                            transaction.getPersonalFinanceCategoryDetailed()),
                    transaction.getPaymentChannel());
        }
    }
}
