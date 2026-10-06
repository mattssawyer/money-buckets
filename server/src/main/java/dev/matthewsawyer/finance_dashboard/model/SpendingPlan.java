package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "spending_plans")
public class SpendingPlan {

    public static final BigDecimal DEFAULT_BUFFER_PERCENT = new BigDecimal("15");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false, unique = true)
    private UUID userId;

    @Column(name = "account_id")
    private String accountId;

    @Column(name = "take_home", precision = 19, scale = 4)
    private BigDecimal takeHome;

    /** Monthly pay before taxes and deductions, for lines set as a percent of it. */
    @Column(name = "gross_pay", precision = 19, scale = 4)
    private BigDecimal grossPay;

    /** Percent of fixed costs added on top for forgotten and rising costs. */
    @Column(name = "fixed_cost_buffer_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal fixedCostBufferPercent = DEFAULT_BUFFER_PERCENT;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<SpendingPlanLine> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SpendingPlan() {
    }

    public SpendingPlan(UUID userId) {
        this.userId = userId;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Replaces the whole plan. The editor always sends every line, so old lines (and their items)
     * are removed rather than matched up; list order becomes each line's position.
     */
    public void replace(
            String accountId,
            BigDecimal takeHome,
            BigDecimal grossPay,
            BigDecimal fixedCostBufferPercent,
            List<SpendingPlanLine> newLines
    ) {
        this.accountId = accountId;
        this.takeHome = takeHome;
        this.grossPay = grossPay;
        this.fixedCostBufferPercent = fixedCostBufferPercent;
        this.updatedAt = Instant.now();
        lines.clear();
        for (int position = 0; position < newLines.size(); position++) {
            SpendingPlanLine line = newLines.get(position);
            line.attach(this, position);
            lines.add(line);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getTakeHome() {
        return takeHome;
    }

    public BigDecimal getGrossPay() {
        return grossPay;
    }

    public BigDecimal getFixedCostBufferPercent() {
        return fixedCostBufferPercent;
    }

    public List<SpendingPlanLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
