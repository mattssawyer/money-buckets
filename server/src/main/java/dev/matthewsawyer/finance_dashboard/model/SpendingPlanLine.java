package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "spending_plan_lines")
public class SpendingPlanLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private SpendingPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SpendingPlanBucket bucket;

    @Column(nullable = false)
    private String name;

    /** Only meaningful when the line has no items; otherwise the line is the sum of its items. */
    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private int position;

    /** Taken out of the paycheck, so it's added back to take-home pay rather than subtracted twice. */
    @Column(name = "from_paycheck", nullable = false)
    private boolean fromPaycheck;

    /** Set as a percent of gross pay; the amount is still saved, worked out from it. */
    @Column(name = "percent_of_gross", columnDefinition = "numeric")
    private BigDecimal percentOfGross;

    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<SpendingPlanItem> items = new ArrayList<>();

    protected SpendingPlanLine() {
    }

    public SpendingPlanLine(
            SpendingPlanBucket bucket,
            String name,
            BigDecimal amount,
            boolean fromPaycheck,
            BigDecimal percentOfGross,
            List<SpendingPlanItem> items
    ) {
        this.bucket = bucket;
        this.name = name;
        this.amount = amount;
        this.fromPaycheck = fromPaycheck;
        this.percentOfGross = percentOfGross;
        for (int position = 0; position < items.size(); position++) {
            SpendingPlanItem item = items.get(position);
            item.attach(this, position);
            this.items.add(item);
        }
    }

    void attach(SpendingPlan plan, int position) {
        this.plan = plan;
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public SpendingPlanBucket getBucket() {
        return bucket;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public int getPosition() {
        return position;
    }

    public boolean isFromPaycheck() {
        return fromPaycheck;
    }

    public BigDecimal getPercentOfGross() {
        return percentOfGross;
    }

    public List<SpendingPlanItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
