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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
 * about transactions in batches, so it runs off the caller's thread, and its jobs run one at a
 * time so an older job can never overwrite a newer one's answers.
 *
 * <p>New transactions from Plaid wait for the daily job, so Jev gets a few large requests a day
 * rather than one after every sync. The user's own changes don't wait: a new bank's first
 * transactions, a plan save that changes the lines, a payee correction and a counting change
 * are sorted right away.
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

    /** Sorts everyone's new transactions once a day, in one job so users are asked about in turn. */
    @Scheduled(cron = "${sorting.daily-cron:0 0 10 * * *}", zone = "UTC")
    public void sortDaily() {
        queue(null, () -> {
            for (UUID userId : transactionRepository.findUserIdsWithTransactions()) {
                run(userId, false);
            }
        });
    }

    /**
     * Sorts a newly linked bank's transactions right away, so its first transactions don't wait
     * for the daily job. A bank sorted before waits for it. Called after each transactions sync.
     */
    public void itemSynced(UUID userId, String itemId) {
        if (!transactionRepository.existsByItemIdAndBucketIsNotNull(itemId)) {
            sortNow(userId);
        }
    }

    /**
     * Queues sorting of the user's transactions that don't have a bucket yet, and judging their
     * payees, without waiting for the daily job. For changes the user made, such as correcting a
     * payee, so they see the result.
     */
    public void sortNow(UUID userId) {
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
            log.warn("Sorting queue is full; skipping a sort for {}", userId == null ? "everyone" : "user " + userId);
        }
    }

    private void run(UUID userId, boolean includeSorted) {
        try {
            sort(userId, includeSorted);
            recurringPayees.judge(userId);
        } catch (RuntimeException e) {
            log.warn("Sorting failed for user {}", userId, e);
        }
    }

    /**
     * Asks about the transactions in batches, in parallel, and stores each bucket as it's read.
     * A failed batch leaves its transactions unsorted; the next job asks them again. Without a
     * TypeSafe API key, only payees the user corrected are sorted.
     */
    private void sort(UUID userId, boolean includeSorted) {
        List<PlaidTransaction> transactions = transactionRepository.findToSort(userId, includeSorted, NOT_PLAN_MONEY);
        if (transactions.isEmpty()) {
            return;
        }
        Map<String, PayeeCorrection> corrected = corrections.byPayee(userId);
        boolean canAsk = classifier.isAvailable();
        if (!canAsk) {
            log.info("No TypeSafe API key is set; sorting only corrected payees for user {}", userId);
        }

        int stored = 0;
        List<PlaidTransaction> toAsk = new ArrayList<>();
        for (PlaidTransaction transaction : transactions) {
            String payee = PayeeCorrections.payeeKey(transaction);
            PayeeCorrection correction = payee == null ? null : corrected.get(payee);
            if (correction != null && correction.getBucket() != null) {
                stored += store(transaction, correction.getBucket());
            } else if (canAsk) {
                toAsk.add(transaction);
            }
        }

        PlanLines plan = planLines(userId);
        List<List<PlaidTransaction>> batches = TypeSafeClient.batches(toAsk, BucketClassifier.PER_REQUEST);
        List<CompletableFuture<List<Bucket>>> answers = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> classifier.classify(batch, plan), classifyExecutor))
                .toList();
        int failed = 0;
        RuntimeException firstFailure = null;
        for (int i = 0; i < batches.size(); i++) {
            List<PlaidTransaction> batch = batches.get(i);
            List<Bucket> buckets;
            try {
                buckets = answers.get(i).join();
            } catch (CompletionException e) {
                failed += batch.size();
                firstFailure = firstFailure == null ? e : firstFailure;
                continue;
            }
            for (int j = 0; j < batch.size(); j++) {
                stored += store(batch.get(j), buckets.get(j));
            }
        }

        log.info("Sorted {} transactions for user {} in {} requests: {} stored, {} failed",
                transactions.size(), userId, batches.size(), stored, failed);
        if (firstFailure != null) {
            log.warn("First failure for user {}", userId, firstFailure);
        }
    }

    private int store(PlaidTransaction transaction, Bucket bucket) {
        return bucket == transaction.getBucket()
                ? 0
                : transactionRepository.updateBucket(transaction.getTransactionId(), transaction.getUpdatedAt(), bucket);
    }

    private PlanLines planLines(UUID userId) {
        return planService.find(userId, PlanLines::of).orElse(PlanLines.NONE);
    }
}
