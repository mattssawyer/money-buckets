package dev.matthewsawyer.finance_dashboard.sorting;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.matthewsawyer.finance_dashboard.sorting.BucketClassifier.ordered;

/**
 * Asks Jev whether a transaction looks like it repeats on a schedule, a bill or subscription for
 * money out and a paycheck for pay coming in, and how often a payment like it is usually made.
 * Sorting asks about money out in the same request as its bucket; pay is never sorted, so it's
 * asked on its own here.
 */
@Component
class RecurringJudge {

    static final String REPEATS = "repeats";
    static final String USUAL_FREQUENCY = "usual_frequency";

    private static final Map<String, Object> BILL = ordered(
            "type", "noul",
            "instructions", ordered(
                    "question", "Is `transaction` a charge for a bill or subscription that the user is likely "
                            + "charged again on a regular schedule?",
                    "guidance", "`other_charges_from_same_payee` lists the user's other charges from the same "
                            + "payee; the same amount on a regular schedule suggests a bill."),
            "criteria", ordered(
                    "true", "A bill or subscription charged on a schedule: rent, utilities, phone, internet, "
                            + "insurance, loan payments, streaming, software, memberships or gym fees.",
                    "false", "A one-off or occasional purchase, even from a store the user visits often: "
                            + "shopping, groceries, eating out, travel, cash withdrawals or a payment to a person."));

    private static final Map<String, Object> PAYCHECK = ordered(
            "type", "noul",
            "instructions", "Is `transaction` the user's pay from a job, likely to arrive again on a regular schedule?",
            "criteria", ordered(
                    "true", "Salary or wages from an employer, paid by payroll or direct deposit.",
                    "false", "Other money coming in: refunds, interest, tax refunds, reimbursements, "
                            + "money from other people or one-off payments."));

    // Option keys are what the model reads; they match RecurringFrequency once upper-cased.
    private static final Map<String, Object> FREQUENCY = ordered(
            "type", "choice",
            "instructions", "If payments like `transaction` repeat, how often are they usually made?",
            "criteria", ordered(
                    "weekly", "Every week",
                    "biweekly", "Every two weeks",
                    "semi_monthly", "Twice a month, such as on the 1st and the 15th",
                    "monthly", "Once a month",
                    "quarterly", "Every three months",
                    "semi_annually", "Twice a year",
                    "annually", "Once a year"));

    private final TypeSafeClient typeSafe;

    RecurringJudge(TypeSafeClient typeSafe) {
        this.typeSafe = typeSafe;
    }

    /** Whether {@code transaction} is pay coming in, asked as a paycheck rather than a bill. */
    static boolean isPay(PlaidTransaction transaction) {
        return "INCOME".equals(transaction.getPersonalFinanceCategoryPrimary())
                && transaction.getAmount().signum() < 0;
    }

    /** The questions to ask about {@code transaction}, keyed by question id. */
    static Map<String, Map<String, Object>> questions(PlaidTransaction transaction) {
        return Map.of(REPEATS, isPay(transaction) ? PAYCHECK : BILL, USUAL_FREQUENCY, FREQUENCY);
    }

    static RecurringJudgment read(TypeSafeClient.Answers answers) {
        return new RecurringJudgment(
                answers.noul(REPEATS),
                RecurringFrequency.valueOf(answers.choice(USUAL_FREQUENCY).toUpperCase(Locale.ROOT)));
    }

    /**
     * Asks whether {@code transaction} repeats. Money out is judged alongside the user's other
     * charges from the same payee; pay is judged on its own.
     */
    RecurringJudgment judge(PlaidTransaction transaction, List<PlaidTransaction> otherCharges) {
        boolean pay = isPay(transaction);
        BucketClassifier.State state = new BucketClassifier.State(
                BucketClassifier.TransactionState.of(transaction, !pay),
                null,
                pay ? null : BucketClassifier.Charge.all(otherCharges));
        return read(typeSafe.ask(state, questions(transaction)));
    }

    /** How likely Jev thinks it is that a transaction repeats, from 0 to 1, and how often it would. */
    record RecurringJudgment(double probability, RecurringFrequency usualFrequency) {
    }
}
