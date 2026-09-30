package dev.matthewsawyer.finance_dashboard.controller;

import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayees;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.plaid.PlaidItemLinking;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.spending.Spending;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidControllerTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    @Mock
    private PlaidItemLinking itemLinking;

    @Mock
    private PlaidItemRepository plaidItemRepository;

    @Mock
    private PlaidAccountRepository accountRepository;

    @Mock
    private PlaidTransactionRepository transactionRepository;

    @Mock
    private PlaidRecurringStreamRepository recurringStreamRepository;

    @Mock
    private RecurringPayees recurringPayees;

    @Mock
    private UserService userService;

    private PlaidController controller;
    private Jwt jwt;
    private User user;

    @BeforeEach
    void setUp() {
        TrackedAccounts trackedAccounts = new TrackedAccounts(accountRepository);
        controller = new PlaidController(
                itemLinking,
                plaidItemRepository,
                accountRepository,
                transactionRepository,
                recurringStreamRepository,
                trackedAccounts,
                new Spending(transactionRepository, trackedAccounts),
                recurringPayees,
                userService
        );
        jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("clerk-user")
                .build();
        user = new User("clerk-user");
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    @Test
    void createsLinkTokensForTheCurrentUser() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(itemLinking.createLinkToken(USER_ID)).thenReturn("link-token");

        assertEquals(Map.of("link_token", "link-token"), controller.createLinkToken(jwt, false));
    }

    @Test
    void createsInvestmentLinkTokensWhenAsked() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(itemLinking.createInvestmentsLinkToken(USER_ID)).thenReturn("investments-link-token");

        assertEquals(Map.of("link_token", "investments-link-token"), controller.createLinkToken(jwt, true));
    }

    @Test
    void rejectsBlankPublicToken() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.exchangePublicToken(
                        jwt,
                        new PlaidController.ExchangePublicTokenRequest(" ")
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(userService, itemLinking);
    }

    @Test
    void linksTheItemForTheCurrentUserAndListsItsInstitutionsOtherItems() {
        PlaidItem older = new PlaidItem("older-item", "encrypted-token", USER_ID);
        ReflectionTestUtils.setField(older, "institutionName", "Fidelity");
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(itemLinking.link(USER_ID, "public-token"))
                .thenReturn(new PlaidItemLinking.Linked("item-id", List.of(older)));

        PlaidController.LinkResponse result = controller.exchangePublicToken(
                jwt, new PlaidController.ExchangePublicTokenRequest("public-token"));

        assertEquals("item-id", result.itemId());
        assertEquals(List.of(new PlaidController.ItemResponse("older-item", "Fidelity", false, null)),
                result.sameInstitution());
    }

    @Test
    void listsTheCurrentUsersItemsThatAreNotRemoved() {
        PlaidItem brokerage = new PlaidItem("item-two", "encrypted-token-two", USER_ID);
        ReflectionTestUtils.setField(brokerage, "institutionName", "Fidelity");
        ReflectionTestUtils.setField(brokerage, "investments", true);
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidItemRepository.findAllByUserIdAndRemovedOnIsNullOrderByItemIdAsc(USER_ID)).thenReturn(List.of(
                new PlaidItem("item-one", "encrypted-token-one", USER_ID),
                brokerage
        ));

        PlaidController.ItemsResponse result = controller.getLinkedItems(jwt);

        assertEquals(List.of("item-one", "item-two"), result.itemIds());
        assertEquals(new PlaidController.ItemResponse("item-two", "Fidelity", true, null), result.items().get(1));
    }

    @Test
    void addsInvestmentsToAnItem() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(itemLinking.addInvestments(USER_ID, "item-id"))
                .thenReturn(new PlaidItemLinking.AddInvestments(PlaidItemLinking.AddInvestments.Outcome.ADDED, null));

        assertEquals(new PlaidController.AddInvestmentsResponse("added", null),
                controller.addInvestments(jwt, "item-id"));
    }

    @Test
    void returnsAnUpdateModeTokenWhenPlaidNeedsConsentForInvestments() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(itemLinking.addInvestments(USER_ID, "item-id"))
                .thenReturn(new PlaidItemLinking.AddInvestments(
                        PlaidItemLinking.AddInvestments.Outcome.NEEDS_CONSENT, "update-token"));

        assertEquals(new PlaidController.AddInvestmentsResponse("needs_consent", "update-token"),
                controller.addInvestments(jwt, "item-id"));
    }

    @Test
    void addingInvestmentsToAnotherUsersItemIsNotFound() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(itemLinking.addInvestments(USER_ID, "someone-elses"))
                .thenThrow(new NoSuchElementException("No item someone-elses"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.addInvestments(jwt, "someone-elses"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void removesAnItem() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);

        controller.removeItem(jwt, "item-id");

        verify(itemLinking).remove(USER_ID, "item-id");
    }

    @Test
    void removingAnotherUsersItemIsNotFound() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        doThrow(new NoSuchElementException("No item someone-elses"))
                .when(itemLinking).remove(USER_ID, "someone-elses");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.removeItem(jwt, "someone-elses"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void returnsEmptyItemsForUserWithoutConnections() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidItemRepository.findAllByUserIdAndRemovedOnIsNullOrderByItemIdAsc(USER_ID)).thenReturn(List.of());

        assertEquals(List.of(), controller.getLinkedItems(jwt).itemIds());
    }

    @Test
    void returnsAccountsThatAreNotDropped() {
        PlaidAccount stored = checkingAccount();
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.findAllByUserIdAndDroppedOnIsNullOrderByNameAscAccountIdAsc(USER_ID))
                .thenReturn(List.of(stored));

        List<PlaidController.AccountResponse> result = controller.getAccounts(jwt).get("accounts");

        assertEquals(1, result.size());
        assertEquals("checking", result.get(0).accountId());
        assertEquals("Checking", result.get(0).name());
        assertEquals(new BigDecimal("1250.50"), result.get(0).balances().current());
    }

    @Test
    void returnsRecentTransactionsForCurrentUser() {
        PlaidTransaction stored = new PlaidTransaction(
                "txn-1", "item-id", USER_ID, "account-1",
                new BigDecimal("12.34"), LocalDate.of(2026, 9, 1))
                .merchantName("Coffee Shop")
                .name("COFFEE SHOP")
                .isoCurrencyCode("USD")
                .personalFinanceCategory("FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE");
        ReflectionTestUtils.setField(stored, "bucket", Bucket.GUILT_FREE);

        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("account-1", 100));
        when(transactionRepository.findRecent(USER_ID, Set.of("account-1"), Pageable.ofSize(5)))
                .thenReturn(new PageImpl<>(List.of(stored), Pageable.ofSize(5), 1));

        PlaidController.TransactionsResponse response = controller.getTransactions(jwt, 5, 0, null);
        List<PlaidController.TransactionResponse> result = response.transactions();

        assertEquals(1, response.total());
        assertEquals(1, result.size());
        assertEquals("txn-1", result.get(0).transactionId());
        assertEquals("Coffee Shop", result.get(0).merchantName());
        assertEquals(LocalDate.of(2026, 9, 1), result.get(0).date());
        assertEquals("FOOD_AND_DRINK", result.get(0).category());
        assertEquals(Bucket.GUILT_FREE, result.get(0).bucket());
    }

    @Test
    void pagesThroughTransactionsWithTheTotalAcrossPages() {
        PlaidTransaction stored = new PlaidTransaction(
                "txn-51", "item-id", USER_ID, "account-1",
                new BigDecimal("8.00"), LocalDate.of(2026, 8, 2));
        PageRequest secondPage = PageRequest.of(1, 50);

        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("account-1", 100));
        when(transactionRepository.findRecent(USER_ID, Set.of("account-1"), secondPage))
                .thenReturn(new PageImpl<>(List.of(stored), secondPage, 51));

        PlaidController.TransactionsResponse response = controller.getTransactions(jwt, 50, 1, null);

        assertEquals(51, response.total());
        assertEquals("txn-51", response.transactions().get(0).transactionId());
        assertNull(response.transactions().get(0).bucket());
    }

    @Test
    void totalsSpendingByBucketForTheCurrentMonth() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100));
        when(transactionRepository.findSpending(eq(USER_ID), any(), any(), eq(Set.of("checking")), anyCollection()))
                .thenReturn(List.of(
                        spent("coffee", "12.50", "FOOD_AND_DRINK", Bucket.GUILT_FREE),
                        spent("groceries", "82.50", "FOOD_AND_DRINK", Bucket.FIXED_COSTS),
                        spent("rent", "1450.00", "RENT_AND_UTILITIES", Bucket.FIXED_COSTS),
                        spent("to-savings", "300.00", "TRANSFER_OUT", Bucket.SAVINGS)));

        PlaidController.SpendingByBucketResponse result = controller.getSpendingByBucket(jwt, null);

        LocalDate expectedStart = LocalDate.now().withDayOfMonth(1);
        assertEquals(expectedStart, result.start());
        assertEquals(expectedStart.withDayOfMonth(expectedStart.lengthOfMonth()), result.end());
        assertEquals(new BigDecimal("1845.00"), result.total());
        assertEquals(
                List.of("FIXED_COSTS", "GUILT_FREE", "SAVINGS"),
                result.buckets().stream().map(PlaidController.BucketSpend::bucket).toList());

        PlaidController.BucketSpend fixedCosts = result.buckets().get(0);
        assertEquals(new BigDecimal("1532.50"), fixedCosts.amount());
        assertEquals(
                List.of("RENT_AND_UTILITIES", "FOOD_AND_DRINK"),
                fixedCosts.categories().stream().map(PlaidController.CategorySpend::category).toList());
    }

    @Test
    void listsEachCategorysTransactionsNewestFirst() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100));
        when(transactionRepository.findSpending(eq(USER_ID), any(), any(), eq(Set.of("checking")), anyCollection()))
                .thenReturn(List.of(
                        spent("coffee-2", "5.00", "FOOD_AND_DRINK", Bucket.GUILT_FREE),
                        spent("coffee-1", "7.50", "FOOD_AND_DRINK", Bucket.GUILT_FREE)));

        PlaidController.SpendingByBucketResponse result = controller.getSpendingByBucket(jwt, null);

        PlaidController.CategorySpend food = result.buckets().get(0).categories().get(0);
        assertEquals(new BigDecimal("12.50"), food.amount());
        assertEquals(
                List.of("coffee-2", "coffee-1"),
                food.transactions().stream().map(PlaidController.TransactionResponse::transactionId).toList());
    }

    @Test
    void listsTransactionsNotSortedYetLast() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100));
        when(transactionRepository.findSpending(eq(USER_ID), any(), any(), eq(Set.of("checking")), anyCollection()))
                .thenReturn(List.of(
                        spent("flight", "400.00", "TRAVEL", null),
                        spent("brokerage", "500.00", "TRANSFER_OUT", Bucket.INVESTMENTS)));

        PlaidController.SpendingByBucketResponse result = controller.getSpendingByBucket(jwt, null);

        assertEquals(
                List.of("INVESTMENTS", "UNSORTED"),
                result.buckets().stream().map(PlaidController.BucketSpend::bucket).toList());
    }

    @Test
    void leavesPayAndCardPaymentsOutOfTheSpendingQuery() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100));
        when(transactionRepository.findSpending(eq(USER_ID), any(), any(), eq(Set.of("checking")), anyCollection()))
                .thenReturn(List.of());

        controller.getSpendingByBucket(jwt, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> excludedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(transactionRepository).findSpending(
                eq(USER_ID), any(), any(), eq(Set.of("checking")), excludedCaptor.capture());

        assertEquals(
                Set.of("INCOME", "TRANSFER_IN", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT"),
                Set.copyOf(excludedCaptor.getValue()));
    }

    @Test
    void dropsCategoriesRefundedBackToZero() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100));
        when(transactionRepository.findSpending(eq(USER_ID), any(), any(), eq(Set.of("checking")), anyCollection()))
                .thenReturn(List.of(
                        spent("jacket", "25.00", "GENERAL_MERCHANDISE", Bucket.GUILT_FREE),
                        spent("jacket-refund", "-25.00", "GENERAL_MERCHANDISE", Bucket.GUILT_FREE),
                        spent("pharmacy-refund", "-8.00", "MEDICAL", Bucket.FIXED_COSTS),
                        spent("hotel", "300.00", "TRAVEL", Bucket.GUILT_FREE)));

        PlaidController.SpendingByBucketResponse result = controller.getSpendingByBucket(jwt, null);

        assertEquals(
                List.of("GUILT_FREE"),
                result.buckets().stream().map(PlaidController.BucketSpend::bucket).toList());
        assertEquals(
                List.of("TRAVEL"),
                result.buckets().get(0).categories().stream().map(PlaidController.CategorySpend::category).toList());
        assertEquals(new BigDecimal("300.00"), result.total());
    }

    private static PlaidTransaction spent(String id, String amount, String category, Bucket bucket) {
        PlaidTransaction transaction = new PlaidTransaction(
                id, "item-id", USER_ID, "checking", new BigDecimal(amount), LocalDate.now())
                .personalFinanceCategory(category, null);
        ReflectionTestUtils.setField(transaction, "bucket", bucket);
        return transaction;
    }

    @Test
    void rejectsOutOfRangeTransactionLimit() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> controller.getTransactions(jwt, 101, 0, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(userService, transactionRepository);
    }

    @Test
    void rejectsNegativeTransactionPage() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> controller.getTransactions(jwt, 25, -1, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(userService, transactionRepository);
    }

    @Test
    void filtersRecentTransactionsToTheRequestedAccount() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.findByAccountIdAndUserId("account-1", USER_ID))
                .thenReturn(Optional.of(account("account-1", 100)));
        when(transactionRepository.findRecent(USER_ID, Set.of("account-1"), Pageable.ofSize(5)))
                .thenReturn(Page.empty());

        controller.getTransactions(jwt, 5, 0, "account-1");

        verify(transactionRepository).findRecent(USER_ID, Set.of("account-1"), Pageable.ofSize(5));
    }

    @Test
    void returnsStoredRecurringStreamsSoonestFirst() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100));
        when(recurringStreamRepository.findAllByUserIdAndAccountIdIn(USER_ID, Set.of("checking"))).thenReturn(List.of(
                storedStream("rent", "checking", "Landlord", new BigDecimal("1450.0"), "MONTHLY",
                        LocalDate.of(2026, 10, 1), false),
                storedStream("later", "checking", "Netflix", new BigDecimal("15.49"), "MONTHLY",
                        LocalDate.of(2026, 10, 12), false),
                storedStream("pay", "checking", "Payroll", new BigDecimal("-2400.0"), "BIWEEKLY",
                        LocalDate.of(2026, 9, 25), true)));
        RecurringPayee landlord = new RecurringPayee(USER_ID, new RecurringMerchant(RecurringKind.BILL, "landlord"));
        landlord.judged(null, null, SpendingPlanBucket.FIXED_COSTS, "Rent/mortgage", "state", Instant.now());
        when(recurringPayees.judged(USER_ID)).thenReturn(Map.of(landlord.payee(), landlord));

        List<PlaidController.RecurringStreamResponse> streams =
                controller.getRecurringTransactions(jwt, null, null).get("streams");

        assertEquals(List.of("pay", "rent", "later"), streams.stream()
                .map(PlaidController.RecurringStreamResponse::streamId)
                .toList());
        assertEquals(new BigDecimal("1450.0"), streams.get(1).amount());
        assertEquals("MONTHLY", streams.get(1).frequency());
        assertTrue(streams.get(0).isInflow());
        assertEquals("FOOD_AND_DRINK", streams.get(1).category());
        assertEquals("FOOD_AND_DRINK_GROCERIES", streams.get(1).categoryDetailed());
        assertEquals(SpendingPlanBucket.FIXED_COSTS, streams.get(1).planBucket());
        assertEquals("Rent/mortgage", streams.get(1).planLine());
        assertNull(streams.get(2).planLine(), "Netflix hasn't been judged yet");
    }

    @Test
    void filtersStoredRecurringStreamsToTheRequestedAccount() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.findByAccountIdAndUserId("account-1", USER_ID))
                .thenReturn(Optional.of(account("account-1", 100)));
        when(recurringStreamRepository.findAllByUserIdAndAccountIdIn(USER_ID, Set.of("account-1")))
                .thenReturn(List.of());

        controller.getRecurringTransactions(jwt, "account-1", null);

        verify(recurringStreamRepository).findAllByUserIdAndAccountIdIn(USER_ID, Set.of("account-1"));
    }

    @Test
    void returnsNoRecurringStreamsWhenNoAccountIsTracked() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);

        assertEquals(Map.of("streams", List.of()), controller.getRecurringTransactions(jwt, null, null));
        verifyNoInteractions(recurringStreamRepository);
    }

    @Test
    void capsRecurringStreamsAtTheRequestedLimit() {
        List<PlaidRecurringStream> stored = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            stored.add(storedStream(
                    "bill-" + i,
                    "checking",
                    "Bill " + i,
                    BigDecimal.valueOf(10.0 * i),
                    "MONTHLY",
                    LocalDate.of(2026, 10, i),
                    false));
        }
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100));
        when(recurringStreamRepository.findAllByUserIdAndAccountIdIn(USER_ID, Set.of("checking"))).thenReturn(stored);

        assertEquals(8, controller.getRecurringTransactions(jwt, null, null).get("streams").size());
        assertEquals(12, controller.getRecurringTransactions(jwt, null, 50).get("streams").size());
        assertEquals(1, controller.getRecurringTransactions(jwt, null, 1).get("streams").size());
    }

    private static PlaidRecurringStream storedStream(
            String streamId,
            String accountId,
            String merchant,
            BigDecimal amount,
            String frequency,
            LocalDate nextDate,
            boolean inflow
    ) {
        return new PlaidRecurringStream(
                streamId, "item-id", USER_ID, accountId, amount, frequency, inflow)
                .merchantName(merchant)
                .description(merchant.toUpperCase())
                .isoCurrencyCode("USD")
                .nextDate(nextDate)
                .lastDate(nextDate.minusMonths(1))
                .category("FOOD_AND_DRINK")
                .categoryDetailed("FOOD_AND_DRINK_GROCERIES");
    }

    @Test
    void filtersSpendingToTheRequestedAccount() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.findByAccountIdAndUserId("account-1", USER_ID))
                .thenReturn(Optional.of(account("account-1", 100)));
        when(transactionRepository.findSpending(
                eq(USER_ID), any(), any(), eq(Set.of("account-1")), anyCollection()))
                .thenReturn(List.of());

        controller.getSpendingByBucket(jwt, "account-1");

        verify(transactionRepository).findSpending(
                eq(USER_ID), any(), any(), eq(Set.of("account-1")), anyCollection());
    }

    @Test
    void countsASharedAccountsSpendingAtTheUsersShare() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100), account("joint", 50));
        when(transactionRepository.findSpending(
                eq(USER_ID), any(), any(), eq(Set.of("checking", "joint")), anyCollection()))
                .thenReturn(List.of(
                        spent("rent", "joint", "2000.00", "RENT_AND_UTILITIES", Bucket.FIXED_COSTS),
                        spent("coffee", "checking", "5.00", "FOOD_AND_DRINK", Bucket.GUILT_FREE)));

        PlaidController.SpendingByBucketResponse result = controller.getSpendingByBucket(jwt, null);

        assertEquals(new BigDecimal("1005.00"), result.total());
        PlaidController.CategorySpend rent = result.buckets().get(0).categories().get(0);
        assertEquals(new BigDecimal("1000.00"), rent.amount());
        // The transaction itself keeps its whole amount, with the share alongside.
        assertEquals(new BigDecimal("2000.00"), rent.transactions().get(0).amount());
        assertEquals(50, rent.transactions().get(0).sharePercent());
    }

    @Test
    void leavesOutMoneyMovedIntoASharedAccount() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100), account("joint", 50));
        LocalDate today = LocalDate.now();
        when(transactionRepository.findSpending(
                eq(USER_ID), any(), any(), eq(Set.of("checking", "joint")), anyCollection()))
                .thenReturn(List.of(
                        spent("to-joint", "checking", "600.00", "TRANSFER_OUT", Bucket.GUILT_FREE),
                        spent("groceries", "joint", "80.00", "FOOD_AND_DRINK", Bucket.FIXED_COSTS)));
        when(transactionRepository.findTransfersIn(
                eq(USER_ID), eq(Set.of("checking", "joint")), any(), any()))
                .thenReturn(List.of(arrival("from-checking", "joint", "-600.00", today.plusDays(1))));

        PlaidController.SpendingByBucketResponse result = controller.getSpendingByBucket(jwt, null);

        assertEquals(new BigDecimal("40.00"), result.total());
        assertEquals(List.of("FIXED_COSTS"),
                result.buckets().stream().map(PlaidController.BucketSpend::bucket).toList());
    }

    @Test
    void countsATransferThatDidNotArriveInASharedAccount() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100), account("joint", 50));
        LocalDate today = LocalDate.now();
        when(transactionRepository.findSpending(
                eq(USER_ID), any(), any(), eq(Set.of("checking", "joint")), anyCollection()))
                .thenReturn(List.of(
                        spent("to-friend", "checking", "600.00", "TRANSFER_OUT", Bucket.GUILT_FREE)));
        // A different amount, and the right amount but too many days later.
        when(transactionRepository.findTransfersIn(
                eq(USER_ID), eq(Set.of("checking", "joint")), any(), any()))
                .thenReturn(List.of(
                        arrival("roommate", "joint", "-650.00", today),
                        arrival("later", "joint", "-600.00", today.plusDays(4))));

        PlaidController.SpendingByBucketResponse result = controller.getSpendingByBucket(jwt, null);

        assertEquals(new BigDecimal("600.00"), result.total());
    }

    @Test
    void keepsMoneyMovedIntoSavingsWhenNoAccountIsShared() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        track(account("checking", 100), account("savings", 100));
        when(transactionRepository.findSpending(
                eq(USER_ID), any(), any(), eq(Set.of("checking", "savings")), anyCollection()))
                .thenReturn(List.of(
                        spent("to-savings", "checking", "300.00", "TRANSFER_OUT", Bucket.SAVINGS)));

        PlaidController.SpendingByBucketResponse result = controller.getSpendingByBucket(jwt, null);

        assertEquals(new BigDecimal("300.00"), result.total());
        verify(transactionRepository, never()).findTransfersIn(any(), any(), any(), any());
    }

    @Test
    void marksAnAccountAsShared() {
        PlaidAccount joint = account("joint", 100);
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.findByAccountIdAndUserId("joint", USER_ID)).thenReturn(Optional.of(joint));
        when(accountRepository.save(joint)).thenReturn(joint);

        PlaidController.AccountResponse result = controller.updateAccountTracking(
                jwt, "joint", new PlaidController.AccountTrackingRequest(true, null, 50));

        assertTrue(result.tracksSpending());
        assertEquals(50, result.sharePercent());
    }

    @Test
    void leavesAnAccountOutOfNetWorthAndKeepsThatWhenNotSaid() {
        PlaidAccount brokerage = account("brokerage", 100);
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.findByAccountIdAndUserId("brokerage", USER_ID)).thenReturn(Optional.of(brokerage));
        when(accountRepository.save(brokerage)).thenReturn(brokerage);

        PlaidController.AccountResponse left = controller.updateAccountTracking(
                jwt, "brokerage", new PlaidController.AccountTrackingRequest(false, false, 100));
        PlaidController.AccountResponse kept = controller.updateAccountTracking(
                jwt, "brokerage", new PlaidController.AccountTrackingRequest(false, null, 100));

        assertFalse(left.countsInNetWorth());
        assertFalse(kept.countsInNetWorth());
    }

    @Test
    void rejectsAShareOutsideOneToAHundredPercent() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.findByAccountIdAndUserId("joint", USER_ID))
                .thenReturn(Optional.of(account("joint", 100)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.updateAccountTracking(
                        jwt, "joint", new PlaidController.AccountTrackingRequest(true, null, 0)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void updatingAnotherUsersAccountIsNotFound() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(accountRepository.findByAccountIdAndUserId("other", USER_ID)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.updateAccountTracking(
                        jwt, "other", new PlaidController.AccountTrackingRequest(true, null, 50)));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    private void track(PlaidAccount... accounts) {
        when(accountRepository.findAllByUserIdAndTracksSpendingIsTrue(USER_ID)).thenReturn(List.of(accounts));
    }

    /** A tracked checking account with the given id and the user's share of it. */
    private static PlaidAccount account(String accountId, int sharePercent) {
        PlaidAccount account = new PlaidAccount(accountId, "item-id", USER_ID);
        account.updateSnapshot(accountId, null, null, "depository", "checking", null, null, null, "USD", null);
        account.updateTracking(true, true, sharePercent);
        return account;
    }

    private static PlaidTransaction spent(
            String id, String accountId, String amount, String category, Bucket bucket) {
        PlaidTransaction transaction = new PlaidTransaction(
                id, "item-id", USER_ID, accountId, new BigDecimal(amount), LocalDate.now())
                .personalFinanceCategory(category, null);
        ReflectionTestUtils.setField(transaction, "bucket", bucket);
        return transaction;
    }

    private static PlaidTransaction arrival(String id, String accountId, String amount, LocalDate day) {
        return new PlaidTransaction(id, "item-id", USER_ID, accountId, new BigDecimal(amount), day)
                .personalFinanceCategory("TRANSFER_IN", null);
    }

    private static PlaidAccount checkingAccount() {
        PlaidAccount account = new PlaidAccount("checking", "item-id", USER_ID);
        account.updateSnapshot(
                "Checking",
                null,
                "1234",
                "depository",
                "checking",
                new BigDecimal("1200.00"),
                new BigDecimal("1250.50"),
                null,
                "USD",
                null
        );
        return account;
    }
}
