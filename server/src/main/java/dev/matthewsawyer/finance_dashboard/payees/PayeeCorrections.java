package dev.matthewsawyer.finance_dashboard.payees;

import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PayeeCorrection;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.repository.PayeeCorrectionRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The user's corrections to how payees' spending is classified. A correction covers the payee's
 * charges now and later: its bucket is stored on them straight away and used by sorting instead
 * of asking Jev, and its category is shown in place of Plaid's.
 */
@Service
public class PayeeCorrections {

    private final PayeeCorrectionRepository correctionRepository;
    private final PlaidTransactionRepository transactionRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PayeeCorrections(
            PayeeCorrectionRepository correctionRepository,
            PlaidTransactionRepository transactionRepository,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.correctionRepository = correctionRepository;
        this.transactionRepository = transactionRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * The payee a transaction's spending is corrected under, or null for money coming in, which
     * has no bucket, and for transactions with neither a merchant nor a description.
     */
    public static String payeeKey(PlaidTransaction transaction) {
        RecurringMerchant payee = RecurringMerchant.of(transaction);
        return payee == null || payee.kind() != RecurringKind.BILL || transaction.getAmount().signum() <= 0
                ? null : payee.key();
    }

    /** The user's corrections, by payee key. */
    public Map<String, PayeeCorrection> byPayee(UUID userId) {
        Map<String, PayeeCorrection> byPayee = new HashMap<>();
        for (PayeeCorrection correction : correctionRepository.findAllByUserId(userId)) {
            byPayee.put(correction.getMerchantKey(), correction);
        }
        return byPayee;
    }

    /**
     * Stores the user's bucket and category for a payee, replacing any earlier correction, and
     * moves the payee's existing charges to that bucket. Two corrections racing to add the first
     * one for a payee end with the later one updating the other's, like recurring answers.
     */
    public void correct(UUID userId, String merchantKey, Bucket bucket, String category) {
        try {
            transactionTemplate.executeWithoutResult(status -> store(userId, merchantKey, bucket, category));
        } catch (DataIntegrityViolationException e) {
            transactionTemplate.executeWithoutResult(status -> store(userId, merchantKey, bucket, category));
        }
        if (bucket != null) {
            for (PlaidTransaction charge : charges(userId, merchantKey)) {
                if (charge.getBucket() != bucket) {
                    transactionRepository.updateBucket(charge.getTransactionId(), charge.getUpdatedAt(), bucket);
                }
            }
        }
    }

    /** Forgets a payee's correction. Its charges are left unsorted, for sorting to decide again. */
    public void undo(UUID userId, String merchantKey) {
        correctionRepository.findByUserIdAndMerchantKey(userId, merchantKey).ifPresent(correction -> {
            correctionRepository.delete(correction);
            if (correction.getBucket() != null) {
                List<String> ids = charges(userId, merchantKey).stream()
                        .map(PlaidTransaction::getTransactionId)
                        .toList();
                if (!ids.isEmpty()) {
                    transactionRepository.clearBuckets(ids);
                }
            }
        });
    }

    private void store(UUID userId, String merchantKey, Bucket bucket, String category) {
        PayeeCorrection correction = correctionRepository.findByUserIdAndMerchantKey(userId, merchantKey)
                .orElseGet(() -> new PayeeCorrection(userId, merchantKey));
        correction.correct(bucket, category, Instant.now(clock));
        // Flushed here so a clashing insert fails inside the transaction rather than at commit.
        correctionRepository.saveAndFlush(correction);
    }

    private List<PlaidTransaction> charges(UUID userId, String merchantKey) {
        return transactionRepository.findMoneyOut(userId).stream()
                .filter(transaction -> merchantKey.equals(payeeKey(transaction)))
                .toList();
    }
}
