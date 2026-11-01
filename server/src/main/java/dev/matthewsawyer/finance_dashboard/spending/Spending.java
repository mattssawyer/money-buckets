package dev.matthewsawyer.finance_dashboard.spending;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.transactions.TransactionExclusions;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The user's spending: what left the accounts in view, other than pay, card payments and money
 * that only moved between their own accounts. A transfer between a shared account and another
 * tracked account is left out too; that money counts when it's spent from the shared account.
 */
@Service
public class Spending {

    /** How many days apart the two sides of a transfer between the user's accounts can post. */
    static final int TRANSFER_DAYS = 3;

    private static final String TRANSFER_OUT = "TRANSFER_OUT";

    private final PlaidTransactionRepository transactionRepository;
    private final TrackedAccounts trackedAccounts;
    private final TransactionExclusions exclusions;

    public Spending(
            PlaidTransactionRepository transactionRepository,
            TrackedAccounts trackedAccounts,
            TransactionExclusions exclusions
    ) {
        this.transactionRepository = transactionRepository;
        this.trackedAccounts = trackedAccounts;
        this.exclusions = exclusions;
    }

    /**
     * Spending between two dates, newest first, from every tracked account or only
     * {@code accountId} when one is given.
     */
    public List<Spent> between(UUID userId, LocalDate start, LocalDate end, String accountId) {
        Map<String, Integer> inView = trackedAccounts.shares(userId, accountId);
        if (inView.isEmpty()) {
            return List.of();
        }
        List<PlaidTransaction> found = transactionRepository.findSpending(
                userId, start, end, inView.keySet(), BucketSorting.NOT_PLAN_MONEY);
        Set<String> sharedTransfers = transfersWithSharedAccounts(userId, found, start, end);
        var counting = exclusions.forUser(userId);
        return found.stream()
                .filter(transaction -> !counting.excluded(transaction))
                .filter(transaction -> !sharedTransfers.contains(transaction.getTransactionId()))
                .map(transaction -> new Spent(transaction, inView.get(transaction.getAccountId())))
                .toList();
    }

    /** The key for spending that isn't sorted into a bucket yet. */
    public static final String UNSORTED = "UNSORTED";

    /**
     * Each bucket's total, keyed by bucket name or {@link #UNSORTED}: the sum of its categories'
     * totals, where a category that refunds bring to zero or below counts as nothing. The
     * spending chart totals buckets the same way.
     */
    public static Map<String, BigDecimal> bucketTotals(
            List<Spent> spent, Function<PlaidTransaction, String> category) {
        Map<String, Map<String, BigDecimal>> byBucketAndCategory = new HashMap<>();
        for (Spent each : spent) {
            PlaidTransaction transaction = each.transaction();
            String bucket = transaction.getBucket() == null ? UNSORTED : transaction.getBucket().name();
            String categoryName = Objects.requireNonNullElse(category.apply(transaction), "UNCATEGORIZED");
            byBucketAndCategory
                    .computeIfAbsent(bucket, key -> new HashMap<>())
                    .merge(categoryName, each.amount(), BigDecimal::add);
        }
        Map<String, BigDecimal> totals = new HashMap<>();
        byBucketAndCategory.forEach((bucket, categories) -> totals.put(bucket, categories.values().stream()
                .filter(amount -> amount.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
        return totals;
    }

    /** A transaction and how much of it is the user's, in percent. */
    public record Spent(PlaidTransaction transaction, int sharePercent) {

        /** The user's part of the transaction. */
        public BigDecimal amount() {
            return TrackedAccounts.share(transaction.getAmount(), sharePercent);
        }
    }

    /**
     * Transfers out of one tracked account that arrived in another, where either account is
     * shared. Each arrival pairs with one transfer out, so two equal transfers need two arrivals.
     */
    private Set<String> transfersWithSharedAccounts(
            UUID userId, List<PlaidTransaction> spending, LocalDate start, LocalDate end) {
        Map<String, Integer> tracked = trackedAccounts.shares(userId, null);
        if (tracked.values().stream().noneMatch(share -> share < 100)) {
            return Set.of();
        }
        List<PlaidTransaction> arrivals = new ArrayList<>(transactionRepository.findTransfersIn(
                userId, tracked.keySet(), start.minusDays(TRANSFER_DAYS), end.plusDays(TRANSFER_DAYS)));

        Set<String> matched = new HashSet<>();
        for (PlaidTransaction out : spending) {
            if (!TRANSFER_OUT.equals(out.getPersonalFinanceCategoryPrimary())
                    || !tracked.containsKey(out.getAccountId())) {
                continue;
            }
            for (Iterator<PlaidTransaction> candidates = arrivals.iterator(); candidates.hasNext(); ) {
                PlaidTransaction in = candidates.next();
                if (areTwoSides(out, in, tracked)) {
                    matched.add(out.getTransactionId());
                    candidates.remove();
                    break;
                }
            }
        }
        return matched;
    }

    private static boolean areTwoSides(PlaidTransaction out, PlaidTransaction in, Map<String, Integer> tracked) {
        // Plaid signs money out as positive and money in as negative.
        return !in.getAccountId().equals(out.getAccountId())
                && in.getAmount().negate().compareTo(out.getAmount()) == 0
                && Math.abs(ChronoUnit.DAYS.between(out.getTransactionDate(), in.getTransactionDate()))
                        <= TRANSFER_DAYS
                && (tracked.get(out.getAccountId()) < 100 || tracked.get(in.getAccountId()) < 100);
    }
}
