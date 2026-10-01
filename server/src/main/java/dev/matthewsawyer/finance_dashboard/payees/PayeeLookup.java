package dev.matthewsawyer.finance_dashboard.payees;

import dev.matthewsawyer.finance_dashboard.model.PayeeCorrection;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringAnswer;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringCandidates;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayees;
import dev.matthewsawyer.finance_dashboard.repository.RecurringAnswerRepository;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the user and Jev have said about the payees behind transactions, looked up once per
 * request: the user's corrections, and whether each payee repeats.
 */
@Service
public class PayeeLookup {

    private final PayeeCorrections corrections;
    private final RecurringPayees recurringPayees;
    private final RecurringAnswerRepository answerRepository;
    private final TrackedAccounts trackedAccounts;

    public PayeeLookup(
            PayeeCorrections corrections,
            RecurringPayees recurringPayees,
            RecurringAnswerRepository answerRepository,
            TrackedAccounts trackedAccounts
    ) {
        this.corrections = corrections;
        this.recurringPayees = recurringPayees;
        this.answerRepository = answerRepository;
        this.trackedAccounts = trackedAccounts;
    }

    public Payees forUser(UUID userId) {
        Map<RecurringMerchant, Boolean> answers = new HashMap<>();
        for (RecurringAnswer answer : answerRepository.findAllByUserId(userId)) {
            answers.put(new RecurringMerchant(answer.getKind(), answer.getMerchantKey()), answer.isConfirmed());
        }
        return new Payees(
                corrections.byPayee(userId),
                answers,
                recurringPayees.judged(userId),
                recurringPayees.detectedByPlaid(userId, trackedAccounts.shares(userId, null).keySet()));
    }

    /** Whether a transaction's payee repeats, as far as anyone has said. */
    public enum Recurring {
        /** Plaid detects it as a recurring stream. */
        DETECTED,
        /** The user said it repeats. */
        CONFIRMED,
        /** The user said it doesn't. */
        DISMISSED,
        /** Jev thinks it repeats and the user hasn't answered. */
        SUGGESTED
    }

    public record Payees(
            Map<String, PayeeCorrection> corrections,
            Map<RecurringMerchant, Boolean> answers,
            Map<RecurringMerchant, RecurringPayee> judged,
            Set<RecurringMerchant> detected
    ) {

        /** The user's correction for the transaction's payee, or null. */
        public PayeeCorrection correction(PlaidTransaction transaction) {
            String payee = PayeeCorrections.payeeKey(transaction);
            return payee == null ? null : corrections.get(payee);
        }

        /** The Plaid primary category to show: the user's, if they corrected it, else Plaid's. */
        public String category(PlaidTransaction transaction) {
            PayeeCorrection correction = correction(transaction);
            return correction != null && correction.getCategory() != null
                    ? correction.getCategory()
                    : transaction.getPersonalFinanceCategoryPrimary();
        }

        /**
         * The plan line Jev put the transaction's payee on, or null: the payee is on no line, or
         * the transaction is sorted into another bucket than the line's, as when the user
         * corrected the payee into guilt-free spending.
         */
        public RecurringPayee line(PlaidTransaction transaction) {
            RecurringMerchant payee = RecurringMerchant.of(transaction);
            RecurringPayee judgment = payee == null ? null : judged.get(payee);
            if (judgment == null || judgment.getPlanBucket() == null || judgment.getPlanLine() == null
                    || transaction.getBucket() == null
                    || !transaction.getBucket().name().equals(judgment.getPlanBucket().name())) {
                return null;
            }
            return judgment;
        }

        public Recurring recurring(PlaidTransaction transaction) {
            RecurringMerchant payee = RecurringMerchant.of(transaction);
            if (payee == null) {
                return null;
            }
            // The user's word wins, even over Plaid: they can say a payee it detects doesn't repeat.
            Boolean answer = answers.get(payee);
            if (Boolean.FALSE.equals(answer)) {
                return Recurring.DISMISSED;
            }
            if (detected.contains(payee)) {
                return Recurring.DETECTED;
            }
            if (answer != null) {
                return Recurring.CONFIRMED;
            }
            RecurringPayee judgment = judged.get(payee);
            BigDecimal probability = judgment == null ? null : judgment.getProbability();
            return probability != null && probability.compareTo(RecurringCandidates.SUGGEST_AT) >= 0
                    ? Recurring.SUGGESTED : null;
        }
    }
}
