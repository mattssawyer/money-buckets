package dev.matthewsawyer.finance_dashboard.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaidCategoryTests {

    @Test
    void readsPlaidCategoriesAsWords() {
        assertEquals("food and drink: coffee", PlaidCategory.readable("FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        assertEquals("travel", PlaidCategory.readable("TRAVEL", null));
        assertEquals("other transfer", PlaidCategory.readable("TRANSFER_OUT", "OTHER_TRANSFER"));
        assertNull(PlaidCategory.readable(null, null));
    }
}
