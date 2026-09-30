package dev.matthewsawyer.finance_dashboard.model;

import java.util.Locale;

/** Plaid's personal finance categories as words a model reads. */
public final class PlaidCategory {

    private PlaidCategory() {
    }

    /** Turns FOOD_AND_DRINK / FOOD_AND_DRINK_COFFEE into "food and drink: coffee". */
    public static String readable(String primary, String detailed) {
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
}
