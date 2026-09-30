package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * The user's correction to how a payee's spending is classified, covering its charges now and
 * later. A null bucket or category leaves that part to sorting or Plaid.
 */
@Entity
@Table(name = "payee_corrections", uniqueConstraints = @UniqueConstraint(
        name = "payee_corrections_unique", columnNames = {"user_id", "merchant_key"}))
public class PayeeCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "merchant_key", nullable = false, updatable = false, length = 512)
    private String merchantKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "bucket", length = 32)
    private Bucket bucket;

    /** A Plaid primary category, such as RENT_AND_UTILITIES. */
    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "corrected_at", nullable = false)
    private Instant correctedAt;

    protected PayeeCorrection() {
    }

    public PayeeCorrection(UUID userId, String merchantKey) {
        this.userId = userId;
        this.merchantKey = merchantKey;
    }

    public void correct(Bucket bucket, String category, Instant correctedAt) {
        this.bucket = bucket;
        this.category = category;
        this.correctedAt = correctedAt;
    }

    public String getMerchantKey() {
        return merchantKey;
    }

    public Bucket getBucket() {
        return bucket;
    }

    public String getCategory() {
        return category;
    }
}
