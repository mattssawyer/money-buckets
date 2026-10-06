package dev.matthewsawyer.finance_dashboard.recurring;

import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringAnswer;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.repository.RecurringAnswerRepository;
import dev.matthewsawyer.finance_dashboard.repository.RecurringPayeeRepository;
import dev.matthewsawyer.finance_dashboard.service.SpendingPlanService;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.sorting.PlanLines;
import dev.matthewsawyer.finance_dashboard.spending.TrackedAccounts;
import dev.matthewsawyer.finance_dashboard.transactions.TransactionExclusions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Jev's judgments of the payees the user pays or is paid by in tracked accounts: whether the
 * payments are regular, how often, and which plan line they belong under. Each payee is judged
 * from all of its recent charges at once, and judged again only when what Jev would be shown
 * changes: a new charge, a changed plan line, or Plaid starting or stopping to detect it.
 */
@Service
public class RecurringPayees {

    private static final Logger log = LoggerFactory.getLogger(RecurringPayees.class);

    // Enough to show a monthly bill's pattern over a year without growing every request.
    static final int MAX_CHARGES = 12;

    private final PlaidTransactionRepository transactionRepository;
    private final PlaidRecurringStreamRepository streamRepository;
    private final RecurringPayeeRepository payeeRepository;
    private final RecurringAnswerRepository answerRepository;
    private final SpendingPlanService planService;
    private final TrackedAccounts trackedAccounts;
    private final PayeeJudge judge;
    private final Executor askExecutor;
    private final Clock clock;
    private final TransactionExclusions exclusions;

    RecurringPayees(
            PlaidTransactionRepository transactionRepository,
            PlaidRecurringStreamRepository streamRepository,
            RecurringPayeeRepository payeeRepository,
            RecurringAnswerRepository answerRepository,
            SpendingPlanService planService,
            TrackedAccounts trackedAccounts,
            PayeeJudge judge,
            @Qualifier("sortingClassifyExecutor") Executor askExecutor,
            Clock clock,
            TransactionExclusions exclusions
    ) {
        this.transactionRepository = transactionRepository;
        this.streamRepository = streamRepository;
        this.payeeRepository = payeeRepository;
        this.answerRepository = answerRepository;
        this.planService = planService;
        this.trackedAccounts = trackedAccounts;
        this.judge = judge;
        this.askExecutor = askExecutor;
        this.clock = clock;
        this.exclusions = exclusions;
    }

    /**
     * Judges the user's payees whose charges, plan lines or Plaid detection changed since they
     * were last judged. Runs after sorting, which decides the money that only moves between the
     * user's own accounts and so is never a payee's. A failed question leaves that payee's last
     * judgment in place; the next run asks again.
     */
    public void judge(UUID userId) {
        if (!judge.isAvailable()) {
            return;
        }
        Set<String> accountIds = trackedAccounts.shares(userId, null).keySet();
        if (accountIds.isEmpty()) {
            return;
        }

        Set<RecurringMerchant> detected = detectedByPlaid(userId, accountIds);
        PlanLines plan = planService.find(userId, PlanLines::of)
                .filter(lines -> !lines.isEmpty())
                .orElse(PlanLines.DEFAULT);
        Map<RecurringMerchant, RecurringPayee> stored = new HashMap<>();
        for (RecurringPayee payee : payeeRepository.findAllByUserId(userId)) {
            stored.put(payee.payee(), payee);
        }

        List<PayeeJudge.Request> toAsk = new ArrayList<>();
        charges(userId, accountIds).forEach((payee, charges) -> {
            PayeeJudge.Request request = judge.request(
                    payee, charges.subList(0, Math.min(charges.size(), MAX_CHARGES)), detected.contains(payee), plan);
            RecurringPayee known = stored.get(payee);
            if (request != null && (known == null || !known.getJudgedState().equals(request.fingerprint()))) {
                toAsk.add(request);
            }
        });
        if (toAsk.isEmpty()) {
            return;
        }

        List<CompletableFuture<PayeeJudge.Judgment>> answers = toAsk.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> judge.ask(request), askExecutor))
                .toList();
        int failed = 0;
        RuntimeException firstFailure = null;
        for (int i = 0; i < toAsk.size(); i++) {
            PayeeJudge.Request request = toAsk.get(i);
            PayeeJudge.Judgment judgment;
            try {
                judgment = answers.get(i).join();
            } catch (CompletionException e) {
                failed++;
                firstFailure = firstFailure == null ? e : firstFailure;
                continue;
            }
            RecurringPayee payee = stored.getOrDefault(request.payee(), new RecurringPayee(userId, request.payee()));
            payee.judged(
                    judgment.probability(),
                    judgment.usualFrequency(),
                    judgment.line() == null ? null : judgment.line().bucket(),
                    judgment.line() == null ? null : judgment.line().name(),
                    request.fingerprint(),
                    Instant.now(clock));
            payeeRepository.save(payee);
        }

        log.info("Judged {} payees for user {}: {} failed", toAsk.size(), userId, failed);
        if (firstFailure != null) {
            log.warn("First failure for user {}", userId, firstFailure);
        }
    }

    /** The stored judgment of each of the user's payees. */
    public Map<RecurringMerchant, RecurringPayee> judged(UUID userId) {
        Map<RecurringMerchant, RecurringPayee> byPayee = new HashMap<>();
        for (RecurringPayee payee : payeeRepository.findAllByUserId(userId)) {
            byPayee.put(payee.payee(), payee);
        }
        return byPayee;
    }

    /**
     * The pay and spending in the given accounts that could be a payee's, grouped by payee, each
     * newest first.
     */
    Map<RecurringMerchant, List<PlaidTransaction>> charges(UUID userId, Collection<String> accountIds) {
        var counting = exclusions.forUser(userId);
        Map<RecurringMerchant, List<PlaidTransaction>> byPayee = new LinkedHashMap<>();
        for (PlaidTransaction transaction : transactionRepository.findPayeeCharges(
                userId, accountIds, BucketSorting.NOT_PLAN_MONEY)) {
            RecurringMerchant payee = RecurringMerchant.of(transaction);
            if (payee != null && !counting.excluded(transaction)) {
                byPayee.computeIfAbsent(payee, key -> new ArrayList<>()).add(transaction);
            }
        }
        return byPayee;
    }

    /**
     * Payees Plaid detects as a recurring stream in the given accounts. A stream whose payments
     * have stopped doesn't count, so the payee can be found again from its charges elsewhere,
     * such as a bill now paid from another account. Whether they've stopped goes by the frequency
     * the user set for the payee, if they set one.
     */
    public Set<RecurringMerchant> detectedByPlaid(UUID userId, Collection<String> accountIds) {
        List<PlaidRecurringStream> streams = streamRepository.findAllByUserIdAndAccountIdIn(userId, accountIds);
        if (streams.isEmpty()) {
            return Set.of();
        }
        Map<RecurringMerchant, RecurringFrequency> usersFrequency = new HashMap<>();
        for (RecurringAnswer answer : answerRepository.findAllByUserId(userId)) {
            if (answer.getFrequency() != null) {
                usersFrequency.put(answer.payee(), answer.getFrequency());
            }
        }
        LocalDate today = LocalDate.now(clock);
        Set<RecurringMerchant> detected = new HashSet<>();
        for (PlaidRecurringStream stream : streams) {
            RecurringMerchant payee = RecurringMerchant.of(stream);
            if (payee != null && !stream.hasStopped(today, usersFrequency.get(payee))) {
                detected.add(payee);
            }
        }
        return detected;
    }
}
