package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The user's yes or no to a recurring candidate. It's kept per merchant and kind rather than per
 * transaction, so it covers the merchant's later charges and outlives Jev's judgments of them.
 */
@Entity
@Table(name = "recurring_answers")
public class RecurringAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private RecurringKind kind;

    @Column(name = "merchant_key", nullable = false, updatable = false, length = 512)
    private String merchantKey;

    @Column(name = "confirmed", nullable = false)
    private boolean confirmed;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    protected RecurringAnswer() {
    }

    public RecurringAnswer(UUID userId, RecurringKind kind, String merchantKey) {
        this.userId = userId;
        this.kind = kind;
        this.merchantKey = merchantKey;
    }

    public void answer(boolean confirmed, Instant answeredAt) {
        this.confirmed = confirmed;
        this.answeredAt = answeredAt;
    }

    public RecurringKind getKind() {
        return kind;
    }

    public String getMerchantKey() {
        return merchantKey;
    }

    public boolean isConfirmed() {
        return confirmed;
    }
}
