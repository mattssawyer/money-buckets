package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "plaid_items")
public class PlaidItem {

    @Id
    @Column(name = "item_id", nullable = false, updatable = false)
    private String itemId;

    @Column(name = "access_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "transactions_cursor", columnDefinition = "TEXT")
    private String transactionsCursor;

    @Column(name = "recurring_synced_at")
    private Instant recurringSyncedAt;

    @Column(name = "institution_id", length = 64)
    private String institutionId;

    @Column(name = "institution_name")
    private String institutionName;

    /** Whether the item has Plaid's investments product, which Plaid bills per item. */
    @Column(nullable = false)
    private boolean investments;

    /**
     * Whether the institution offers Plaid's investments product, whether or not the item has it
     * yet. Null until a sync after this was added finds out.
     */
    @Column(name = "investments_available")
    private Boolean investmentsAvailable;

    /** The day the user removed the item; its data stays for balance history but it no longer syncs. */
    @Column(name = "removed_on")
    private LocalDate removedOn;

    /** Why the user needs to sign in to the bank again; null while the item is connected. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Reconnect reconnect;

    protected PlaidItem() {
    }

    public PlaidItem(String itemId, String encryptedAccessToken, UUID userId) {
        this.itemId = itemId;
        this.encryptedAccessToken = encryptedAccessToken;
        this.userId = userId;
    }

    public String getItemId() {
        return itemId;
    }

    public String getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTransactionsCursor() {
        return transactionsCursor;
    }

    public void updateAccessToken(String encryptedAccessToken) {
        this.encryptedAccessToken = encryptedAccessToken;
    }

    public Instant getRecurringSyncedAt() {
        return recurringSyncedAt;
    }

    public String getInstitutionId() {
        return institutionId;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public boolean hasInvestments() {
        return investments;
    }

    public Boolean getInvestmentsAvailable() {
        return investmentsAvailable;
    }

    public boolean isRemoved() {
        return removedOn != null;
    }

    public Reconnect getReconnect() {
        return reconnect;
    }
}
