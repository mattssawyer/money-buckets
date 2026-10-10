package dev.matthewsawyer.finance_dashboard.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.plaid.PlaidItemSync;
import dev.matthewsawyer.finance_dashboard.service.PlaidWebhookVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Public endpoint: Plaid authenticates itself with a signature rather than a user token.
 */
@RestController
@RequestMapping("/plaid")
public class PlaidWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PlaidWebhookController.class);

    private final PlaidWebhookVerifier webhookVerifier;
    private final PlaidItemSync itemSync;
    private final ObjectMapper objectMapper;

    public PlaidWebhookController(
            PlaidWebhookVerifier webhookVerifier,
            PlaidItemSync itemSync,
            ObjectMapper objectMapper
    ) {
        this.webhookVerifier = webhookVerifier;
        this.itemSync = itemSync;
        this.objectMapper = objectMapper;
    }

    /**
     * Takes the body as a string because the signature covers Plaid's exact bytes, which
     * a deserialized object could not reproduce.
     */
    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(name = "Plaid-Verification", required = false) String verification
    ) {
        if (!webhookVerifier.isValid(rawBody, verification)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Plaid webhook signature");
        }

        WebhookPayload payload = parse(rawBody);
        log.info(
                "Received Plaid webhook {}/{} for item {}",
                payload.webhookType(),
                payload.webhookCode(),
                payload.itemId());

        itemSync.notified(payload.itemId(), payload.webhookType(), payload.webhookCode(),
                payload.error() == null ? null : payload.error().errorCode());
    }

    private WebhookPayload parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable Plaid webhook body");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookPayload(
            @JsonProperty("webhook_type") String webhookType,
            @JsonProperty("webhook_code") String webhookCode,
            @JsonProperty("item_id") String itemId,
            @JsonProperty("error") WebhookError error
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookError(@JsonProperty("error_code") String errorCode) {
    }
}
