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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Jev's judgment of one payee the user pays or is paid by, made from all of its charges at once:
 * how likely the payments are regular, how often, and which plan line they belong under.
 */
@Entity
@Table(name = "recurring_payees", uniqueConstraints = @UniqueConstraint(
        name = "recurring_payees_unique", columnNames = {"user_id", "kind", "merchant_key"}))
public class RecurringPayee {

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

    /** How likely the payments are regular, from 0 to 1; null while Plaid detects the payee, so Jev isn't asked. */
    @Column(name = "probability", precision = 5, scale = 4)
    private BigDecimal probability;

    /** How often payments like these are usually made, for when the dates don't show a schedule. */
    @Enumerated(EnumType.STRING)
    @Column(name = "usual_frequency", length = 32)
    private RecurringFrequency usualFrequency;

    /** The plan line the payments belong under; null for pay, and when they fit no line. */
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_bucket", length = 32)
    private SpendingPlanBucket planBucket;

    @Column(name = "plan_line", length = 255)
    private String planLine;

    /** A fingerprint of what Jev was shown, so the payee is judged again only when that changes. */
    @Column(name = "judged_state", nullable = false, length = 64)
    private String judgedState;

    @Column(name = "judged_at", nullable = false)
    private Instant judgedAt;

    protected RecurringPayee() {
    }

    public RecurringPayee(UUID userId, RecurringMerchant payee) {
        this.userId = userId;
        this.kind = payee.kind();
        this.merchantKey = payee.key();
    }

    public void judged(
            BigDecimal probability,
            RecurringFrequency usualFrequency,
            SpendingPlanBucket planBucket,
            String planLine,
            String judgedState,
            Instant judgedAt
    ) {
        this.probability = probability;
        this.usualFrequency = usualFrequency;
        this.planBucket = planBucket;
        this.planLine = planLine;
        this.judgedState = judgedState;
        this.judgedAt = judgedAt;
    }

    public RecurringMerchant payee() {
        return new RecurringMerchant(kind, merchantKey);
    }

    public BigDecimal getProbability() {
        return probability;
    }

    public RecurringFrequency getUsualFrequency() {
        return usualFrequency;
    }

    public SpendingPlanBucket getPlanBucket() {
        return planBucket;
    }

    public String getPlanLine() {
        return planLine;
    }

    public String getJudgedState() {
        return judgedState;
    }
}
