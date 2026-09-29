package dev.matthewsawyer.finance_dashboard.model;

import java.util.Locale;

/**
 * Who a recurring payment is with, and which way it goes: what the user's answers about recurring
 * candidates are stored under, and how a transaction is matched to a Plaid recurring stream.
 */
public record RecurringMerchant(RecurringKind kind, String key) {

    /** The merchant a transaction is with, or null when it has neither a merchant nor a description. */
    public static RecurringMerchant of(PlaidTransaction transaction) {
        boolean pay = "INCOME".equals(transaction.getPersonalFinanceCategoryPrimary())
                && transaction.getAmount().signum() < 0;
        return of(pay ? RecurringKind.PAYCHECK : RecurringKind.BILL,
                key(transaction.getMerchantName(), transaction.getName()));
    }

    /** The merchant a Plaid stream is with, or null when it has neither a merchant nor a description. */
    public static RecurringMerchant of(PlaidRecurringStream stream) {
        return of(stream.isInflow() ? RecurringKind.PAYCHECK : RecurringKind.BILL,
                key(stream.getMerchantName(), stream.getDescription()));
    }

    /**
     * The merchant, or the description when Plaid found no merchant, in lower case and without
     * punctuation. Digits are dropped from descriptions, since banks put dates and reference
     * numbers in them that change with every charge.
     */
    public static String key(String merchantName, String description) {
        boolean hasMerchant = merchantName != null && !merchantName.isBlank();
        String source = hasMerchant ? merchantName : description;
        if (source == null) {
            return null;
        }
        String key = source.toLowerCase(Locale.ROOT);
        if (!hasMerchant) {
            key = key.replaceAll("\\p{N}", "");
        }
        key = key.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return key.isEmpty() ? null : key;
    }

    private static RecurringMerchant of(RecurringKind kind, String key) {
        return key == null ? null : new RecurringMerchant(kind, key);
    }
}
