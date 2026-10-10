package dev.matthewsawyer.finance_dashboard.controller;

import dev.matthewsawyer.finance_dashboard.plaid.PlaidItemSync;
import dev.matthewsawyer.finance_dashboard.service.PlaidWebhookVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidWebhookControllerTests {

    private static final String SIGNATURE = "plaid-verification-jwt";
    private static final String SYNC_BODY = """
            {"webhook_type":"TRANSACTIONS","webhook_code":"SYNC_UPDATES_AVAILABLE",\
            "item_id":"item-id","environment":"sandbox"}""";
    @Mock
    private PlaidWebhookVerifier webhookVerifier;

    @Mock
    private PlaidItemSync itemSync;

    private PlaidWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new PlaidWebhookController(webhookVerifier, itemSync, new ObjectMapper());
    }

    @Test
    void handsVerifiedWebhooksToItemSync() {
        when(webhookVerifier.isValid(SYNC_BODY, SIGNATURE)).thenReturn(true);

        controller.receiveWebhook(SYNC_BODY, SIGNATURE);

        verify(itemSync).notified("item-id", "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", null);
    }

    @Test
    void passesOnTheErrorCodeOfAnItemError() {
        String body = """
                {"webhook_type":"ITEM","webhook_code":"ERROR","item_id":"item-id",\
                "error":{"error_type":"ITEM_ERROR","error_code":"ITEM_LOGIN_REQUIRED"}}""";
        when(webhookVerifier.isValid(body, SIGNATURE)).thenReturn(true);

        controller.receiveWebhook(body, SIGNATURE);

        verify(itemSync).notified("item-id", "ITEM", "ERROR", "ITEM_LOGIN_REQUIRED");
    }

    @Test
    void rejectsWebhookWithAnInvalidSignature() {
        when(webhookVerifier.isValid(SYNC_BODY, SIGNATURE)).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> controller.receiveWebhook(SYNC_BODY, SIGNATURE));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verifyNoInteractions(itemSync);
    }

    @Test
    void rejectsAnUnreadableBody() {
        when(webhookVerifier.isValid("not-json", SIGNATURE)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> controller.receiveWebhook("not-json", SIGNATURE));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(itemSync);
    }
}
