package dev.matthewsawyer.finance_dashboard.recurring;

import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringAnswer;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.repository.RecurringAnswerRepository;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recurring candidates: merchants Jev thinks the user pays or is paid by regularly, found by
 * grouping its judgments of single transactions, and the user's answers about them. Plaid's
 * recurring streams take precedence, so a merchant Plaid has detected isn't a candidate.
 */
@Service
public class RecurringCandidates {

    /** How likely a charge has to be to repeat before its merchant is suggested. */
    static final BigDecimal SUGGEST_AT = new BigDecimal("0.5");

    // Charges closer together than this are one payment split up or retried, not a schedule.
    private static final int MIN_SCHEDULE_DAYS = 5;

    private final PlaidTransactionRepository transactionRepository;
    private final PlaidRecurringStreamRepository streamRepository;
    private final RecurringAnswerRepository answerRepository;
    private final TrackedAccounts trackedAccounts;
    private final Clock clock;

    public RecurringCandidates(
            PlaidTransactionRepository transactionRepository,
            PlaidRecurringStreamRepository streamRepository,
            RecurringAnswerRepository answerRepository,
            TrackedAccounts trackedAccounts,
            Clock clock
    ) {
        this.transactionRepository = transactionRepository;
        this.streamRepository = streamRepository;
        this.answerRepository = answerRepository;
        this.trackedAccounts = trackedAccounts;
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

        List<PlaidTransaction> judged =
                transactionRepository.findJudged(userId, shares.keySet(), BucketSorting.NOT_PLAN_MONEY);
        Set<RecurringMerchant> detectedByPlaid = new HashSet<>();
        for (PlaidRecurringStream stream : streamRepository.findAllByUserIdAndAccountIdIn(userId, shares.keySet())) {
            RecurringMerchant merchant = RecurringMerchant.of(stream);
            if (merchant != null) {
                detectedByPlaid.add(merchant);
            }
        }
        Map<RecurringMerchant, Boolean> answers = new HashMap<>();
        for (RecurringAnswer answer : answerRepository.findAllByUserId(userId)) {
            answers.put(new RecurringMerchant(answer.getKind(), answer.getMerchantKey()), answer.isConfirmed());
        }

        return group(judged, shares, detectedByPlaid, answers, LocalDate.now(clock));
    }

    /** Stores the user's yes or no for a merchant, replacing any earlier answer. */
    @Transactional
    public void answer(UUID userId, RecurringKind kind, String merchantKey, boolean confirmed) {
        RecurringAnswer answer = answerRepository.findByUserIdAndKindAndMerchantKey(userId, kind, merchantKey)
                .orElseGet(() -> new RecurringAnswer(userId, kind, merchantKey));
        answer.answer(confirmed, Instant.now(clock));
        answerRepository.save(answer);
    }

    /** Forgets the user's answer for a merchant, so it's suggested again. */
    @Transactional
    public void undo(UUID userId, RecurringKind kind, String merchantKey) {
        answerRepository.findByUserIdAndKindAndMerchantKey(userId, kind, merchantKey)
                .ifPresent(answerRepository::delete);
    }

    /**
     * Groups judged transactions, newest first, into one candidate per merchant and kind. A
     * merchant becomes a candidate once any of its charges is likely to repeat, or when the user
     * confirmed it, and is left out while Plaid detects it. Soonest expected first.
     */
    static List<RecurringCandidate> group(
            List<PlaidTransaction> judged,
            Map<String, Integer> shares,
            Set<RecurringMerchant> detectedByPlaid,
            Map<RecurringMerchant, Boolean> answers,
            LocalDate today
    ) {
        Map<RecurringMerchant, List<PlaidTransaction>> byMerchant = new LinkedHashMap<>();
        for (PlaidTransaction transaction : judged) {
            RecurringMerchant merchant = RecurringMerchant.of(transaction);
            if (merchant != null) {
                byMerchant.computeIfAbsent(merchant, key -> new ArrayList<>()).add(transaction);
            }
        }

        List<RecurringCandidate> candidates = new ArrayList<>();
        byMerchant.forEach((key, transactions) -> {
            Boolean answer = answers.get(key);
            if (detectedByPlaid.contains(key)) {
                return;
            }
            List<PlaidTransaction> likely = transactions.stream()
                    .filter(transaction -> transaction.getRecurringProbability() != null
                            && transaction.getRecurringProbability().compareTo(SUGGEST_AT) >= 0)
                    .toList();
            if (likely.isEmpty() && !Boolean.TRUE.equals(answer)) {
                return;
            }

            // A store's one-off purchases shouldn't set the amount or schedule of its subscription.
            List<PlaidTransaction> charges = likely.isEmpty() ? transactions : likely;
            PlaidTransaction latest = charges.get(0);
            RecurringFrequency frequency = frequency(
                    charges.stream().map(PlaidTransaction::getTransactionDate).toList(), latest.getUsualFrequency());
            LocalDate next = frequency.next(latest.getTransactionDate());
            BigDecimal probability = charges.stream()
                    .map(PlaidTransaction::getRecurringProbability)
                    .filter(value -> value != null)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            candidates.add(new RecurringCandidate(
                    key.kind(),
                    key.key(),
                    displayName(latest),
                    latest.getAccountId(),
                    latest.getAmount(),
                    latest.getIsoCurrencyCode(),
                    frequency,
                    next.isBefore(today) ? null : next,
                    latest.getTransactionDate(),
                    latest.getPersonalFinanceCategoryPrimary(),
                    latest.getPersonalFinanceCategoryDetailed(),
                    shares.get(latest.getAccountId()),
                    probability,
                    answer == null ? RecurringCandidate.Status.SUGGESTED
                            : answer ? RecurringCandidate.Status.CONFIRMED : RecurringCandidate.Status.DISMISSED));
        });
        candidates.sort(Comparator
                .comparing(RecurringCandidate::nextDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RecurringCandidate::lastDate, Comparator.reverseOrder()));
        return candidates;
    }

    /**
     * How often charges on these dates repeat, from the typical gap between them. With one
     * charge, or charges too close together to show a schedule, Jev's guess is used.
     */
    static RecurringFrequency frequency(List<LocalDate> dates, RecurringFrequency jevsGuess) {
        RecurringFrequency fallback = jevsGuess == null ? RecurringFrequency.MONTHLY : jevsGuess;
        List<LocalDate> days = dates.stream().distinct().sorted().toList();
        if (days.size() < 2) {
            return fallback;
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < days.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(days.get(i - 1), days.get(i)));
        }
        long median = gaps.stream().sorted().toList().get(gaps.size() / 2);

        if (median < MIN_SCHEDULE_DAYS) {
            return fallback;
        } else if (median <= 10) {
            return RecurringFrequency.WEEKLY;
        } else if (median <= 17) {
            // Every other week lands the same weekday each time; the 1st and 15th don't.
            return gaps.stream().allMatch(gap -> gap == 14)
                    ? RecurringFrequency.BIWEEKLY : RecurringFrequency.SEMI_MONTHLY;
        } else if (median <= 45) {
            return RecurringFrequency.MONTHLY;
        } else if (median <= 135) {
            return RecurringFrequency.QUARTERLY;
        } else if (median <= 270) {
            return RecurringFrequency.SEMI_ANNUALLY;
        }
        return RecurringFrequency.ANNUALLY;
    }

    private static String displayName(PlaidTransaction transaction) {
        String merchant = transaction.getMerchantName();
        return merchant != null && !merchant.isBlank() ? merchant : transaction.getName();
    }
}
