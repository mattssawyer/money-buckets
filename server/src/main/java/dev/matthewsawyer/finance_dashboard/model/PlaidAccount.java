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
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class PlaidAccount {

    /** Plaid depository subtypes that hold everyday money, unlike CDs, HSAs or prepaid cards. */
    private static final Set<String> BANK_ACCOUNT_SUBTYPES =
            Set.of("checking", "savings", "money market", "cash management");
    /** Tracked from the start; money moved into savings is saved rather than spent. */
    private static final Set<String> TRACKED_BY_DEFAULT = Set.of("checking", "cash management", "credit card");

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Column(name = "item_id", nullable = false, updatable = false)
    private String itemId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(name = "official_name", length = 512)
    private String officialName;

    @Column(length = 16)
    private String mask;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 64)
    private String subtype;

    @Column(name = "available_balance", precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "current_balance", precision = 19, scale = 4)
    private BigDecimal currentBalance;

    @Column(name = "limit_amount", precision = 19, scale = 4)
    private BigDecimal limitAmount;

    @Column(name = "iso_currency_code", length = 8)
    private String isoCurrencyCode;

    @Column(name = "unofficial_currency_code", length = 16)
    private String unofficialCurrencyCode;

    @Column(name = "dropped_on")
    private LocalDate droppedOn;

    @Column(name = "tracks_spending", nullable = false)
    private boolean tracksSpending;

    @Column(name = "counts_in_net_worth", nullable = false)
    private boolean countsInNetWorth = true;

    /** How much of this account is the user's; below 100 for a shared account. */
    @Column(name = "share_percent", nullable = false)
    private int sharePercent = 100;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlaidAccount() {
    }

    public PlaidAccount(String accountId, String itemId, UUID userId) {
        this.accountId = accountId;
        this.itemId = itemId;
        this.userId = userId;
    }

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }

    public void updateSnapshot(
            String name,
            String officialName,
            String mask,
            String type,
            String subtype,
            BigDecimal availableBalance,
            BigDecimal currentBalance,
            BigDecimal limitAmount,
            String isoCurrencyCode,
            String unofficialCurrencyCode
    ) {
        this.name = name;
        this.officialName = officialName;
        this.mask = mask;
        this.type = type;
        this.subtype = subtype;
        this.availableBalance = availableBalance;
        this.currentBalance = currentBalance;
        this.limitAmount = limitAmount;
        this.isoCurrencyCode = isoCurrencyCode;
        this.unofficialCurrencyCode = unofficialCurrencyCode;
    }

    /** Whether spending can be tracked from this account: a bank account or a credit card. */
    public boolean isTrackable() {
        if (subtype == null) {
            return false;
        }
        return ("depository".equals(type) && BANK_ACCOUNT_SUBTYPES.contains(subtype))
                || ("credit".equals(type) && "credit card".equals(subtype));
    }

    /** Sets up tracking for a newly linked account from its type; call after its first snapshot. */
    public void trackByDefault() {
        tracksSpending = isTrackable() && TRACKED_BY_DEFAULT.contains(subtype);
    }

    /**
     * The user's choices of whether to count this account's spending and its balance in net worth,
     * and their share of it, which applies to both.
     *
     * @throws IllegalArgumentException when the account can't be tracked or the share isn't 1–100
     */
    public void updateTracking(boolean tracksSpending, boolean countsInNetWorth, int sharePercent) {
        if (tracksSpending && !isTrackable()) {
            throw new IllegalArgumentException("Spending can't be tracked from a " + type + " account");
        }
        if (sharePercent < 1 || sharePercent > 100) {
            throw new IllegalArgumentException("Share must be between 1 and 100 percent");
        }
        this.tracksSpending = tracksSpending;
        this.countsInNetWorth = countsInNetWorth;
        this.sharePercent = sharePercent;
    }

    public boolean countsInNetWorth() {
        return countsInNetWorth;
    }

    public boolean tracksSpending() {
        return tracksSpending;
    }

    public int getSharePercent() {
        return sharePercent;
    }

    /** Plaid stopped returning this account on {@code day}; an earlier drop date is kept. */
    public void drop(LocalDate day) {
        if (droppedOn == null) {
            droppedOn = day;
        }
    }

    /** Plaid is returning this account again. */
    public void restore() {
        droppedOn = null;
    }

    public LocalDate getDroppedOn() {
        return droppedOn;
    }

    public boolean isDropped() {
        return droppedOn != null;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getItemId() {
        return itemId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getOfficialName() {
        return officialName;
    }

    public String getMask() {
        return mask;
    }

    public String getType() {
        return type;
    }

    public String getSubtype() {
        return subtype;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public BigDecimal getLimitAmount() {
        return limitAmount;
    }

    public String getIsoCurrencyCode() {
        return isoCurrencyCode;
    }

    public String getUnofficialCurrencyCode() {
        return unofficialCurrencyCode;
    }
}
