package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.CountryCode;
import com.plaid.client.model.InvestmentsHoldingsGetRequest;
import com.plaid.client.model.ItemRemoveRequest;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateRequestUser;
import com.plaid.client.model.LinkTokenTransactions;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Connects and removes a user's Plaid items, and stores their access for later syncs. */
@Service
public class PlaidItemLinking {

    /**
     * Transaction history requested for new links. Plaid's default is 90 days, and it recommends
     * at least 180 for detecting recurring streams; 730 is the most it offers.
     */
    private static final int DAYS_OF_HISTORY = 730;

    private final PlaidApi plaidApi;
    private final PlaidItemRepository plaidItemRepository;
    private final PlaidTokenEncryption tokenEncryption;
    private final PlaidItemSync itemSync;
    private final String webhookUrl;

    public PlaidItemLinking(
            PlaidApi plaidApi,
            PlaidItemRepository plaidItemRepository,
            PlaidTokenEncryption tokenEncryption,
            PlaidItemSync itemSync,
            @Value("${plaid.webhook.url:}") String webhookUrl
    ) {
        this.plaidApi = plaidApi;
        this.plaidItemRepository = plaidItemRepository;
        this.tokenEncryption = tokenEncryption;
        this.itemSync = itemSync;
        this.webhookUrl = webhookUrl;
    }

    /** A short-lived token that opens Plaid Link in the browser for this user. */
    public String createLinkToken(UUID userId) {
        return requestLinkToken(linkTokenRequest(userId)
                .products(List.of(Products.TRANSACTIONS))
                .transactions(new LinkTokenTransactions().daysRequested(DAYS_OF_HISTORY)));
    }

    /**
     * Like {@link #createLinkToken}, for linking investment accounts. Investments is required, so
     * Link shows institutions such as 401(k) providers that have no transactions; transactions
     * are added wherever the institution supports them. Plaid bills investments per item, so
     * only these links ask for it.
     */
    public String createInvestmentsLinkToken(UUID userId) {
        return requestLinkToken(linkTokenRequest(userId)
                .products(List.of(Products.INVESTMENTS))
                .optionalProducts(List.of(Products.TRANSACTIONS))
                .transactions(new LinkTokenTransactions().daysRequested(DAYS_OF_HISTORY)));
    }

    private static LinkTokenCreateRequest linkTokenRequest(UUID userId) {
        return new LinkTokenCreateRequest()
                .user(new LinkTokenCreateRequestUser().clientUserId(userId.toString()))
                .clientName("Money Buckets")
                .countryCodes(List.of(CountryCode.US))
                .language("en");
    }

    private String requestLinkToken(LinkTokenCreateRequest request) {
        // Items linked without a URL never receive webhooks, so local runs without a tunnel
        // only sync at link time.
        if (!webhookUrl.isBlank()) {
            request.webhook(webhookUrl);
        }

        return PlaidCalls.execute(plaidApi.linkTokenCreate(request), "link token create")
                .getLinkToken();
    }

    /**
     * Exchanges the public token Plaid Link returned, stores the item's encrypted access token and
     * syncs it. Plaid request failures during sync do not fail the link.
     *
     * @throws PlaidRequestException when Plaid cannot exchange the public token
     */
    public Linked link(UUID userId, String publicToken) {
        ItemPublicTokenExchangeResponse exchange = PlaidCalls.execute(
                plaidApi.itemPublicTokenExchange(
                        new ItemPublicTokenExchangeRequest().publicToken(publicToken)),
                "token exchange");

        // Plaid returns the same item_id when an institution is re-linked, so this upserts
        // onto the existing row to keep its transactions cursor.
        String encryptedToken = tokenEncryption.encrypt(
                exchange.getAccessToken(), userId, exchange.getItemId());
        PlaidItem item = plaidItemRepository.findByItemIdAndUserId(exchange.getItemId(), userId)
                .orElseGet(() -> new PlaidItem(exchange.getItemId(), encryptedToken, userId));
        item.updateAccessToken(encryptedToken);
        plaidItemRepository.save(item);

        itemSync.linked(item);
        return new Linked(exchange.getItemId(), sameInstitution(userId, exchange.getItemId()));
    }

    /**
     * Other items the user has at the linked item's institution. Linking an institution again
     * creates a second item holding the same accounts, which would count twice in net worth.
     * Empty when the sync couldn't tell which institution the item belongs to.
     */
    private List<PlaidItem> sameInstitution(UUID userId, String itemId) {
        String institutionId = plaidItemRepository.findById(itemId)
                .map(PlaidItem::getInstitutionId)
                .orElse(null);
        if (institutionId == null) {
            return List.of();
        }
        return plaidItemRepository.findAllByUserIdAndInstitutionIdAndRemovedOnIsNull(userId, institutionId)
                .stream()
                .filter(other -> !other.getItemId().equals(itemId))
                .toList();
    }

    /**
     * Adds Plaid's investments product to an item already linked for transactions, so an
     * institution the user has connected never needs a second item. If the holdings request fails,
     * returns a Link update-mode token; once that flow finishes, call this again. Institutions
     * that don't offer investments, such as many banks, are refused up front.
     *
     * @throws NoSuchElementException when the user has no such item
     * @throws PlaidRequestException when Plaid cannot create the update-mode token; a failed
     *         holdings request instead produces that token
     */
    public AddInvestments addInvestments(UUID userId, String itemId) {
        PlaidItem item = activeItem(userId, itemId);
        if (item.getInvestmentsAvailable() == null) {
            // Synced before we tracked this; the accounts list tells us.
            itemSync.refreshAccounts(itemId);
            item = activeItem(userId, itemId);
        }
        if (Boolean.FALSE.equals(item.getInvestmentsAvailable())) {
            return AddInvestments.notOffered();
        }
        String accessToken = tokenEncryption.decrypt(item.getEncryptedAccessToken(), userId, itemId);
        try {
            PlaidCalls.execute(plaidApi.investmentsHoldingsGet(
                    new InvestmentsHoldingsGetRequest().accessToken(accessToken)), "investments holdings get");
        } catch (PlaidRequestException e) {
            return AddInvestments.needsConsent(requestLinkToken(linkTokenRequest(userId)
                    .accessToken(accessToken)
                    .additionalConsentedProducts(List.of(Products.INVESTMENTS))));
        }

        // Picks up the new product and fresh investment balances.
        itemSync.linked(item);
        return AddInvestments.added();
    }

    /**
     * Removes an item from Plaid, which ends its billing, then stops syncing it. Its accounts
     * stay as dropped accounts so net worth keeps its history.
     *
     * @throws NoSuchElementException when the user has no such item
     * @throws PlaidRequestException when Plaid cannot remove the item; it remains linked
     */
    public void remove(UUID userId, String itemId) {
        PlaidItem item = activeItem(userId, itemId);
        String accessToken = tokenEncryption.decrypt(item.getEncryptedAccessToken(), userId, itemId);
        PlaidCalls.execute(plaidApi.itemRemove(new ItemRemoveRequest().accessToken(accessToken)), "item remove");
        itemSync.removed(itemId);
    }

    /**
     * Asks Plaid again for the recurring streams of each item the user has linked. Every item is
     * tried before any failure is thrown.
     *
     * @throws RuntimeException the first failure, e.g. a {@link PlaidRequestException}
     */
    public void recheckRecurring(UUID userId) {
        RuntimeException firstFailure = null;
        for (PlaidItem item : plaidItemRepository.findAllByUserIdAndRemovedOnIsNullOrderByItemIdAsc(userId)) {
            try {
                itemSync.recheckRecurring(item.getItemId());
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private PlaidItem activeItem(UUID userId, String itemId) {
        return plaidItemRepository.findByItemIdAndUserIdAndRemovedOnIsNull(itemId, userId)
                .orElseThrow(() -> new NoSuchElementException("No item " + itemId));
    }

    /** A linked item, and the user's other items at the same institution. */
    public record Linked(String itemId, List<PlaidItem> sameInstitution) {
    }

    /**
     * What happened when adding investments. For {@code NEEDS_CONSENT}, the user must finish Link
     * with {@code linkToken} first.
     */
    public record AddInvestments(Outcome outcome, String linkToken) {

        public enum Outcome { ADDED, NEEDS_CONSENT, NOT_OFFERED }

        static AddInvestments added() {
            return new AddInvestments(Outcome.ADDED, null);
        }

        static AddInvestments needsConsent(String linkToken) {
            return new AddInvestments(Outcome.NEEDS_CONSENT, linkToken);
        }

        static AddInvestments notOffered() {
            return new AddInvestments(Outcome.NOT_OFFERED, null);
        }
    }
}
