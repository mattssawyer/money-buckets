package dev.matthewsawyer.finance_dashboard.spending;

import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Which of a user's accounts spending is counted from, and how much of each is theirs. A shared
 * account, such as one the user pays household bills from with someone they live with, counts
 * at the user's share.
 */
@Service
public class TrackedAccounts {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PlaidAccountRepository accountRepository;

    public TrackedAccounts(PlaidAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * The accounts a view covers, each with the user's share in percent: every tracked account,
     * or only {@code accountId} when one is given. Someone else's account gives an empty view.
     */
    public Map<String, Integer> shares(UUID userId, String accountId) {
        List<PlaidAccount> accounts = accountId == null
                ? accountRepository.findAllByUserIdAndTracksSpendingIsTrue(userId)
                : accountRepository.findByAccountIdAndUserId(accountId, userId).stream().toList();
        Map<String, Integer> shares = new LinkedHashMap<>();
        for (PlaidAccount account : accounts) {
            shares.put(account.getAccountId(), account.getSharePercent());
        }
        return shares;
    }

    /**
     * Stores whether the user tracks an account's spending and counts it in net worth, and their
     * share of it. A null {@code countsInNetWorth} keeps the current choice.
     *
     * @throws NoSuchElementException when the user has no such account
     * @throws IllegalArgumentException when the account can't be tracked or the share isn't 1–100
     */
    @Transactional
    public PlaidAccount update(
            UUID userId, String accountId, boolean tracksSpending, Boolean countsInNetWorth, int sharePercent) {
        PlaidAccount account = accountRepository.findByAccountIdAndUserId(accountId, userId)
                .orElseThrow(() -> new NoSuchElementException("No account " + accountId));
        account.updateTracking(tracksSpending,
                countsInNetWorth == null ? account.countsInNetWorth() : countsInNetWorth, sharePercent);
        return accountRepository.save(account);
    }

    /** The user's part of {@code amount}, to the cent; the whole amount when it isn't shared. */
    public static BigDecimal share(BigDecimal amount, int sharePercent) {
        if (sharePercent == 100) {
            return amount;
        }
        return amount.multiply(BigDecimal.valueOf(sharePercent)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
