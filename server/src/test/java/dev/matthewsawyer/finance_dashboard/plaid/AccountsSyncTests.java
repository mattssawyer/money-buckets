package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountSubtype;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.Institution;
import com.plaid.client.model.InstitutionsGetByIdRequest;
import com.plaid.client.model.InstitutionsGetByIdResponse;
import com.plaid.client.model.Item;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.AccountDrop;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.AccountDropRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class AccountsSyncTests {

    private static final String ITEM_ID = "accounts-sync-item";
    private static final String INSTITUTION_ID = "ins_fidelity";
    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate TUE = MON.plusDays(1);

    @MockitoBean private PlaidApi plaidApi;

    @Autowired private AccountsSync accountsSync;
    @Autowired private PlaidTokenEncryption tokenEncryption;
    @Autowired private PlaidItemRepository items;
    @Autowired private PlaidAccountRepository accounts;
    @Autowired private AccountDropRepository drops;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private PlaidItem item;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = users.saveAndFlush(new User("accounts-sync-test-user")).getId();
        item = items.saveAndFlush(new PlaidItem(
                ITEM_ID, tokenEncryption.encrypt("access-token", userId, ITEM_ID), userId));
    }

    @Test
    void storesEveryAccountPlaidReturnsIncludingInvestmentAccounts() throws IOException {
        stubAccounts(List.of(Products.TRANSACTIONS),
                account("checking", AccountType.DEPOSITORY, 2500.0),
                account("ira", AccountType.INVESTMENT, 41000.25));

        accountsSync.sync(item, MON);

        PlaidAccount ira = stored("ira");
        assertEquals("investment", ira.getType());
        assertEquals(0, new BigDecimal("41000.25").compareTo(ira.getCurrentBalance()));
        assertEquals(List.of("checking", "ira"), storedIds());
    }

    @Test
    void tracksSpendingFromNewCheckingAccountsAndCardsButNotSavingsOrInvestments() throws IOException {
        stubAccounts(List.of(Products.TRANSACTIONS),
                account("checking", AccountType.DEPOSITORY, 2500.0).subtype(AccountSubtype.CHECKING),
                account("card", AccountType.CREDIT, 300.0).subtype(AccountSubtype.CREDIT_CARD),
                account("savings", AccountType.DEPOSITORY, 9000.0).subtype(AccountSubtype.SAVINGS),
                account("ira", AccountType.INVESTMENT, 41000.0).subtype(AccountSubtype.IRA));

        accountsSync.sync(item, MON);

        assertTrue(stored("checking").tracksSpending());
        assertTrue(stored("card").tracksSpending());
        assertFalse(stored("savings").tracksSpending());
        assertFalse(stored("ira").tracksSpending());
        assertEquals(100, stored("checking").getSharePercent());
    }

    @Test
    void keepsTheUsersTrackingChoicesWhenPlaidRefreshesAnAccount() throws IOException {
        stubAccounts(List.of(Products.TRANSACTIONS),
                account("joint", AccountType.DEPOSITORY, 2500.0).subtype(AccountSubtype.CHECKING),
                account("savings", AccountType.DEPOSITORY, 9000.0).subtype(AccountSubtype.SAVINGS));
        accountsSync.sync(item, MON);
        PlaidAccount joint = stored("joint");
        joint.updateTracking(true, 50);
        accounts.save(joint);
        PlaidAccount savings = stored("savings");
        savings.updateTracking(true, 100);
        accounts.save(savings);

        accountsSync.sync(item, TUE);

        assertEquals(50, stored("joint").getSharePercent());
        assertTrue(stored("savings").tracksSpending());
    }

    @Test
    void dropsStoredAccountsPlaidNoLongerReturns() throws IOException {
        stubAccounts(List.of(Products.TRANSACTIONS),
                account("checking", AccountType.DEPOSITORY, 2500.0),
                account("ira", AccountType.INVESTMENT, 41000.0));
        accountsSync.sync(item, MON);

        stubAccounts(List.of(Products.TRANSACTIONS), account("checking", AccountType.DEPOSITORY, 2400.0));
        accountsSync.sync(item, TUE);

        assertEquals(TUE, stored("ira").getDroppedOn());
        assertNull(stored("checking").getDroppedOn());
    }

    @Test
    void restoresADroppedAccountPlaidReturnsAgain() throws IOException {
        stubAccounts(List.of(Products.TRANSACTIONS), account("ira", AccountType.INVESTMENT, 41000.0));
        accountsSync.sync(item, MON);
        stubAccounts(List.of(Products.TRANSACTIONS));
        accountsSync.sync(item, MON);

        stubAccounts(List.of(Products.TRANSACTIONS), account("ira", AccountType.INVESTMENT, 42000.0));
        accountsSync.sync(item, TUE);

        assertFalse(stored("ira").isDropped());
        AccountDrop drop = drops.findAllByUserId(userId).get(0);
        assertEquals("ira", drop.getAccountId());
        assertEquals(MON, drop.getDroppedOn());
        assertEquals(TUE, drop.getRestoredOn());
    }

    @Test
    void reportsWhetherTheItemHasTheTransactionsProduct() throws IOException {
        stubAccounts(List.of(Products.INVESTMENTS), account("401k", AccountType.INVESTMENT, 90000.0));
        assertFalse(accountsSync.sync(item, MON));

        stubAccounts(List.of(Products.INVESTMENTS, Products.TRANSACTIONS),
                account("401k", AccountType.INVESTMENT, 90000.0));
        assertTrue(accountsSync.sync(item, MON));
    }

    @Test
    void storesTheItemsInstitutionAndWhetherItHasInvestments() throws IOException {
        stubAccounts(List.of(Products.INVESTMENTS, Products.TRANSACTIONS));
        stubInstitution("Fidelity");

        accountsSync.sync(item, MON);

        PlaidItem stored = storedItem();
        assertEquals(INSTITUTION_ID, stored.getInstitutionId());
        assertEquals("Fidelity", stored.getInstitutionName());
        assertTrue(stored.hasInvestments());
        assertTrue(stored.getInvestmentsAvailable());
    }

    @Test
    void recordsWhetherTheInstitutionOffersInvestments() throws IOException {
        stubAccounts(List.of(Products.TRANSACTIONS), List.of(Products.INVESTMENTS));
        accountsSync.sync(item, MON);
        assertTrue(storedItem().getInvestmentsAvailable());

        stubAccounts(List.of(Products.TRANSACTIONS), List.of(Products.AUTH));
        accountsSync.sync(storedItem(), TUE);
        assertFalse(storedItem().getInvestmentsAvailable());
    }

    @Test
    void looksUpTheInstitutionNameOnlyOnce() throws IOException {
        stubAccounts(List.of(Products.TRANSACTIONS));
        stubInstitution("Fidelity");
        accountsSync.sync(item, MON);

        accountsSync.sync(storedItem(), TUE);

        verify(plaidApi, times(1)).institutionsGetById(any());
    }

    @Test
    void stillSyncsAccountsWhenTheInstitutionLookupFails() throws IOException {
        stubAccounts(List.of(Products.TRANSACTIONS), account("checking", AccountType.DEPOSITORY, 2500.0));

        accountsSync.sync(item, MON);

        assertEquals(List.of("checking"), storedIds());
        assertNull(storedItem().getInstitutionName());
    }

    @Test
    void dropsEveryAccountOfARemovedItem() throws IOException {
        stubAccounts(List.of(Products.TRANSACTIONS),
                account("checking", AccountType.DEPOSITORY, 2500.0),
                account("ira", AccountType.INVESTMENT, 41000.0));
        accountsSync.sync(item, MON);

        accountsSync.dropAll(ITEM_ID, TUE);

        assertEquals(TUE, stored("checking").getDroppedOn());
        assertEquals(TUE, stored("ira").getDroppedOn());
    }

    private PlaidItem storedItem() {
        entityManager.flush();
        entityManager.clear();
        return items.findById(ITEM_ID).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private void stubInstitution(String name) throws IOException {
        Call<InstitutionsGetByIdResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(new InstitutionsGetByIdResponse()
                .institution(new Institution().institutionId(INSTITUTION_ID).name(name))));
        when(plaidApi.institutionsGetById(any(InstitutionsGetByIdRequest.class))).thenReturn(call);
    }

    private PlaidAccount stored(String accountId) {
        entityManager.flush();
        entityManager.clear();
        return accounts.findById(accountId).orElseThrow();
    }

    private List<String> storedIds() {
        return accounts.findAllByItemId(ITEM_ID).stream()
                .map(PlaidAccount::getAccountId)
                .sorted()
                .toList();
    }

    private static AccountBase account(String id, AccountType type, double current) {
        return new AccountBase()
                .accountId(id)
                .name(id)
                .type(type)
                .balances(new AccountBalance().current(current).isoCurrencyCode("USD"));
    }

    @SuppressWarnings("unchecked")
    private void stubAccounts(List<Products> products, AccountBase... plaidAccounts) throws IOException {
        stubAccounts(products, List.of(), plaidAccounts);
    }

    @SuppressWarnings("unchecked")
    private void stubAccounts(List<Products> products, List<Products> available, AccountBase... plaidAccounts)
            throws IOException {
        Call<AccountsGetResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(new AccountsGetResponse()
                .accounts(Arrays.asList(plaidAccounts))
                .item(new Item().itemId(ITEM_ID).institutionId(INSTITUTION_ID).products(products)
                        .availableProducts(available))));
        when(plaidApi.accountsGet(any(AccountsGetRequest.class))).thenReturn(call);
    }
}
