package dev.matthewsawyer.finance_dashboard.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PayeeCorrection;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.payees.PayeeLookup;
import dev.matthewsawyer.finance_dashboard.plaid.PlaidItemLinking;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayees;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.spending.Spending;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

@RestController
@RequestMapping("/plaid")
public class PlaidController {

    private static final int MAX_TRANSACTION_LIMIT = 100;
    private static final int DEFAULT_RECURRING_STREAMS = 8;
    private static final int MAX_RECURRING_STREAMS = 50;

    /** Transactions sorting hasn't reached yet. */
    static final String UNSORTED = "UNSORTED";

    // The order buckets are listed in, following the plan. NOT_COUNTED is left out of spending.
    private static final List<String> BUCKET_ORDER = List.of(
            Bucket.FIXED_COSTS.name(),
            Bucket.GUILT_FREE.name(),
            Bucket.SAVINGS.name(),
            Bucket.INVESTMENTS.name(),
            UNSORTED);

    private final PlaidItemLinking itemLinking;
    private final PlaidItemRepository plaidItemRepository;
    private final PlaidAccountRepository accountRepository;
    private final PlaidTransactionRepository transactionRepository;
    private final PlaidRecurringStreamRepository recurringStreamRepository;
    private final TrackedAccounts trackedAccounts;
    private final Spending spending;
    private final RecurringPayees recurringPayees;
    private final PayeeLookup payeeLookup;
    private final UserService userService;

    public PlaidController(
            PlaidItemLinking itemLinking,
            PlaidItemRepository plaidItemRepository,
            PlaidAccountRepository accountRepository,
            PlaidTransactionRepository transactionRepository,
            PlaidRecurringStreamRepository recurringStreamRepository,
            TrackedAccounts trackedAccounts,
            Spending spending,
            RecurringPayees recurringPayees,
            PayeeLookup payeeLookup,
            UserService userService
    ) {
        this.itemLinking = itemLinking;
        this.plaidItemRepository = plaidItemRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.recurringStreamRepository = recurringStreamRepository;
        this.trackedAccounts = trackedAccounts;
        this.spending = spending;
        this.recurringPayees = recurringPayees;
        this.payeeLookup = payeeLookup;
        this.userService = userService;
    }

    /**
     * Requests transactions by default; investment links require investments and make
     * transactions optional.
     */
    @PostMapping("/create-link-token")
    public Map<String, String> createLinkToken(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean investments
    ) {
        User user = userService.getOrCreateUser(jwt);
        String linkToken = investments
                ? itemLinking.createInvestmentsLinkToken(user.getId())
                : itemLinking.createLinkToken(user.getId());
        return Map.of("link_token", linkToken);
    }

    @PostMapping("/items")
    public LinkResponse exchangePublicToken(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody ExchangePublicTokenRequest request
    ) {
        if (request.publicToken() == null || request.publicToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Public token is required");
        }

        User user = userService.getOrCreateUser(jwt);
        PlaidItemLinking.Linked linked = itemLinking.link(user.getId(), request.publicToken());
        return new LinkResponse(
                linked.itemId(), linked.sameInstitution().stream().map(ItemResponse::from).toList());
    }

    public record ExchangePublicTokenRequest(String publicToken) {
    }

    /** {@code sameInstitution} lists the user's other items at the linked item's institution. */
    public record LinkResponse(
            @JsonProperty("item_id") String itemId,
            @JsonProperty("same_institution") List<ItemResponse> sameInstitution
    ) {
    }

    @GetMapping("/items")
    public ItemsResponse getLinkedItems(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.getOrCreateUser(jwt);
        List<ItemResponse> items = plaidItemRepository
                .findAllByUserIdAndRemovedOnIsNullOrderByItemIdAsc(user.getId())
                .stream()
                .map(ItemResponse::from)
                .toList();

        return new ItemsResponse(items.stream().map(ItemResponse::itemId).toList(), items);
    }

    public record ItemsResponse(
            @JsonProperty("item_ids") List<String> itemIds,
            @JsonProperty("items") List<ItemResponse> items
    ) {
    }

    public record ItemResponse(
            @JsonProperty("item_id") String itemId,
            @JsonProperty("institution_name") String institutionName,
            @JsonProperty("investments") boolean investments,
            @JsonProperty("investments_available") Boolean investmentsAvailable
    ) {
        static ItemResponse from(PlaidItem item) {
            return new ItemResponse(item.getItemId(), item.getInstitutionName(), item.hasInvestments(),
                    item.getInvestmentsAvailable());
        }
    }

    /**
     * Adds investments to a linked item. If the holdings request fails, returns a link token for
     * Link update mode; call again once the user finishes it.
     */
    @PostMapping("/items/{itemId}/investments")
    public AddInvestmentsResponse addInvestments(@AuthenticationPrincipal Jwt jwt, @PathVariable String itemId) {
        User user = userService.getOrCreateUser(jwt);
        try {
            PlaidItemLinking.AddInvestments result = itemLinking.addInvestments(user.getId(), itemId);
            return new AddInvestmentsResponse(result.outcome().name().toLowerCase(), result.linkToken());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
        }
    }

    /** {@code outcome} is added, needs_consent (finish Link with the token) or not_offered. */
    public record AddInvestmentsResponse(
            @JsonProperty("outcome") String outcome,
            @JsonProperty("link_token") String linkToken
    ) {
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@AuthenticationPrincipal Jwt jwt, @PathVariable String itemId) {
        User user = userService.getOrCreateUser(jwt);
        try {
            itemLinking.remove(user.getId(), itemId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
        }
    }

    @GetMapping("/transactions")
    public TransactionsResponse getTransactions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(name = "account_id", required = false) String accountId
    ) {
        if (limit < 1 || limit > MAX_TRANSACTION_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_TRANSACTION_LIMIT);
        }
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must not be negative");
        }

        User user = userService.getOrCreateUser(jwt);
        Map<String, Integer> shares = trackedAccounts.shares(user.getId(), blankToNull(accountId));
        if (shares.isEmpty()) {
            return new TransactionsResponse(List.of(), 0);
        }
        Page<PlaidTransaction> found = transactionRepository
                .findRecent(user.getId(), shares.keySet(), PageRequest.of(page, limit));
        PayeeLookup.Payees payees = payeeLookup.forUser(user.getId());

        return new TransactionsResponse(
                found.stream()
                        .map(transaction -> TransactionResponse.from(
                                transaction, shares.get(transaction.getAccountId()), payees))
                        .toList(),
                found.getTotalElements());
    }

    /** Asks Plaid again for recurring streams; fetch them afterwards to see what changed. */
    @PostMapping("/transactions/recurring/sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void syncRecurringTransactions(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.getOrCreateUser(jwt);
        itemLinking.recheckRecurring(user.getId());
    }

    @GetMapping("/transactions/recurring")
    public Map<String, List<RecurringStreamResponse>> getRecurringTransactions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "account_id", required = false) String accountId,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        User user = userService.getOrCreateUser(jwt);

        Map<String, Integer> shares = trackedAccounts.shares(user.getId(), blankToNull(accountId));
        List<PlaidRecurringStream> stored = shares.isEmpty()
                ? List.of()
                : recurringStreamRepository.findAllByUserIdAndAccountIdIn(user.getId(), shares.keySet());

        Map<RecurringMerchant, RecurringPayee> payees = stored.isEmpty() ? Map.of() : recurringPayees.judged(user.getId());
        List<RecurringStreamResponse> streams = stored.stream()
                .map(stream -> RecurringStreamResponse.from(
                        stream, shares.get(stream.getAccountId()), payees.get(RecurringMerchant.of(stream))))
                .sorted(Comparator
                        .comparing(RecurringStreamResponse::nextDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(RecurringStreamResponse::lastDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int cap = resolveRecurringLimit(limit);
        if (streams.size() > cap) {
            streams = List.copyOf(streams.subList(0, cap));
        }

        return Map.of("streams", streams);
    }

    public record RecurringStreamResponse(
            @JsonProperty("stream_id") String streamId,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("merchant_name") String merchantName,
            @JsonProperty("description") String description,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("iso_currency_code") String isoCurrencyCode,
            @JsonProperty("frequency") String frequency,
            @JsonProperty("next_date") LocalDate nextDate,
            @JsonProperty("last_date") LocalDate lastDate,
            @JsonProperty("is_inflow") boolean isInflow,
            @JsonProperty("category") String category,
            @JsonProperty("category_detailed") String categoryDetailed,
            /** The user's share of the account's money, in percent; the amount is the whole stream's. */
            @JsonProperty("share_percent") int sharePercent,
            /** The plan line Jev put the payee's bills under; null for pay, and until it's judged. */
            @JsonProperty("plan_bucket") SpendingPlanBucket planBucket,
            @JsonProperty("plan_line") String planLine
    ) {
        static RecurringStreamResponse from(PlaidRecurringStream stream, int sharePercent, RecurringPayee payee) {
            return new RecurringStreamResponse(
                    stream.getStreamId(),
                    stream.getAccountId(),
                    stream.getMerchantName(),
                    stream.getDescription(),
                    stream.getAmount(),
                    stream.getIsoCurrencyCode(),
                    stream.getFrequency(),
                    stream.getNextDate(),
                    stream.getLastDate(),
                    stream.isInflow(),
                    stream.getCategory(),
                    stream.getCategoryDetailed(),
                    sharePercent,
                    payee == null ? null : payee.getPlanBucket(),
                    payee == null ? null : payee.getPlanLine()
            );
        }
    }

    /** One page of transactions, newest first, with how many there are across all pages. */
    public record TransactionsResponse(
            @JsonProperty("transactions") List<TransactionResponse> transactions,
            @JsonProperty("total") long total
    ) {
    }

    public record TransactionResponse(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("iso_currency_code") String isoCurrencyCode,
            @JsonProperty("date") LocalDate date,
            @JsonProperty("name") String name,
            @JsonProperty("merchant_name") String merchantName,
            @JsonProperty("logo_url") String logoUrl,
            @JsonProperty("pending") boolean pending,
            /** The Plaid primary category to show: the user's correction for the payee, else Plaid's. */
            @JsonProperty("category") String category,
            /** Null until sorting reaches the transaction. */
            @JsonProperty("bucket") Bucket bucket,
            /** The user's share of the account's money, in percent; the amount is the whole transaction's. */
            @JsonProperty("share_percent") int sharePercent,
            /** Who was paid or paid the user, as corrections and recurring answers are stored; may be null. */
            @JsonProperty("payee_key") String payeeKey,
            @JsonProperty("payee_kind") RecurringKind payeeKind,
            /** Whether the user set the payee's bucket, or its category, themselves. */
            @JsonProperty("bucket_corrected") boolean bucketCorrected,
            @JsonProperty("category_corrected") boolean categoryCorrected,
            /** Whether the payee repeats, as far as Plaid, the user or Jev have said; null when nobody has. */
            @JsonProperty("recurring") PayeeLookup.Recurring recurring
    ) {
        static TransactionResponse from(PlaidTransaction transaction, int sharePercent, PayeeLookup.Payees payees) {
            RecurringMerchant payee = RecurringMerchant.of(transaction);
            PayeeCorrection correction = payees.correction(transaction);
            return new TransactionResponse(
                    transaction.getTransactionId(),
                    transaction.getAccountId(),
                    transaction.getAmount(),
                    transaction.getIsoCurrencyCode(),
                    transaction.getTransactionDate(),
                    transaction.getName(),
                    transaction.getMerchantName(),
                    transaction.getLogoUrl(),
                    transaction.isPending(),
                    payees.category(transaction),
                    transaction.getBucket(),
                    sharePercent,
                    payee == null ? null : payee.key(),
                    payee == null ? null : payee.kind(),
                    correction != null && correction.getBucket() != null,
                    correction != null && correction.getCategory() != null,
                    payees.recurring(transaction)
            );
        }
    }

    @GetMapping("/spending/by-bucket")
    public SpendingByBucketResponse getSpendingByBucket(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "account_id", required = false) String accountId
    ) {
        User user = userService.getOrCreateUser(jwt);
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

        PayeeLookup.Payees payees = payeeLookup.forUser(user.getId());
        // Newest first from the query, and kept in that order within each category.
        Map<String, Map<String, List<Spending.Spent>>> byBucketAndCategory = new HashMap<>();
        for (Spending.Spent spent : spending.between(user.getId(), start, end, blankToNull(accountId))) {
            PlaidTransaction transaction = spent.transaction();
            String bucket = transaction.getBucket() == null ? UNSORTED : transaction.getBucket().name();
            // Plaid always assigns a category, but the column is nullable.
            String category = Objects.requireNonNullElse(payees.category(transaction), "UNCATEGORIZED");
            byBucketAndCategory
                    .computeIfAbsent(bucket, key -> new HashMap<>())
                    .computeIfAbsent(category, key -> new ArrayList<>())
                    .add(spent);
        }

        List<BucketSpend> buckets = BUCKET_ORDER.stream()
                .filter(byBucketAndCategory::containsKey)
                .map(bucket -> BucketSpend.of(bucket, byBucketAndCategory.get(bucket), payees))
                .filter(bucket -> !bucket.categories().isEmpty())
                .toList();
        BigDecimal total = buckets.stream()
                .map(BucketSpend::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SpendingByBucketResponse(start, end, total, buckets);
    }

    public record SpendingByBucketResponse(
            @JsonProperty("start") LocalDate start,
            @JsonProperty("end") LocalDate end,
            @JsonProperty("total") BigDecimal total,
            @JsonProperty("buckets") List<BucketSpend> buckets
    ) {
    }

    /** A bucket's total, broken down by Plaid primary category, largest first. */
    public record BucketSpend(
            @JsonProperty("bucket") String bucket,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("categories") List<CategorySpend> categories
    ) {
        static BucketSpend of(
                String bucket, Map<String, List<Spending.Spent>> transactionsByCategory, PayeeLookup.Payees payees) {
            List<CategorySpend> largestFirst = transactionsByCategory.entrySet().stream()
                    .map(entry -> CategorySpend.of(entry.getKey(), entry.getValue(), payees))
                    // Refunds can net a category to zero or below, which a pie chart cannot show.
                    .filter(category -> category.amount().signum() > 0)
                    .sorted(Comparator.comparing(CategorySpend::amount).reversed())
                    .toList();
            BigDecimal amount = largestFirst.stream()
                    .map(CategorySpend::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new BucketSpend(bucket, amount, largestFirst);
        }
    }

    /** A category's total and the transactions that make it up, newest first. */
    /** {@code amount} is the user's share of the category's transactions. */
    public record CategorySpend(
            @JsonProperty("category") String category,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("transactions") List<TransactionResponse> transactions
    ) {
        static CategorySpend of(String category, List<Spending.Spent> spent, PayeeLookup.Payees payees) {
            BigDecimal amount = spent.stream()
                    .map(Spending.Spent::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new CategorySpend(category, amount, spent.stream()
                    .map(each -> TransactionResponse.from(each.transaction(), each.sharePercent(), payees))
                    .toList());
        }
    }

    /** Lists the user's accounts that Plaid still returns, excluding dropped accounts. */
    @GetMapping("/accounts")
    public Map<String, List<AccountResponse>> getAccounts(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.getOrCreateUser(jwt);
        List<AccountResponse> accounts = accountRepository
                .findAllByUserIdAndDroppedOnIsNullOrderByNameAscAccountIdAsc(user.getId())
                .stream()
                .map(AccountResponse::from)
                .toList();

        return Map.of("accounts", accounts);
    }

    public record AccountResponse(
            @JsonProperty("account_id") String accountId,
            @JsonProperty("balances") BalanceResponse balances,
            @JsonProperty("mask") String mask,
            @JsonProperty("name") String name,
            @JsonProperty("official_name") String officialName,
            @JsonProperty("subtype") String subtype,
            @JsonProperty("type") String type,
            /** Whether spending can be tracked from the account: a bank account or credit card. */
            @JsonProperty("trackable") boolean trackable,
            @JsonProperty("tracks_spending") boolean tracksSpending,
            @JsonProperty("counts_in_net_worth") boolean countsInNetWorth,
            @JsonProperty("share_percent") int sharePercent
    ) {
        static AccountResponse from(PlaidAccount account) {
            return new AccountResponse(
                    account.getAccountId(),
                    new BalanceResponse(
                            account.getAvailableBalance(),
                            account.getCurrentBalance(),
                            account.getIsoCurrencyCode(),
                            account.getUnofficialCurrencyCode(),
                            account.getLimitAmount()
                    ),
                    account.getMask(),
                    account.getName(),
                    account.getOfficialName(),
                    account.getSubtype(),
                    account.getType(),
                    account.isTrackable(),
                    account.tracksSpending(),
                    account.countsInNetWorth(),
                    account.getSharePercent()
            );
        }
    }

    /** Sets whether an account counts toward spending and net worth, and the user's share of it. */
    @PutMapping("/accounts/{accountId}/tracking")
    public AccountResponse updateAccountTracking(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String accountId,
            @RequestBody AccountTrackingRequest request
    ) {
        User user = userService.getOrCreateUser(jwt);
        try {
            return AccountResponse.from(trackedAccounts.update(user.getId(), accountId,
                    request.tracksSpending(), request.countsInNetWorth(), request.sharePercent()));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Leaving out {@code counts_in_net_worth} keeps the current choice. */
    public record AccountTrackingRequest(
            @JsonProperty("tracks_spending") boolean tracksSpending,
            @JsonProperty("counts_in_net_worth") Boolean countsInNetWorth,
            @JsonProperty("share_percent") int sharePercent
    ) {
    }

    public record BalanceResponse(
            @JsonProperty("available") BigDecimal available,
            @JsonProperty("current") BigDecimal current,
            @JsonProperty("iso_currency_code") String isoCurrencyCode,
            @JsonProperty("unofficial_currency_code") String unofficialCurrencyCode,
            @JsonProperty("limit") BigDecimal limit
    ) {
    }

    private static int resolveRecurringLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_RECURRING_STREAMS;
        }
        return Math.min(MAX_RECURRING_STREAMS, Math.max(1, limit));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
