package dev.matthewsawyer.finance_dashboard.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaidAccountTests {

    @Test
    void bankAccountsAndCreditCardsCanBeTracked() {
        assertTrue(account("depository", "checking").isTrackable());
        assertTrue(account("depository", "money market").isTrackable());
        assertTrue(account("credit", "credit card").isTrackable());
        assertFalse(account("depository", "cd").isTrackable());
        assertFalse(account("investment", "ira").isTrackable());
        assertFalse(account("loan", "mortgage").isTrackable());
    }

    @Test
    void anAccountPlaidSentNoSubtypeForIsNotTrackedByDefault() {
        PlaidAccount account = account("depository", null);

        account.trackByDefault();

        assertFalse(account.tracksSpending());
    }

    @Test
    void refusesToTrackAnAccountSpendingCantComeFrom() {
        PlaidAccount ira = account("investment", "ira");

        assertThrows(IllegalArgumentException.class, () -> ira.updateTracking(true, true, 100));
        ira.updateTracking(false, true, 100);
        assertFalse(ira.tracksSpending());
    }

    @Test
    void keepsTheShareBetweenOneAndAHundredPercent() {
        PlaidAccount joint = account("depository", "checking");

        assertThrows(IllegalArgumentException.class, () -> joint.updateTracking(true, true, 0));
        assertThrows(IllegalArgumentException.class, () -> joint.updateTracking(true, true, 101));
        joint.updateTracking(true, true, 50);
        assertEquals(50, joint.getSharePercent());
    }

    private static PlaidAccount account(String type, String subtype) {
        PlaidAccount account = new PlaidAccount("account", "item", UUID.randomUUID());
        account.updateSnapshot("Account", null, null, type, subtype, null, null, null, "USD", null);
        return account;
    }
}
