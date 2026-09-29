package dev.matthewsawyer.finance_dashboard.recurring;

import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A merchant Jev thinks the user pays or is paid by regularly. The amount, account and
 * categories are the latest likely charge's; the amount is the whole charge, positive for money
 * out like Plaid's, and {@code sharePercent} is the user's part of that account.
 */
public record RecurringCandidate(
        RecurringKind kind,
        String merchantKey,
        String name,
        String accountId,
        BigDecimal amount,
        String isoCurrencyCode,
        RecurringFrequency frequency,
        LocalDate nextDate,
        LocalDate lastDate,
        String category,
        String categoryDetailed,
        int sharePercent,
        BigDecimal probability,
        Status status
) {

    /** Whether the user has answered: suggested until they do, then confirmed or dismissed. */
    public enum Status {
        SUGGESTED,
        CONFIRMED,
        DISMISSED
    }
}
