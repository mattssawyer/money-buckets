package dev.matthewsawyer.finance_dashboard.recurring;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringAnswer;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.repository.RecurringAnswerRepository;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recurring candidates: payees Jev judges the user pays or is paid by regularly, and the user's
 * answers about them. Plaid's recurring streams take precedence, so a payee Plaid has detected
 * isn't a candidate.
 */
@Service
public class RecurringCandidates {

    /** How likely a charge has to be to repeat before its merchant is suggested. */
    public static final BigDecimal SUGGEST_AT = new BigDecimal("0.5");

    // Charges closer together than this are one payment split up or retried, not a schedule.
    private static final int MIN_SCHEDULE_DAYS = 5;

    private final RecurringPayees payees;
    private final RecurringAnswerRepository answerRepository;
    private final TrackedAccounts trackedAccounts;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RecurringCandidates(
            RecurringPayees payees,
            RecurringAnswerRepository answerRepository,
            TrackedAccounts trackedAccounts,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.payees = payees;
        this.answerRepository = answerRepository;
        this.trackedAccounts = trackedAccounts;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * The candidates in every tracked account, or only {@code accountId} when one is given,
     * answered or not, soonest expected first.
     */
    public List<RecurringCandidate> find(UUID userId, String accountId) {
        Map<String, Integer> shares = trackedAccounts.shares(userId, accountId);
        if (shares.isEmpty()) {
            return List.of();
        }

        return candidates(
                payees.charges(userId, shares.keySet()),
                payees.judged(userId),
                shares,
                payees.detectedByPlaid(userId, shares.keySet()),
                answers(userId),
                LocalDate.now(clock));
    }

    /** The user's answers, by payee. They cover payees Plaid detects as well as candidates. */
    public Map<RecurringMerchant, RecurringAnswer> answers(UUID userId) {
        Map<RecurringMerchant, RecurringAnswer> answers = new HashMap<>();
        for (RecurringAnswer answer : answerRepository.findAllByUserId(userId)) {
            answers.put(answer.payee(), answer);
        }
        return answers;
    }

    /**
     * Stores the user's yes or no for a merchant, replacing any earlier answer. Two answers for a
     * merchant with none stored yet can both try to add one; the one that loses updates the
     * winner's instead, in a transaction of its own since the failed insert spoils the first.
     */
    public void answer(UUID userId, RecurringKind kind, String merchantKey, boolean confirmed) {
        answer(userId, kind, merchantKey, confirmed, null);
    }

    /**
     * As {@link #answer(UUID, RecurringKind, String, boolean)}, with how often the user says a
     * payee that repeats is paid; null leaves the schedule to Plaid or the charge dates.
     */
    public void answer(
            UUID userId, RecurringKind kind, String merchantKey, boolean confirmed, RecurringFrequency frequency) {
        try {
            transactionTemplate.executeWithoutResult(
                    status -> store(userId, kind, merchantKey, confirmed, frequency));
        } catch (DataIntegrityViolationException e) {
            transactionTemplate.executeWithoutResult(
                    status -> store(userId, kind, merchantKey, confirmed, frequency));
        }
    }

    private void store(
            UUID userId, RecurringKind kind, String merchantKey, boolean confirmed, RecurringFrequency frequency) {
        RecurringAnswer answer = answerRepository.findByUserIdAndKindAndMerchantKey(userId, kind, merchantKey)
                .orElseGet(() -> new RecurringAnswer(userId, kind, merchantKey));
        answer.answer(confirmed, frequency, Instant.now(clock));
        // Flushed here so a clashing insert fails inside the transaction rather than at commit.
        answerRepository.saveAndFlush(answer);
    }

    /** Forgets the user's answer for a merchant, so it's suggested again. */
    @Transactional
    public void undo(UUID userId, RecurringKind kind, String merchantKey) {
        answerRepository.findByUserIdAndKindAndMerchantKey(userId, kind, merchantKey)
                .ifPresent(answerRepository::delete);
    }

    /**
     * One candidate per payee Jev judges likely to be regular, or the user confirmed, left out
     * while Plaid detects it and once its payments have stopped. Its amount, schedule, account and categories come from the
     * payee's usual charges. Soonest expected first.
     *
     * @param chargesByPayee each payee's charges, newest first
     */
    static List<RecurringCandidate> candidates(
            Map<RecurringMerchant, List<PlaidTransaction>> chargesByPayee,
            Map<RecurringMerchant, RecurringPayee> judged,
            Map<String, Integer> shares,
            Set<RecurringMerchant> detectedByPlaid,
            Map<RecurringMerchant, RecurringAnswer> answers,
            LocalDate today
    ) {
        List<RecurringCandidate> candidates = new ArrayList<>();
        chargesByPayee.forEach((key, charges) -> {
            if (detectedByPlaid.contains(key)) {
                return;
            }
            RecurringAnswer answered = answers.get(key);
            Boolean answer = answered == null ? null : answered.isConfirmed();
            RecurringPayee payee = judged.get(key);
            BigDecimal probability = payee == null ? null : payee.getProbability();
            boolean likely = probability != null && probability.compareTo(SUGGEST_AT) >= 0;
            if (!likely && !Boolean.TRUE.equals(answer)) {
                return;
            }

            List<PlaidTransaction> usual = usualCharges(charges);
            PlaidTransaction latest = usual.get(0);
            boolean frequencySet = answered != null && answered.getFrequency() != null;
            RecurringFrequency frequency = frequencySet ? answered.getFrequency() : frequency(
                    usual.stream().map(PlaidTransaction::getTransactionDate).toList(),
                    payee == null ? null : payee.getUsualFrequency());
            LocalDate next = frequency.next(latest.getTransactionDate());
            // A whole cycle overdue, the payments have stopped: a cancelled subscription, or a bill
            // from an account the user has moved away from. One late payment doesn't drop it.
            if (frequency.next(next).isBefore(today)) {
                return;
            }

            candidates.add(new RecurringCandidate(
                    key.kind(),
                    key.key(),
                    PayeeJudge.displayName(latest),
                    latest.getAccountId(),
                    latest.getAmount(),
                    latest.getIsoCurrencyCode(),
                    frequency,
                    frequencySet,
                    next.isBefore(today) ? null : next,
                    latest.getTransactionDate(),
                    latest.getPersonalFinanceCategoryPrimary(),
                    latest.getPersonalFinanceCategoryDetailed(),
                    shares.get(latest.getAccountId()),
                    probability == null ? BigDecimal.ZERO : probability,
                    payee == null ? null : payee.getPlanBucket(),
                    payee == null ? null : payee.getPlanLine(),
                    answer == null ? RecurringCandidate.Status.SUGGESTED
                            : answer ? RecurringCandidate.Status.CONFIRMED : RecurringCandidate.Status.DISMISSED));
        });
        candidates.sort(Comparator
                .comparing(RecurringCandidate::nextDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RecurringCandidate::lastDate, Comparator.reverseOrder()));
        return candidates;
    }

    /**
     * The payee's charges for its usual amount, newest first, so a subscription isn't priced or
     * scheduled by one-off purchases from the same payee: Uber One among rides, Prime among
     * Amazon orders. The usual amount is the latest charge's if it repeats, so a price rise
     * shows once it has, and otherwise the most common one. With no amount repeating, that's
     * every charge.
     */
    static List<PlaidTransaction> usualCharges(List<PlaidTransaction> charges) {
        Map<BigDecimal, List<PlaidTransaction>> byAmount = new LinkedHashMap<>();
        for (PlaidTransaction charge : charges) {
            byAmount.computeIfAbsent(charge.getAmount().stripTrailingZeros(), key -> new ArrayList<>()).add(charge);
        }
        List<PlaidTransaction> latest = byAmount.get(charges.get(0).getAmount().stripTrailingZeros());
        List<PlaidTransaction> usual = latest.size() > 1 ? latest : byAmount.values().stream()
                .max(Comparator.comparingInt(List::size))
                .orElseThrow();
        return usual.size() > 1 ? usual : charges;
    }

    /**
     * How often charges on these dates repeat, from the shortest gap between them: a bill's
     * schedule is its shortest regular interval, and a longer gap is a payment that was missed or
     * made some other way, not a slower schedule. With one charge, or charges too close together
     * to show a schedule, Jev's guess is used.
     */
    static RecurringFrequency frequency(List<LocalDate> dates, RecurringFrequency jevsGuess) {
        RecurringFrequency fallback = jevsGuess == null ? RecurringFrequency.MONTHLY : jevsGuess;
        List<LocalDate> days = dates.stream().distinct().sorted().toList();
        if (days.size() < 2) {
            return fallback;
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < days.size(); i++) {
            long gap = ChronoUnit.DAYS.between(days.get(i - 1), days.get(i));
            if (gap >= MIN_SCHEDULE_DAYS) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return fallback;
        }
        long shortest = Collections.min(gaps);

        if (shortest <= 10) {
            return RecurringFrequency.WEEKLY;
        } else if (shortest <= 17) {
            // Every other week lands the same weekday each time, even after a missed one; the 1st
            // and 15th don't.
            return gaps.stream().allMatch(gap -> gap % 14 == 0)
                    ? RecurringFrequency.BIWEEKLY : RecurringFrequency.SEMI_MONTHLY;
        } else if (shortest <= 45) {
            return RecurringFrequency.MONTHLY;
        } else if (shortest <= 135) {
            return RecurringFrequency.QUARTERLY;
        } else if (shortest <= 270) {
            return RecurringFrequency.SEMI_ANNUALLY;
        }
        return RecurringFrequency.ANNUALLY;
    }
}
