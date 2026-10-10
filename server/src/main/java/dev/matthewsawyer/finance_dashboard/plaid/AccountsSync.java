package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.CountryCode;
import com.plaid.client.model.InstitutionsGetByIdRequest;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.AccountDrop;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.repository.AccountDropRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Brings an item's stored accounts and balances up to date from Plaid's full list of the item's
 * accounts, and drops stored accounts Plaid no longer returns. Also keeps the item's institution,
 * whether it has the investments product, and whether its institution offers it.
 *
 * <p>This is the only place accounts are written: transactions sync pages only list accounts
 * with transactions in that page, and never investment accounts.
 */
@Component
class AccountsSync {

    private static final Logger log = LoggerFactory.getLogger(AccountsSync.class);

    private final PlaidApi plaidApi;
    private final PlaidItemRepository itemRepository;
    private final PlaidAccountRepository accountRepository;
    private final AccountDropRepository dropRepository;
    private final PlaidTokenEncryption tokenEncryption;
    private final TransactionTemplate transactionTemplate;

    AccountsSync(
            PlaidApi plaidApi,
            PlaidItemRepository itemRepository,
            PlaidAccountRepository accountRepository,
            AccountDropRepository dropRepository,
            PlaidTokenEncryption tokenEncryption,
            TransactionTemplate transactionTemplate
    ) {
        this.plaidApi = plaidApi;
        this.itemRepository = itemRepository;
        this.accountRepository = accountRepository;
        this.dropRepository = dropRepository;
        this.tokenEncryption = tokenEncryption;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Stores every account Plaid returns for the item and drops the rest as of {@code today}.
     * A returning account is restored and its completed drop interval is kept for history. Returns
     * whether the item has the transactions product. Failure to look up the institution name
     * leaves it null without stopping the account sync.
     *
     * @throws PlaidRequestException when Plaid cannot list the item's accounts, or lists them
     *         but says the item needs a new login; then nothing is stored
     */
    boolean sync(PlaidItem item, LocalDate today) {
        String accessToken = tokenEncryption.decrypt(
                item.getEncryptedAccessToken(), item.getUserId(), item.getItemId());
        AccountsGetResponse response = PlaidCalls.execute(
                plaidApi.accountsGet(new AccountsGetRequest().accessToken(accessToken)), "accounts get");
        // Plaid can answer with what it last saw while reporting the item's error alongside.
        if (response.getItem() != null && response.getItem().getError() != null
                && PlaidRequestException.ITEM_LOGIN_REQUIRED.equals(response.getItem().getError().getErrorCode())) {
            throw new PlaidRequestException("Plaid accounts get reported an item error",
                    PlaidRequestException.ITEM_LOGIN_REQUIRED);
        }
        List<AccountBase> plaidAccounts = Objects.requireNonNullElse(response.getAccounts(), List.of());
        List<Products> products = response.getItem() == null || response.getItem().getProducts() == null
                ? List.of() : response.getItem().getProducts();
        // Products the institution offers that the item doesn't have yet.
        List<Products> available = response.getItem() == null || response.getItem().getAvailableProducts() == null
                ? List.of() : response.getItem().getAvailableProducts();
        String institutionId = response.getItem() == null ? null : response.getItem().getInstitutionId();
        String institutionName = Objects.equals(institutionId, item.getInstitutionId())
                && item.getInstitutionName() != null
                ? item.getInstitutionName() : institutionName(institutionId);

        transactionTemplate.executeWithoutResult(status -> {
            Map<String, PlaidAccount> stored = accountRepository.findAllByItemId(item.getItemId()).stream()
                    .collect(Collectors.toMap(PlaidAccount::getAccountId, Function.identity()));

            for (AccountBase plaidAccount : plaidAccounts) {
                PlaidAccount account = stored.remove(plaidAccount.getAccountId());
                boolean isNew = account == null;
                if (isNew) {
                    account = new PlaidAccount(plaidAccount.getAccountId(), item.getItemId(), item.getUserId());
                }
                update(account, plaidAccount);
                if (isNew) {
                    account.trackByDefault();
                }
                if (account.isDropped()) {
                    // Keep the finished drop, so history still leaves the account out for it.
                    dropRepository.save(new AccountDrop(
                            account.getAccountId(), account.getUserId(), account.getDroppedOn(), today));
                    account.restore();
                }
                accountRepository.save(account);
            }

            for (PlaidAccount missing : stored.values()) {
                missing.drop(today);
                accountRepository.save(missing);
            }

            itemRepository.updateDetails(item.getItemId(), institutionId, institutionName,
                    products.contains(Products.INVESTMENTS),
                    products.contains(Products.INVESTMENTS) || available.contains(Products.INVESTMENTS));
        });

        return products.contains(Products.TRANSACTIONS);
    }

    /** Drops every account of an item the user removed, as of {@code today}. */
    void dropAll(String itemId, LocalDate today) {
        transactionTemplate.executeWithoutResult(status -> {
            for (PlaidAccount account : accountRepository.findAllByItemId(itemId)) {
                account.drop(today);
                accountRepository.save(account);
            }
        });
    }

    /** The institution's display name, or null when Plaid can't say; the next sync tries again. */
    private String institutionName(String institutionId) {
        if (institutionId == null) {
            return null;
        }
        try {
            return PlaidCalls.execute(plaidApi.institutionsGetById(new InstitutionsGetByIdRequest()
                            .institutionId(institutionId)
                            .countryCodes(List.of(CountryCode.US))), "institution get")
                    .getInstitution().getName();
        } catch (RuntimeException e) {
            log.warn("Could not look up institution {}", institutionId, e);
            return null;
        }
    }

    private static void update(PlaidAccount account, AccountBase plaidAccount) {
        AccountBalance balances = plaidAccount.getBalances();
        account.updateSnapshot(
                Objects.requireNonNullElse(plaidAccount.getName(), "Account"),
                plaidAccount.getOfficialName(),
                plaidAccount.getMask(),
                plaidAccount.getType() == null ? "other" : plaidAccount.getType().getValue(),
                plaidAccount.getSubtype() == null ? null : plaidAccount.getSubtype().getValue(),
                money(balances == null ? null : balances.getAvailable()),
                money(balances == null ? null : balances.getCurrent()),
                money(balances == null ? null : balances.getLimit()),
                balances == null ? null : balances.getIsoCurrencyCode(),
                balances == null ? null : balances.getUnofficialCurrencyCode()
        );
    }

    private static BigDecimal money(Double amount) {
        return amount == null ? null : BigDecimal.valueOf(amount);
    }
}
