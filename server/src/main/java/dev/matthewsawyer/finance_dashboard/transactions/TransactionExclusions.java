package dev.matthewsawyer.finance_dashboard.transactions;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.TransactionCounting;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.repository.TransactionCountingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exclusions affect accounting, never the bank record or its automatically assigned bucket. */
@Service
public class TransactionExclusions {
    private final PlaidTransactionRepository transactions;
    private final TransactionCountingRepository choices;
    private final Clock clock;

    public TransactionExclusions(PlaidTransactionRepository transactions,
            TransactionCountingRepository choices, Clock clock) {
        this.transactions = transactions;
        this.choices = choices;
        this.clock = clock;
    }

    public Snapshot forUser(UUID userId) {
        return new Snapshot(choices.findAllByUserId(userId));
    }

    @Transactional
    public void set(UUID userId, String transactionId, boolean excluded, boolean future) {
        PlaidTransaction transaction = transactions.findById(transactionId)
                .filter(t -> userId.equals(t.getUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        RecurringMerchant payee = RecurringMerchant.of(transaction);
        if (future && payee == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This transaction has no recipient to match");
        }
        TransactionCounting choice = choices.findById(transactionId)
                .orElseGet(() -> new TransactionCounting(transactionId, userId));
        choice.setExcluded(excluded);
        if (future) {
            if (!excluded) {
                // Stopping a rule affects future imports, not transactions it already excluded.
                Snapshot before = forUser(userId);
                for (PlaidTransaction existing : transactions.findAllByUserId(userId)) {
                    if (!existing.getTransactionId().equals(transactionId)
                            && payee.equals(RecurringMerchant.of(existing)) && before.excluded(existing)) {
                        TransactionCounting preserved = choices.findById(existing.getTransactionId())
                                .orElseGet(() -> new TransactionCounting(existing.getTransactionId(), userId));
                        preserved.setExcluded(true);
                        choices.save(preserved);
                    }
                }
            }
            // Keep the earliest cutoff when reapplying a rule; earlier charges stay untouched.
            Instant from = Instant.now(clock);
            for (TransactionCounting previous : choices.findAllByUserId(userId)) {
                if (matches(previous, payee) && previous.getFutureFrom() != null) {
                    if (previous.getFutureFrom().isBefore(from)) from = previous.getFutureFrom();
                    previous.clearFuture();
                }
            }
            if (excluded) choice.excludeFuture(payee, from);
            else choice.clearFuture();
        }
        choices.save(choice);
    }

    private static boolean matches(TransactionCounting choice, RecurringMerchant payee) {
        return payee != null && payee.kind() == choice.getKind() && payee.key().equals(choice.getMerchantKey());
    }

    /** A request's choices, so listing transactions never makes a query for each row. */
    public static class Snapshot {
        private final Map<String, TransactionCounting> explicit = new HashMap<>();
        private final List<TransactionCounting> rules;

        public Snapshot(List<TransactionCounting> choices) {
            choices.forEach(choice -> explicit.put(choice.getTransactionId(), choice));
            rules = choices.stream().filter(choice -> choice.getFutureFrom() != null).toList();
        }

        public boolean excludesFuture(PlaidTransaction transaction) {
            RecurringMerchant payee = RecurringMerchant.of(transaction);
            return rules.stream().anyMatch(rule -> matches(rule, payee));
        }

        public boolean excluded(PlaidTransaction transaction) {
            TransactionCounting choice = explicit.get(transaction.getTransactionId());
            // Plaid replaces a pending transaction with a new ID when it posts.
            if (choice == null) choice = explicit.get(transaction.getPendingTransactionId());
            if (choice != null) return choice.isExcluded();
            RecurringMerchant payee = RecurringMerchant.of(transaction);
            return transaction.getCreatedAt() != null && rules.stream().anyMatch(rule ->
                    matches(rule, payee) && !transaction.getCreatedAt().isBefore(rule.getFutureFrom()));
        }
    }
}
