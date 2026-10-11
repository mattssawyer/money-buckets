package dev.matthewsawyer.finance_dashboard.plaid;

import jakarta.persistence.*;
import java.time.Instant;

/** Durable requested/completed generations: a webhook arriving during a run remains pending. */
@Entity
@Table(name = "plaid_sync_jobs")
class PlaidSyncJob {
    enum Scope { ACCOUNTS, TRANSACTIONS, RECURRING, WEBHOOK }

    @Id @Column(length = 300) String id;
    @Column(name = "item_id", nullable = false) String itemId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) Scope scope;
    @Column(nullable = false) long requested;
    @Column(nullable = false) long completed;
    @Column(name = "last_success") Instant lastSuccess;
    @Column(name = "last_attempt") Instant lastAttempt;
    @Column(name = "next_attempt") Instant nextAttempt;
    @Column(nullable = false) int failures;
    @Column(length = 32) String trigger;

    protected PlaidSyncJob() { }
    PlaidSyncJob(String itemId, Scope scope) {
        this.id = itemId + ":" + scope;
        this.itemId = itemId;
        this.scope = scope;
    }
    boolean pending() { return requested > completed; }
    boolean runnable(Instant now) { return pending() && (nextAttempt == null || !nextAttempt.isAfter(now)); }
}
