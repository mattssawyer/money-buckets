package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recurring_streams")
public class PlaidRecurringStream {

    @Id
    @Column(name = "stream_id", nullable = false, updatable = false)
    private String streamId;

    @Column(name = "item_id", nullable = false, updatable = false)
    private String itemId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "merchant_name", length = 512)
    private String merchantName;

    @Column(length = 512)
    private String description;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "iso_currency_code", length = 8)
    private String isoCurrencyCode;

    @Column(nullable = false, length = 32)
    private String frequency;

    @Column(name = "next_date")
    private LocalDate nextDate;

    @Column(name = "last_date")
    private LocalDate lastDate;

    @Column(name = "is_inflow", nullable = false)
    private boolean inflow;

    @Column(length = 64)
    private String category;

    @Column(name = "category_detailed", length = 128)
    private String categoryDetailed;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlaidRecurringStream() {
    }

    public PlaidRecurringStream(
            String streamId,
            String itemId,
            UUID userId,
            String accountId,
            BigDecimal amount,
            String frequency,
            boolean inflow
    ) {
        this.streamId = streamId;
        this.itemId = itemId;
        this.userId = userId;
        this.accountId = accountId;
        this.amount = amount;
        this.frequency = frequency;
        this.inflow = inflow;
    }

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }

    public PlaidRecurringStream merchantName(String merchantName) {
        this.merchantName = merchantName;
        return this;
    }

    public PlaidRecurringStream description(String description) {
        this.description = description;
        return this;
    }

    public PlaidRecurringStream isoCurrencyCode(String isoCurrencyCode) {
        this.isoCurrencyCode = isoCurrencyCode;
        return this;
    }

    public PlaidRecurringStream nextDate(LocalDate nextDate) {
        this.nextDate = nextDate;
        return this;
    }

    public PlaidRecurringStream lastDate(LocalDate lastDate) {
        this.lastDate = lastDate;
        return this;
    }

    public PlaidRecurringStream category(String category) {
        this.category = category;
        return this;
    }

    public PlaidRecurringStream categoryDetailed(String categoryDetailed) {
        this.categoryDetailed = categoryDetailed;
        return this;
    }

    public String getStreamId() {
        return streamId;
    }

    public String getItemId() {
        return itemId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getIsoCurrencyCode() {
        return isoCurrencyCode;
    }

    /**
     * Whether the payments have stopped: the next one is a whole cycle overdue, as with a
     * cancelled subscription or a bill from an account the user moved away from. Plaid can take
     * a while to stop reporting such a stream. A schedule Plaid can't name is never called stopped.
     */
    public boolean hasStopped(LocalDate today) {
        RecurringFrequency cycle;
        try {
            cycle = RecurringFrequency.valueOf(frequency);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
        LocalDate next = nextDate != null ? nextDate : lastDate == null ? null : cycle.next(lastDate);
        return next != null && cycle.next(next).isBefore(today);
    }

    public String getFrequency() {
        return frequency;
    }

    public LocalDate getNextDate() {
        return nextDate;
    }

    public LocalDate getLastDate() {
        return lastDate;
    }

    public boolean isInflow() {
        return inflow;
    }

    public String getCategory() {
        return category;
    }

    public String getCategoryDetailed() {
        return categoryDetailed;
    }
}
