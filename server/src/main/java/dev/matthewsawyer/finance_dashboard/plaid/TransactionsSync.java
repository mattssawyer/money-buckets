package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncRequestOptions;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Brings an item's transactions up to date, starting from its stored cursor. */
@Component
class TransactionsSync {

    private static final Logger log = LoggerFactory.getLogger(TransactionsSync.class);

    private static final int PAGE_SIZE = 500;

    // Plaid always advances the cursor, so this only trips if the API misbehaves.
    private static final int MAX_PAGES = 200;

    private final PlaidApi plaidApi;
    private final PlaidItemRepository plaidItemRepository;
    private final PlaidTransactionRepository transactionRepository;
    private final PlaidTokenEncryption tokenEncryption;
    private final TransactionTemplate transactionTemplate;

    TransactionsSync(
            PlaidApi plaidApi,
            PlaidItemRepository plaidItemRepository,
            PlaidTransactionRepository transactionRepository,
            PlaidTokenEncryption tokenEncryption,
            TransactionTemplate transactionTemplate
    ) {
        this.plaidApi = plaidApi;
        this.plaidItemRepository = plaidItemRepository;
        this.transactionRepository = transactionRepository;
        this.tokenEncryption = tokenEncryption;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Pulls every page Plaid has for the item. Returns the number of transactions added,
     * modified or removed.
     */
    int sync(PlaidItem item) {
        String itemId = item.getItemId();
        log.info("Starting transactions sync for item {}", itemId);

        String accessToken = tokenEncryption.decrypt(
                item.getEncryptedAccessToken(), item.getUserId(), itemId);

        // No page is visible, and no cursor advances, until a complete batch is fetched.
        // A mutation invalidates the entire batch, including pages already fetched.
        for (int attempt = 0; attempt < 3; attempt++) {
            String cursor = item.getTransactionsCursor();
            List<TransactionsSyncResponse> batch = new ArrayList<>();
            try {
                for (int page = 0; page < MAX_PAGES; page++) {
                    TransactionsSyncResponse body = fetchPage(accessToken, cursor);
                    batch.add(body);
                    cursor = body.getNextCursor();
                    if (!Boolean.TRUE.equals(body.getHasMore())) {
                        return transactionTemplate.execute(status -> {
                            int changed = 0;
                            for (TransactionsSyncResponse result : batch) {
                                PageCounts counts = applyPage(item, result);
                                changed += counts.added() + counts.modified() + counts.removed();
                            }
                            plaidItemRepository.updateTransactionsCursor(itemId, body.getNextCursor());
                            if (body.getTransactionsUpdateStatus() != null
                                    && "HISTORICAL_UPDATE_COMPLETE".equals(body.getTransactionsUpdateStatus().getValue())) {
                                plaidItemRepository.markTransactionHistoryReady(itemId);
                            }
                            log.info("Plaid transactions result: item={} pages={} changed={}", itemId, batch.size(), changed);
                            return changed;
                        });
                    }
                }
                throw new IllegalStateException("Plaid transactions sync exceeded " + MAX_PAGES + " pages");
            } catch (PlaidRequestException e) {
                if (!"TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION".equals(e.getErrorCode()) || attempt == 2) {
                    throw e;
                }
                log.info("Restarting Plaid pagination for item {} after mutation", itemId);
            }
        }
        throw new IllegalStateException("Unreachable pagination state");
    }

    /** Deletes the transactions of an item the user removed. */
    void forget(String itemId) {
        transactionTemplate.executeWithoutResult(status -> transactionRepository.deleteAllByItemId(itemId));
    }

    private TransactionsSyncResponse fetchPage(String accessToken, String cursor) {
        TransactionsSyncRequest request = new TransactionsSyncRequest()
                .accessToken(accessToken)
                .cursor(cursor)
                .count(PAGE_SIZE)
                .options(new TransactionsSyncRequestOptions().includePersonalFinanceCategory(true));

        return PlaidCalls.execute(plaidApi.transactionsSync(request), "transactions sync");
    }

    /**
     * Applies a page within the complete batch transaction.
     */
    private PageCounts applyPage(PlaidItem item, TransactionsSyncResponse page) {
        List<Transaction> added = Objects.requireNonNullElse(page.getAdded(), List.of());
        List<Transaction> modified = Objects.requireNonNullElse(page.getModified(), List.of());
        List<RemovedTransaction> removed = Objects.requireNonNullElse(page.getRemoved(), List.of());

        transactionTemplate.executeWithoutResult(status -> {
            List<PlaidTransaction> upserts = Stream.concat(added.stream(), modified.stream())
                    .map(transaction -> toEntity(transaction, item))
                    .toList();
            if (!upserts.isEmpty()) {
                transactionRepository.saveAll(upserts);
            }

            List<String> removedIds = removed.stream()
                    .map(RemovedTransaction::getTransactionId)
                    .toList();
            if (!removedIds.isEmpty()) {
                transactionRepository.deleteAllByItemIdAndTransactionIdIn(item.getItemId(), removedIds);
            }
        });

        return new PageCounts(added.size(), modified.size(), removed.size());
    }

    private record PageCounts(int added, int modified, int removed) {
    }

    private static PlaidTransaction toEntity(Transaction transaction, PlaidItem item) {
        return new PlaidTransaction(
                transaction.getTransactionId(),
                item.getItemId(),
                item.getUserId(),
                transaction.getAccountId(),
                BigDecimal.valueOf(Objects.requireNonNullElse(transaction.getAmount(), 0.0)),
                transaction.getDate()
        )
                .isoCurrencyCode(transaction.getIsoCurrencyCode())
                .unofficialCurrencyCode(transaction.getUnofficialCurrencyCode())
                .authorizedDate(transaction.getAuthorizedDate())
                .name(transaction.getName())
                .merchantName(transaction.getMerchantName())
                .logoUrl(transaction.getLogoUrl())
                .pending(Boolean.TRUE.equals(transaction.getPending()))
                .pendingTransactionId(transaction.getPendingTransactionId())
                .paymentChannel(transaction.getPaymentChannel() == null
                        ? null : transaction.getPaymentChannel().getValue())
                .personalFinanceCategory(
                        transaction.getPersonalFinanceCategory() == null
                                ? null : transaction.getPersonalFinanceCategory().getPrimary(),
                        transaction.getPersonalFinanceCategory() == null
                                ? null : transaction.getPersonalFinanceCategory().getDetailed());
    }
}
