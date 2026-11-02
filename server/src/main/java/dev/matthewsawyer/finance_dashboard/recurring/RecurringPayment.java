package dev.matthewsawyer.finance_dashboard.recurring;

import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A payment that counts as recurring: one of Plaid's recurring streams, or a recurring candidate
 * the user confirmed, in the same shape. The amount is the whole payment, positive for money out
 * like Plaid's, and {@code sharePercent} is the user's part of its account. A candidate's
 * {@code streamId} is made up from its payee, since it has no stream of its own.
 */
public record RecurringPayment(
        String streamId,
        String accountId,
        String merchantName,
        String description,
        BigDecimal amount,
        String isoCurrencyCode,
        /** The user's own frequency for the payee if they set one, else Plaid's or the charges'. */
        String frequency,
        boolean frequencySet,
        LocalDate nextDate,
        LocalDate lastDate,
        boolean inflow,
        String category,
        String categoryDetailed,
        int sharePercent,
        /** The plan line Jev put the payee's bills under; null for pay, and until it's judged. */
        SpendingPlanBucket planBucket,
        String planLine,
        /** Who is paid, as the user's answers are stored; null when Plaid named nobody. */
        RecurringKind kind,
        String merchantKey,
        Source source
) {

    /** Where the payment comes from. */
    public enum Source {
        /** One of Plaid's recurring streams. */
        DETECTED,
        /** A recurring candidate the user said repeats. */
        CONFIRMED
    }
}
