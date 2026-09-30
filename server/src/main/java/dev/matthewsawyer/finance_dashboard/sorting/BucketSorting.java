package dev.matthewsawyer.finance_dashboard.sorting;

import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PayeeCorrection;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.payees.PayeeCorrections;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayees;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.service.SpendingPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Keeps each transaction's bucket current, then has the user's recurring payees judged again,
 * since which money is plan money decides what counts as a payee's charge. Sorting asks TypeSafe
 * about every transaction, so it runs off the caller's thread, and its jobs run one at a time so
 * an older job can never overwrite a newer one's answers.
 *
 * <p>A payee the user corrected keeps the bucket they chose; Jev isn't asked about its charges.
 * Failures leave transactions unsorted rather than guessing; the next sort retries them.
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
    private final SpendingPlanService planService;
    private final BucketClassifier classifier;
    private final PayeeCorrections corrections;
    private final RecurringPayees recurringPayees;
    private final Executor jobExecutor;
    private final Executor classifyExecutor;

    BucketSorting(
            PlaidTransactionRepository transactionRepository,
            SpendingPlanService planService,
            BucketClassifier classifier,
            PayeeCorrections corrections,
            RecurringPayees recurringPayees,
            @Qualifier("sortingJobExecutor") Executor jobExecutor,
            @Qualifier("sortingClassifyExecutor") Executor classifyExecutor
    ) {
        this.transactionRepository = transactionRepository;
        this.planService = planService;
        this.classifier = classifier;
        this.corrections = corrections;
        this.recurringPayees = recurringPayees;
        this.jobExecutor = jobExecutor;
        this.classifyExecutor = classifyExecutor;
    }

    /**
     * Catches everyone up on startup, such as when payee judgments first ship. Payees whose
     * charges haven't changed since they were judged aren't asked about again.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUp() {
        if (!classifier.isAvailable()) {
            return;
        }
        for (UUID userId : transactionRepository.findUserIdsWithTransactions()) {
            sortLater(userId);
        }
    }

    /** Queues sorting of the user's transactions that don't have a bucket yet. */
    public void sortLater(UUID userId) {
        queue(userId, () -> run(userId, false));
    }

    /**
     * Sorts all of the user's transactions again if the save changed their plan lines, since the
     * lines decide cases like whether a subscription is a fixed cost, and which line a payee's
     * bills go on. Other plan changes, such as take-home pay, don't affect either.
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
        if (!classifier.isAvailable()) {
            log.info("Skipping sorting for user {}: no TypeSafe API key is set", userId);
            return;
        }
        try {
            sort(userId, includeSorted);
            recurringPayees.judge(userId);
        } catch (RuntimeException e) {
            log.warn("Sorting failed for user {}", userId, e);
        }
    }

    /**
     * Asks about every transaction in parallel and stores each bucket as it's read, skipping the
     * ones whose question failed; the next job asks them again.
     */
    private void sort(UUID userId, boolean includeSorted) {
        List<PlaidTransaction> transactions = transactionRepository.findToSort(userId, includeSorted, NOT_PLAN_MONEY);
        if (transactions.isEmpty()) {
            return;
        }
        PlanLines plan = planLines(userId);
        Map<String, PayeeCorrection> corrected = corrections.byPayee(userId);
        List<CompletableFuture<Bucket>> answers = transactions.stream()
                .map(transaction -> {
                    String payee = PayeeCorrections.payeeKey(transaction);
                    PayeeCorrection correction = payee == null ? null : corrected.get(payee);
                    return correction != null && correction.getBucket() != null
                            ? CompletableFuture.completedFuture(correction.getBucket())
                            : CompletableFuture.supplyAsync(() -> classifier.classify(transaction, plan), classifyExecutor);
                })
                .toList();

        int stored = 0;
        int failed = 0;
        RuntimeException firstFailure = null;
        for (int i = 0; i < transactions.size(); i++) {
            PlaidTransaction transaction = transactions.get(i);
            Bucket bucket;
            try {
                bucket = answers.get(i).join();
            } catch (CompletionException e) {
                failed++;
                firstFailure = firstFailure == null ? e : firstFailure;
                continue;
            }
            if (bucket != transaction.getBucket()) {
                stored += transactionRepository.updateBucket(
                        transaction.getTransactionId(), transaction.getUpdatedAt(), bucket);
            }
        }

        log.info("Sorted {} transactions for user {}: {} stored, {} failed",
                transactions.size(), userId, stored, failed);
        if (firstFailure != null) {
            log.warn("First failure for user {}", userId, firstFailure);
        }
    }

    private PlanLines planLines(UUID userId) {
        return planService.find(userId, PlanLines::of).orElse(PlanLines.NONE);
    }
}
