package dev.matthewsawyer.finance_dashboard.model;

import java.time.LocalDate;

/**
 * How often a recurring payment is made. The names Plaid shares match Plaid's recurring stream
 * frequencies; quarterly and twice-yearly cover bills Plaid has no word for, like insurance.
 */
public enum RecurringFrequency {
    WEEKLY,
    BIWEEKLY,
    SEMI_MONTHLY,
    MONTHLY,
    QUARTERLY,
    SEMI_ANNUALLY,
    ANNUALLY;

    /** When a payment made on {@code last} is next expected. */
    public LocalDate next(LocalDate last) {
        return switch (this) {
            case WEEKLY -> last.plusWeeks(1);
            case BIWEEKLY -> last.plusWeeks(2);
            case SEMI_MONTHLY -> last.plusDays(15);
            case MONTHLY -> last.plusMonths(1);
            case QUARTERLY -> last.plusMonths(3);
            case SEMI_ANNUALLY -> last.plusMonths(6);
            case ANNUALLY -> last.plusYears(1);
        };
    }
}
