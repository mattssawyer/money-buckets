package dev.matthewsawyer.finance_dashboard.sorting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static dev.matthewsawyer.finance_dashboard.sorting.BucketClassifier.ordered;

/**
 * Picks the plan line a recurring payment belongs under when Plaid's category doesn't say, such as
 * rent Plaid labels home improvement. Plaid's category is left out of the question for that reason:
 * Jev judges from who is paid, how much and how often.
 */
@Component
public class PlanLineChooser {

    private static final Logger log = LoggerFactory.getLogger(PlanLineChooser.class);

    private static final String LINE_QUESTION_ID = "line";
    private static final String NONE = "none";

    private static final Map<SpendingPlanBucket, String> BUCKET_WORDS = Map.of(
            SpendingPlanBucket.FIXED_COSTS, "fixed costs",
            SpendingPlanBucket.INVESTMENTS, "investments",
            SpendingPlanBucket.SAVINGS, "savings");

    private static final Object INSTRUCTIONS = ordered(
            "question", "Which line of the user's Conscious Spending Plan does the recurring payment `payment` "
                    + "belong under?",
            "guidance", "`payment` repeats on a schedule. Choose the line whose kind of cost fits it best; a large "
                    + "monthly payment to a company that is not clearly something else is usually rent paid to a "
                    + "landlord or property manager.");

    private static final String NONE_CRITERION = "Not a bill, investment or saving: money moved between the user's "
            + "own checking accounts, credit card payments, or payments to friends or family.";

    private final TypeSafeClient typeSafe;
    private final Executor executor;

    PlanLineChooser(TypeSafeClient typeSafe, @Qualifier("sortingClassifyExecutor") Executor executor) {
        this.typeSafe = typeSafe;
        this.executor = executor;
    }

    /**
     * The line each payment belongs under, keyed by payment id. Payments that fit no line, and
     * ones whose question failed, are left out for the user to place.
     */
    public Map<String, Line> choose(List<Payment> payments, PlanLines plan) {
        Map<String, Line> lineByOption = options(plan);
        if (payments.isEmpty() || lineByOption.isEmpty() || !typeSafe.isConfigured()) {
            return Map.of();
        }
        Map<String, Object> criteria = new LinkedHashMap<>();
        lineByOption.forEach((option, line) -> criteria.put(option, "The " + line.name() + " line"));
        criteria.put(NONE, NONE_CRITERION);
        Map<String, Map<String, Object>> questions = Map.of(LINE_QUESTION_ID, ordered(
                "type", "choice",
                "instructions", INSTRUCTIONS,
                "criteria", criteria));

        List<CompletableFuture<String>> answers = payments.stream()
                .map(payment -> CompletableFuture.supplyAsync(
                        () -> typeSafe.ask(new State(PaymentState.of(payment)), questions).choice(LINE_QUESTION_ID),
                        executor))
                .toList();

        Map<String, Line> chosen = new HashMap<>();
        int failed = 0;
        for (int i = 0; i < payments.size(); i++) {
            try {
                Line line = lineByOption.get(answers.get(i).join());
                if (line != null) {
                    chosen.put(payments.get(i).id(), line);
                }
            } catch (CompletionException e) {
                failed++;
            }
        }
        if (failed > 0) {
            log.warn("Couldn't choose a plan line for {} of {} recurring payments", failed, payments.size());
        }
        return chosen;
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

    /** A recurring payment to place: Plaid's stream or a confirmed candidate, at the user's share. */
    public record Payment(String id, String description, String merchant, BigDecimal amount, String frequency) {
    }

    public record Line(SpendingPlanBucket bucket, String name) {
    }

    record State(@JsonProperty("payment") PaymentState payment) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PaymentState(
            @JsonProperty("description") String description,
            @JsonProperty("merchant") String merchant,
            @JsonProperty("amount_usd") BigDecimal amountUsd,
            @JsonProperty("frequency") String frequency
    ) {
        static PaymentState of(Payment payment) {
            return new PaymentState(
                    payment.description(),
                    payment.merchant(),
                    payment.amount() == null ? null : payment.amount().abs().setScale(2, RoundingMode.HALF_UP),
                    payment.frequency() == null ? null : payment.frequency().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
    }
}
