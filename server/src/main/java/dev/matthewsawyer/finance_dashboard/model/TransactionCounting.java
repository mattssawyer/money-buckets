package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import java.time.Instant;
import java.util.UUID;

/** User choices kept independently of Plaid's replaceable transaction records. */
@Entity
@Table(name = "transaction_counting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionCounting {
    @Id
    @Column(name = "transaction_id")
    private String transactionId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false)
    private boolean excluded;
    @Column(name = "merchant_key", length = 512)
    private String merchantKey;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private RecurringKind kind;
    @Column(name = "future_from")
    private Instant futureFrom;

    public TransactionCounting(String transactionId, UUID userId) {
        this.transactionId = transactionId;
        this.userId = userId;
    }

    public void setExcluded(boolean excluded) {
        this.excluded = excluded;
    }

    public void excludeFuture(RecurringMerchant payee, Instant from) {
        merchantKey = payee.key();
        kind = payee.kind();
        futureFrom = from;
    }

    public void clearFuture() {
        futureFrom = null;
    }
}
