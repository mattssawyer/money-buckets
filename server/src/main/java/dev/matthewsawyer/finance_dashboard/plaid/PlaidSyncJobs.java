package dev.matthewsawyer.finance_dashboard.plaid;

import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static dev.matthewsawyer.finance_dashboard.plaid.PlaidSyncJob.Scope;

/** All mutations take a short database lock; no Plaid I/O runs inside that lock. */
@Component
class PlaidSyncJobs {
    private final PlaidSyncJobRepository jobs;
    private final PlaidItemRepository items;
    private final TransactionTemplate transaction;

    PlaidSyncJobs(PlaidSyncJobRepository jobs, PlaidItemRepository items, TransactionTemplate transaction) {
        this.jobs = jobs;
        this.items = items;
        this.transaction = transaction;
    }

    List<PlaidSyncJob> forItem(String itemId) { return jobs.findAllByItemId(itemId); }

    boolean request(String itemId, Scope scope, String trigger, Instant now, Duration freshness, boolean reset) {
        return Boolean.TRUE.equals(change(itemId, scope, job -> {
            if (!reset && freshness != null && ((job.lastSuccess != null && job.lastSuccess.plus(freshness).isAfter(now))
                    || ("manual".equals(trigger) && job.lastAttempt != null && job.lastAttempt.plus(freshness).isAfter(now)))) {
                return false;
            }
            // Recovery scans never manufacture more work while an earlier request is pending.
            if (freshness == null || !job.pending()) {
                job.requested++;
                job.trigger = trigger;
            }
            if (reset) { job.nextAttempt = null; job.failures = 0; }
            return job.runnable(now);
        }));
    }

    PlaidSyncJob start(String itemId, Scope scope, Instant now) {
        return change(itemId, scope, job -> {
            if (!job.runnable(now)) return null;
            job.lastAttempt = now;
            // Return a detached snapshot; later requests must not change the completion generation.
            PlaidSyncJob run = new PlaidSyncJob(itemId, scope);
            run.requested = job.requested;
            run.trigger = job.trigger;
            return run;
        });
    }

    void succeed(PlaidSyncJob run, Instant now) {
        change(run.itemId, run.scope, job -> {
            job.completed = Math.max(job.completed, run.requested);
            job.lastSuccess = now;
            job.nextAttempt = null;
            job.failures = 0;
            return null;
        });
    }

    void fail(PlaidSyncJob run, Instant now, boolean retryable) {
        change(run.itemId, run.scope, job -> {
            job.failures = Math.min(job.failures + 1, 5);
            long minutes = !retryable || job.failures >= 5 ? 1440
                    : new long[]{2, 10, 30, 120}[job.failures - 1];
            // A persisted budget, shared by all triggers, with a slow daily recovery after exhaustion.
            long seconds = minutes * 60;
            job.nextAttempt = now.plusSeconds(seconds + ThreadLocalRandom.current().nextLong(Math.max(1, seconds / 5)));
            return null;
        });
    }

    void discard(String itemId, Scope scope) {
        change(itemId, scope, job -> {
            job.completed = job.requested;
            job.nextAttempt = null;
            return null;
        });
    }

    void forget(String itemId) { jobs.deleteAllByItemId(itemId); }

    private <T> T change(String itemId, Scope scope, Function<PlaidSyncJob, T> action) {
        return transaction.execute(status -> {
            var item = items.lockById(itemId).orElse(null);
            if (item == null || item.isRemoved()) return null;
            PlaidSyncJob job = jobs.findById(itemId + ":" + scope).orElseGet(() -> new PlaidSyncJob(itemId, scope));
            T result = action.apply(job);
            jobs.saveAndFlush(job);
            return result;
        });
    }
}
