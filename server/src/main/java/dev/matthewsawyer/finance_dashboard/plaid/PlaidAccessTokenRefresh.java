package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.ItemAccessTokenInvalidateRequest;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A one-off job for after a leak. With {@code PLAID_REFRESH_ACCESS_TOKENS=true}, each start asks
 * Plaid for a new access token for every linked item, so stolen tokens stop working, and stores
 * it encrypted with the keyset's primary key. Removed items, and items Plaid refuses, keep their
 * token but are encrypted again with the primary key. Once a run logs no failures, older keys can
 * be disabled. Turn the setting off afterwards: each start would otherwise replace the tokens again.
 */
@Component
public class PlaidAccessTokenRefresh {

    private static final Logger log = LoggerFactory.getLogger(PlaidAccessTokenRefresh.class);

    private final boolean enabled;
    private final PlaidApi plaidApi;
    private final PlaidItemRepository items;
    private final PlaidTokenEncryption tokenEncryption;
    private final PlaidItemLocks locks;

    PlaidAccessTokenRefresh(
            @Value("${PLAID_REFRESH_ACCESS_TOKENS:false}") boolean enabled,
            PlaidApi plaidApi,
            PlaidItemRepository items,
            PlaidTokenEncryption tokenEncryption,
            PlaidItemLocks locks
    ) {
        this.enabled = enabled;
        this.plaidApi = plaidApi;
        this.items = items;
        this.tokenEncryption = tokenEncryption;
        this.locks = locks;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        if (enabled) {
            refreshAll();
        }
    }

    Summary refreshAll() {
        int replaced = 0;
        int reEncrypted = 0;
        int failed = 0;
        for (PlaidItem item : items.findAll()) {
            Outcome outcome;
            try {
                outcome = refresh(item);
            } catch (RuntimeException e) {
                // Messages here never contain tokens: decryption and Plaid errors leave them out.
                log.error("Plaid token refresh: item={} failed: {}", item.getItemId(), e.toString());
                outcome = Outcome.FAILED;
            }
            log.info("Plaid token refresh: item={} outcome={}", item.getItemId(), outcome);
            switch (outcome) {
                case REPLACED -> replaced++;
                case RE_ENCRYPTED, PLAID_REFUSED -> reEncrypted++;
                case FAILED -> failed++;
            }
        }
        Summary summary = new Summary(replaced, reEncrypted, failed);
        log.info("Plaid token refresh finished: replaced={} re_encrypted={} failed={} old_keys_can_be_disabled={}",
                replaced, reEncrypted, failed, failed == 0);
        return summary;
    }

    private Outcome refresh(PlaidItem item) {
        String itemId = item.getItemId();
        AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.FAILED);
        // Holding the sync lock keeps a sync from using the old token after Plaid has retired it.
        boolean ran = locks.run(itemId, Duration.ofMinutes(2), () -> {
            String token = tokenEncryption.decrypt(item.getEncryptedAccessToken(), item.getUserId(), itemId);
            Outcome result = Outcome.RE_ENCRYPTED;
            if (!item.isRemoved()) {
                try {
                    token = PlaidCalls.execute(plaidApi.itemAccessTokenInvalidate(
                            new ItemAccessTokenInvalidateRequest().accessToken(token)),
                            "item access token invalidate").getNewAccessToken();
                    result = Outcome.REPLACED;
                } catch (PlaidRequestException e) {
                    result = Outcome.PLAID_REFUSED;
                }
            }
            // Store right away: once Plaid has issued the new token, the old one no longer works.
            try {
                items.updateEncryptedAccessToken(itemId, tokenEncryption.encrypt(token, item.getUserId(), itemId));
            } catch (RuntimeException e) {
                if (result == Outcome.REPLACED) {
                    log.error("Plaid token refresh: item={} got a new token but could not store it;"
                            + " the bank must be linked again", itemId);
                }
                throw e;
            }
            outcome.set(result);
        });
        if (!ran) {
            log.warn("Plaid token refresh: item={} could not get the item's lock", itemId);
        }
        return outcome.get();
    }

    enum Outcome { REPLACED, RE_ENCRYPTED, PLAID_REFUSED, FAILED }

    record Summary(int replaced, int reEncrypted, int failed) {
    }
}
