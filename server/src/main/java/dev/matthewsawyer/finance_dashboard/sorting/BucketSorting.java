package dev.matthewsawyer.finance_dashboard.sorting;

import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.SpendingPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Keeps each transaction's bucket current, and asks whether transactions repeat so recurring
 * candidates can be found. Sorting asks TypeSafe about every transaction, so it runs off the
 * caller's thread, and its jobs run one at a time so an older job can never overwrite a newer
 * one's answers.
 *
 * <p>Failures leave transactions unsorted rather than guessing; the next sort retries them.
 */
@Service
public class BucketSorting {

    private static final Logger log = LoggerFactory.getLogger(BucketSorting.class);

    /**
     * Plaid categories that are never plan money: pay and other money coming in, and card
     * payments, whose purchases already count on the card itself. Matched against both the
     * primary and the detailed category.
     */
    public static final Set<String> NOT_PLAN_MONEY =
            Set.of("INCOME", "TRANSFER_IN", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT");

    private final PlaidTransactionRepository transactionRepository;
    private final PlaidRecurringStreamRepository streamRepository;
    private final SpendingPlanService planService;
    private final BucketClassifier classifier;
    private final RecurringJudge recurringJudge;
    private final Executor jobExecutor;
    private final Executor classifyExecutor;

    BucketSorting(
            PlaidTransactionRepository transactionRepository,
            PlaidRecurringStreamRepository streamRepository,
            SpendingPlanService planService,
            BucketClassifier classifier,
            RecurringJudge recurringJudge,
            @Qualifier("sortingJobExecutor") Executor jobExecutor,
            @Qualifier("sortingClassifyExecutor") Executor classifyExecutor
    ) {
        this.transactionRepository = transactionRepository;
        this.streamRepository = streamRepository;
        this.planService = planService;
        this.classifier = classifier;
        this.recurringJudge = recurringJudge;
        this.jobExecutor = jobExecutor;
        this.classifyExecutor = classifyExecutor;
    }

    /**
     * Judges everyone's history once whether it repeats, such as when recurring judgments first
     * ship; after that, new transactions are judged as they sync.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void judgeHistory() {
        if (!classifier.isAvailable()) {
            return;
        }
        for (UUID userId : transactionRepository.findUsersWithTransactionsToJudge(NOT_PLAN_MONEY)) {
            sortLater(userId);
        }
    }

    /**
     * Queues sorting of the user's transactions that don't have a bucket yet, then asks whether
     * their pay and spending repeat.
     */
    public void sortLater(UUID userId) {
        queue(userId, () -> run(userId, false));
    }

    /**
     * Re-sorts all of the user's transactions if the save changed their plan lines, since the
     * lines decide cases like whether a subscription is a fixed cost. Other plan changes, such as
     * take-home pay, don't affect sorting.
     */
    public void planSaved(UUID userId, PlanLines linesBefore) {
        queue(userId, () -> {
            if (!planLines(userId).equals(linesBefore)) {
                run(userId, true);
            }
        });
    }

    // A full queue shouldn't fail the sync or save that asked; the next sort catches up.
    private void queue(UUID userId, Runnable job) {
        try {
            jobExecutor.execute(job);
        } catch (RejectedExecutionException e) {
            log.warn("Sorting queue is full; skipping a sort for user {}", userId);
        }
    }

    private void run(UUID userId, boolean includeSorted) {
        try {
            sort(userId, includeSorted);
        } catch (RuntimeException e) {
            log.warn("Sorting failed for user {}", userId, e);
        }
    }

    private void sort(UUID userId, boolean includeSorted) {
        if (!classifier.isAvailable()) {
            log.info("Skipping sorting for user {}: no TypeSafe API key is set", userId);
            return;
        }

        PlanLines plan = planLines(userId);
        Set<AccountMerchant> detectedByPlaid = detectedByPlaid(userId);
        ChargesByPayee chargesByPayee = new ChargesByPayee(transactionRepository.findMoneyOut(userId));
        askEach(userId, "Sorted",
                transactionRepository.findToSort(userId, includeSorted, NOT_PLAN_MONEY),
                transaction -> classifier.classify(
                        transaction, plan,
                        transaction.getAmount().signum() > 0 && judgesRecurring(transaction, detectedByPlaid)
                                ? chargesByPayee.otherThan(transaction)
                                : null),
                (transaction, sorted) -> sorted.recurring() != null
                        ? transactionRepository.updateSorted(
                                transaction.getTransactionId(), transaction.getUpdatedAt(), sorted.bucket(),
                                probability(sorted.recurring()), sorted.recurring().usualFrequency(), Instant.now())
                        : sorted.bucket() == transaction.getBucket()
                                ? 0
                                : transactionRepository.updateBucket(
                                        transaction.getTransactionId(), transaction.getUpdatedAt(), sorted.bucket()));

        // Pay isn't sorted, and money out sorted before recurring judgments existed hasn't been
        // asked whether it repeats; both are asked here.
        askEach(userId, "Judged whether they repeat",
                transactionRepository.findToJudge(userId, NOT_PLAN_MONEY).stream()
                        .filter(transaction -> judgesRecurring(transaction, detectedByPlaid))
                        .toList(),
                transaction -> recurringJudge.judge(transaction, chargesByPayee.otherThan(transaction)),
                (transaction, judgment) -> transactionRepository.updateRecurring(
                        transaction.getTransactionId(), transaction.getUpdatedAt(),
                        probability(judgment), judgment.usualFrequency(), Instant.now()));
    }

    /**
     * Asks about every transaction in parallel and stores each answer as it's read, skipping
     * the ones whose question failed; the next job asks them again.
     */
    private <A> void askEach(
            UUID userId,
            String done,
            List<PlaidTransaction> transactions,
            Function<PlaidTransaction, A> ask,
            BiFunction<PlaidTransaction, A, Integer> store
    ) {
        if (transactions.isEmpty()) {
            return;
        }
        List<CompletableFuture<A>> answers = transactions.stream()
                .map(transaction -> CompletableFuture.supplyAsync(() -> ask.apply(transaction), classifyExecutor))
                .toList();

        int stored = 0;
        int failed = 0;
        RuntimeException firstFailure = null;
        for (int i = 0; i < transactions.size(); i++) {
            A answer;
            try {
                answer = answers.get(i).join();
            } catch (CompletionException e) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
                continue;
            }
            stored += store.apply(transactions.get(i), answer);
        }

        log.info("{} {} transactions for user {}: {} stored, {} failed",
                done, transactions.size(), userId, stored, failed);
        if (firstFailure != null) {
            log.warn("First failure for user {}", userId, firstFailure);
        }
    }

    /**
     * Whether to ask if a transaction repeats: not when it's already been asked, such as when a
     * plan save sorts it again, and not when Plaid already detects its merchant repeating in the
     * same account, since Plaid's stream wins over any candidate. A transaction with no merchant
     * or description can't become a candidate either. Transactions skipped here stay unjudged, so
     * they're asked about if Plaid later stops detecting the merchant.
     */
    private static boolean judgesRecurring(PlaidTransaction transaction, Set<AccountMerchant> detectedByPlaid) {
        if (transaction.getRecurringJudgedAt() != null) {
            return false;
        }
        RecurringMerchant merchant = RecurringMerchant.of(transaction);
        return merchant != null
                && !detectedByPlaid.contains(new AccountMerchant(transaction.getAccountId(), merchant));
    }

    private Set<AccountMerchant> detectedByPlaid(UUID userId) {
        Set<AccountMerchant> detected = new HashSet<>();
        for (PlaidRecurringStream stream : streamRepository.findAllByUserId(userId)) {
            RecurringMerchant merchant = RecurringMerchant.of(stream);
            if (merchant != null) {
                detected.add(new AccountMerchant(stream.getAccountId(), merchant));
            }
        }
        return detected;
    }

    private record AccountMerchant(String accountId, RecurringMerchant merchant) {
    }

    /** The user's money out grouped by payee, in every account, so a move between accounts keeps its history. */
    private static final class ChargesByPayee {

        // Enough to show a monthly bill's pattern over a year without growing every request.
        private static final int MAX_OTHER_CHARGES = 12;

        private final Map<RecurringMerchant, List<PlaidTransaction>> byPayee = new HashMap<>();

        ChargesByPayee(List<PlaidTransaction> moneyOut) {
            for (PlaidTransaction charge : moneyOut) {
                RecurringMerchant payee = RecurringMerchant.of(charge);
                if (payee != null) {
                    byPayee.computeIfAbsent(payee, key -> new ArrayList<>()).add(charge);
                }
            }
        }

        /** The payee's most recent other charges, newest first; empty for pay, which has no payee. */
        List<PlaidTransaction> otherThan(PlaidTransaction transaction) {
            RecurringMerchant payee = RecurringMerchant.of(transaction);
            return byPayee.getOrDefault(payee, List.of()).stream()
                    .filter(charge -> !charge.getTransactionId().equals(transaction.getTransactionId()))
                    .limit(MAX_OTHER_CHARGES)
                    .toList();
        }
    }

    private static BigDecimal probability(RecurringJudge.RecurringJudgment judgment) {
        return BigDecimal.valueOf(judgment.probability()).setScale(4, RoundingMode.HALF_UP);
    }

    private PlanLines planLines(UUID userId) {
        return planService.find(userId, PlanLines::of).orElse(PlanLines.NONE);
    }
}
