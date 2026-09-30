package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class PlaidTransaction {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "item_id", nullable = false, updatable = false)
    private String itemId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "iso_currency_code", length = 8)
    private String isoCurrencyCode;

    @Column(name = "unofficial_currency_code", length = 16)
    private String unofficialCurrencyCode;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "authorized_date")
    private LocalDate authorizedDate;

    @Column(name = "name", length = 512)
    private String name;

    @Column(name = "merchant_name", length = 512)
    private String merchantName;

    @Column(name = "logo_url", length = 1024)
    private String logoUrl;

    @Column(name = "pending", nullable = false)
    private boolean pending;

    @Column(name = "pending_transaction_id")
    private String pendingTransactionId;

    @Column(name = "payment_channel", length = 32)
    private String paymentChannel;

    @Column(name = "personal_finance_category_primary", length = 64)
    private String personalFinanceCategoryPrimary;

    @Column(name = "personal_finance_category_detailed", length = 128)
    private String personalFinanceCategoryDetailed;

    /**
     * Set by sorting, never by Plaid. Sync saves Plaid's copy over the stored one, which
     * clears this whenever Plaid changes a transaction so it gets sorted again.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "bucket", length = 32)
    private Bucket bucket;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlaidTransaction() {
    }

    public PlaidTransaction(
            String transactionId,
            String itemId,
            UUID userId,
            String accountId,
            BigDecimal amount,
            LocalDate transactionDate
    ) {
        this.transactionId = transactionId;
        this.itemId = itemId;
        this.userId = userId;
        this.accountId = accountId;
        this.amount = amount;
        this.transactionDate = transactionDate;
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

    public PlaidTransaction isoCurrencyCode(String isoCurrencyCode) {
        this.isoCurrencyCode = isoCurrencyCode;
        return this;
    }

    public PlaidTransaction unofficialCurrencyCode(String unofficialCurrencyCode) {
        this.unofficialCurrencyCode = unofficialCurrencyCode;
        return this;
    }

    public PlaidTransaction authorizedDate(LocalDate authorizedDate) {
        this.authorizedDate = authorizedDate;
        return this;
    }

    public PlaidTransaction name(String name) {
        this.name = name;
        return this;
    }

    public PlaidTransaction merchantName(String merchantName) {
        this.merchantName = merchantName;
        return this;
    }

    public PlaidTransaction logoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
        return this;
    }

    public PlaidTransaction pending(boolean pending) {
        this.pending = pending;
        return this;
    }

    public PlaidTransaction pendingTransactionId(String pendingTransactionId) {
        this.pendingTransactionId = pendingTransactionId;
        return this;
    }

    public PlaidTransaction paymentChannel(String paymentChannel) {
        this.paymentChannel = paymentChannel;
        return this;
    }

    public PlaidTransaction personalFinanceCategory(String primary, String detailed) {
        this.personalFinanceCategoryPrimary = primary;
        this.personalFinanceCategoryDetailed = detailed;
        return this;
    }

    public String getTransactionId() {
        return transactionId;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public String getIsoCurrencyCode() {
        return isoCurrencyCode;
    }

    public String getUnofficialCurrencyCode() {
        return unofficialCurrencyCode;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public LocalDate getAuthorizedDate() {
        return authorizedDate;
    }

    public String getName() {
        return name;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public boolean isPending() {
        return pending;
    }

    public String getPendingTransactionId() {
        return pendingTransactionId;
    }

    public String getPaymentChannel() {
        return paymentChannel;
    }

    public String getPersonalFinanceCategoryPrimary() {
        return personalFinanceCategoryPrimary;
    }

    public String getPersonalFinanceCategoryDetailed() {
        return personalFinanceCategoryDetailed;
    }

    public Bucket getBucket() {
        return bucket;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
