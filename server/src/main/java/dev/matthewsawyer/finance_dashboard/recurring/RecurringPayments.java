package dev.matthewsawyer.finance_dashboard.recurring;

import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.RecurringAnswer;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The payments that count as recurring: Plaid's recurring streams whose payments haven't stopped
 * and whose payee the user hasn't said doesn't repeat, and the recurring candidates the user
 * confirmed. A candidate is never a payee Plaid detects (ADR 0002), so nothing counts twice.
 */
@Service
public class RecurringPayments {

    private final PlaidRecurringStreamRepository streamRepository;
    private final TrackedAccounts trackedAccounts;
    private final RecurringPayees payees;
    private final RecurringCandidates candidates;
    private final Clock clock;

    public RecurringPayments(
            PlaidRecurringStreamRepository streamRepository,
            TrackedAccounts trackedAccounts,
            RecurringPayees payees,
            RecurringCandidates candidates,
            Clock clock
    ) {
        this.streamRepository = streamRepository;
        this.trackedAccounts = trackedAccounts;
        this.payees = payees;
        this.candidates = candidates;
        this.clock = clock;
    }

    /** A Plaid recurring stream with what Jev and the user said about its payee. */
    public record Detected(
            PlaidRecurringStream stream,
            int sharePercent,
            /** Null when Plaid named nobody. */
            RecurringMerchant payee,
            /** Jev's judgment of the payee, or null until it's judged. */
            RecurringPayee judged,
            /** The user's answer for the payee, or null. */
            RecurringAnswer answer
    ) {

        /** The user said the payee doesn't repeat. */
        public boolean dismissed() {
            return answer != null && !answer.isConfirmed();
        }
    }

    /** What counts as recurring in every tracked account, and how many candidates wait for an answer. */
    public record Counted(List<RecurringPayment> payments, int suggestedCandidates) {
    }

    /**
     * Plaid's recurring streams in the accounts given, at the shares given, whose payments
     * haven't stopped. Dismissed ones are included; see {@link Detected#dismissed()}.
     */
    public List<Detected> plaidStreams(UUID userId, Map<String, Integer> shares) {
        List<PlaidRecurringStream> stored = shares.isEmpty()
                ? List.of()
                : streamRepository.findAllByUserIdAndAccountIdIn(userId, shares.keySet());
        if (stored.isEmpty()) {
            return List.of();
        }
        Map<RecurringMerchant, RecurringPayee> judged = payees.judged(userId);
        Map<RecurringMerchant, RecurringAnswer> answers = candidates.answers(userId);
        LocalDate today = LocalDate.now(clock);
        List<Detected> detected = new ArrayList<>();
        for (PlaidRecurringStream stream : stored) {
            RecurringMerchant payee = RecurringMerchant.of(stream);
            RecurringAnswer answer = answers.get(payee);
            if (!stream.hasStopped(today, answer == null ? null : answer.getFrequency())) {
                detected.add(new Detected(
                        stream, shares.get(stream.getAccountId()), payee, judged.get(payee), answer));
            }
        }
        return detected;
    }

    /**
     * Every tracked account's recurring payments that count, soonest expected first, then most
     * recently paid.
     */
    public Counted counted(UUID userId) {
        List<RecurringPayment> payments = new ArrayList<>();
        for (Detected detected : plaidStreams(userId, trackedAccounts.shares(userId, null))) {
            if (!detected.dismissed()) {
                payments.add(from(detected));
            }
        }
        int suggested = 0;
        for (RecurringCandidate candidate : candidates.find(userId, null)) {
            if (candidate.status() == RecurringCandidate.Status.CONFIRMED) {
                payments.add(from(candidate));
            } else if (candidate.status() == RecurringCandidate.Status.SUGGESTED) {
                suggested++;
            }
        }
        payments.sort(Comparator
                .comparing(RecurringPayment::nextDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RecurringPayment::lastDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return new Counted(List.copyOf(payments), suggested);
    }

    private static RecurringPayment from(Detected detected) {
        PlaidRecurringStream stream = detected.stream();
        RecurringAnswer answer = detected.answer();
        boolean frequencySet = answer != null && answer.getFrequency() != null;
        RecurringPayee judged = detected.judged();
        RecurringMerchant payee = detected.payee();
        return new RecurringPayment(
                stream.getStreamId(),
                stream.getAccountId(),
                stream.getMerchantName(),
                stream.getDescription(),
                stream.getAmount(),
                stream.getIsoCurrencyCode(),
                frequencySet ? answer.getFrequency().name() : stream.getFrequency(),
                frequencySet,
                stream.getNextDate(),
                stream.getLastDate(),
                stream.isInflow(),
                stream.getCategory(),
                stream.getCategoryDetailed(),
                detected.sharePercent(),
                judged == null ? null : judged.getPlanBucket(),
                judged == null ? null : judged.getPlanLine(),
                payee == null ? null : payee.kind(),
                payee == null ? null : payee.key(),
                RecurringPayment.Source.DETECTED);
    }

    private static RecurringPayment from(RecurringCandidate candidate) {
        return new RecurringPayment(
                candidate.streamId(),
                candidate.accountId(),
                candidate.name(),
                candidate.name(),
                candidate.amount(),
                candidate.isoCurrencyCode(),
                candidate.frequency().name(),
                candidate.frequencySet(),
                candidate.nextDate(),
                candidate.lastDate(),
                candidate.kind() == RecurringKind.PAYCHECK,
                candidate.category(),
                candidate.categoryDetailed(),
                candidate.sharePercent(),
                candidate.planBucket(),
                candidate.planLine(),
                candidate.kind(),
                candidate.merchantKey(),
                RecurringPayment.Source.CONFIRMED);
    }
}
