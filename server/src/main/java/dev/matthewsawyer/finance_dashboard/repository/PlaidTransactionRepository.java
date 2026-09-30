package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlaidTransactionRepository extends JpaRepository<PlaidTransaction, String> {

    @Query("""
            SELECT t FROM PlaidTransaction t
            WHERE t.userId = :userId
              AND t.accountId IN :accountIds
            ORDER BY t.transactionDate DESC, t.transactionId ASC
            """)
    Page<PlaidTransaction> findRecent(
            @Param("userId") UUID userId,
            @Param("accountIds") Collection<String> accountIds,
            Pageable pageable);

    void deleteAllByItemId(String itemId);

    void deleteAllByItemIdAndTransactionIdIn(String itemId, Collection<String> transactionIds);

    /**
     * The spending in a date range, newest first: every transaction except pay, card payments and
     * money that only moved between the user's own accounts. Transactions not sorted yet are
     * included with no bucket.
     */
    @Query("""
            SELECT t FROM PlaidTransaction t
            WHERE t.userId = :userId
              AND t.transactionDate BETWEEN :start AND :end
              AND t.accountId IN :accountIds
              AND (t.bucket IS NULL
                   OR t.bucket <> dev.matthewsawyer.finance_dashboard.model.Bucket.NOT_COUNTED)
              AND (t.personalFinanceCategoryPrimary IS NULL
                   OR t.personalFinanceCategoryPrimary NOT IN :excludedCategories)
              AND (t.personalFinanceCategoryDetailed IS NULL
                   OR t.personalFinanceCategoryDetailed NOT IN :excludedCategories)
            ORDER BY t.transactionDate DESC, t.transactionId ASC
            """)
    List<PlaidTransaction> findSpending(
            @Param("userId") UUID userId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("accountIds") Collection<String> accountIds,
            @Param("excludedCategories") Collection<String> excludedCategories);

    /** Money that arrived in the given accounts from a transfer, oldest first. */
    @Query("""
            SELECT t FROM PlaidTransaction t
            WHERE t.userId = :userId
              AND t.accountId IN :accountIds
              AND t.transactionDate BETWEEN :start AND :end
              AND t.personalFinanceCategoryPrimary = 'TRANSFER_IN'
            ORDER BY t.transactionDate ASC, t.transactionId ASC
            """)
    List<PlaidTransaction> findTransfersIn(
            @Param("userId") UUID userId,
            @Param("accountIds") Collection<String> accountIds,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    /**
     * The user's transactions that need a bucket, newest first so this month's are sorted
     * before older ones. With {@code includeSorted}, already sorted transactions come back too.
     */
    @Query("""
            SELECT t FROM PlaidTransaction t
            WHERE t.userId = :userId
              AND (:includeSorted = TRUE OR t.bucket IS NULL)
              AND (t.personalFinanceCategoryPrimary IS NULL
                   OR t.personalFinanceCategoryPrimary NOT IN :excludedCategories)
              AND (t.personalFinanceCategoryDetailed IS NULL
                   OR t.personalFinanceCategoryDetailed NOT IN :excludedCategories)
            ORDER BY t.transactionDate DESC, t.transactionId ASC
            """)
    List<PlaidTransaction> findToSort(
            @Param("userId") UUID userId,
            @Param("includeSorted") boolean includeSorted,
            @Param("excludedCategories") Collection<String> excludedCategories);

    /**
     * Stores a transaction's bucket unless sync changed the transaction after it was read,
     * in which case the bucket may no longer fit and the next sort picks it up. Returns the rows
     * updated.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE PlaidTransaction t SET t.bucket = :bucket
            WHERE t.transactionId = :transactionId AND t.updatedAt = :readUpdatedAt
            """)
    int updateBucket(
            @Param("transactionId") String transactionId,
            @Param("readUpdatedAt") Instant readUpdatedAt,
            @Param("bucket") Bucket bucket);

    /**
     * Stores a transaction's bucket together with Jev's recurring judgment, guarded like
     * {@link #updateBucket}. A null {@code judgedAt} means it wasn't asked whether it repeats.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE PlaidTransaction t
            SET t.bucket = :bucket,
                t.recurringProbability = :probability,
                t.usualFrequency = :frequency,
                t.recurringJudgedAt = :judgedAt
            WHERE t.transactionId = :transactionId AND t.updatedAt = :readUpdatedAt
            """)
    int updateSorted(
            @Param("transactionId") String transactionId,
            @Param("readUpdatedAt") Instant readUpdatedAt,
            @Param("bucket") Bucket bucket,
            @Param("probability") BigDecimal probability,
            @Param("frequency") RecurringFrequency frequency,
            @Param("judgedAt") Instant judgedAt);

    /** Stores Jev's recurring judgment without touching the bucket, guarded like {@link #updateBucket}. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE PlaidTransaction t
            SET t.recurringProbability = :probability,
                t.usualFrequency = :frequency,
                t.recurringJudgedAt = :judgedAt
            WHERE t.transactionId = :transactionId AND t.updatedAt = :readUpdatedAt
            """)
    int updateRecurring(
            @Param("transactionId") String transactionId,
            @Param("readUpdatedAt") Instant readUpdatedAt,
            @Param("probability") BigDecimal probability,
            @Param("frequency") RecurringFrequency frequency,
            @Param("judgedAt") Instant judgedAt);

    /**
     * Transactions still waiting for Jev's recurring judgment, newest first: pay coming in, and
     * spending that has already been sorted (money out not sorted yet is judged as it's sorted).
     * Money that only moved between the user's own accounts is never a bill.
     */
    @Query("""
            SELECT t FROM PlaidTransaction t
            WHERE t.userId = :userId
              AND t.recurringJudgedAt IS NULL
              AND (
                (t.personalFinanceCategoryPrimary = 'INCOME' AND t.amount < 0)
                OR (t.amount > 0 AND t.bucket IS NOT NULL
                    AND t.bucket <> dev.matthewsawyer.finance_dashboard.model.Bucket.NOT_COUNTED
                    AND (t.personalFinanceCategoryPrimary IS NULL
                         OR t.personalFinanceCategoryPrimary NOT IN :excludedCategories)
                    AND (t.personalFinanceCategoryDetailed IS NULL
                         OR t.personalFinanceCategoryDetailed NOT IN :excludedCategories))
              )
            ORDER BY t.transactionDate DESC, t.transactionId ASC
            """)
    List<PlaidTransaction> findToJudge(
            @Param("userId") UUID userId,
            @Param("excludedCategories") Collection<String> excludedCategories);

    /**
     * Transactions in the given accounts that Jev has judged whether they repeat, newest first:
     * the same pay and spending {@link #findToJudge} asks about.
     */
    @Query("""
            SELECT t FROM PlaidTransaction t
            WHERE t.userId = :userId
              AND t.accountId IN :accountIds
              AND t.recurringJudgedAt IS NOT NULL
              AND (
                (t.personalFinanceCategoryPrimary = 'INCOME' AND t.amount < 0)
                OR (t.amount > 0 AND t.bucket IS NOT NULL
                    AND t.bucket <> dev.matthewsawyer.finance_dashboard.model.Bucket.NOT_COUNTED
                    AND (t.personalFinanceCategoryPrimary IS NULL
                         OR t.personalFinanceCategoryPrimary NOT IN :excludedCategories)
                    AND (t.personalFinanceCategoryDetailed IS NULL
                         OR t.personalFinanceCategoryDetailed NOT IN :excludedCategories))
              )
            ORDER BY t.transactionDate DESC, t.transactionId ASC
            """)
    List<PlaidTransaction> findJudged(
            @Param("userId") UUID userId,
            @Param("accountIds") Collection<String> accountIds,
            @Param("excludedCategories") Collection<String> excludedCategories);

    /** All of the user's money out, in every account, newest first. */
    @Query("""
            SELECT t FROM PlaidTransaction t
            WHERE t.userId = :userId AND t.amount > 0
            ORDER BY t.transactionDate DESC, t.transactionId ASC
            """)
    List<PlaidTransaction> findMoneyOut(@Param("userId") UUID userId);

    /** Users with transactions {@link #findToJudge} would return, such as everyone's history on first release. */
    @Query("""
            SELECT DISTINCT t.userId FROM PlaidTransaction t
            WHERE t.recurringJudgedAt IS NULL
              AND (
                (t.personalFinanceCategoryPrimary = 'INCOME' AND t.amount < 0)
                OR (t.amount > 0 AND t.bucket IS NOT NULL
                    AND t.bucket <> dev.matthewsawyer.finance_dashboard.model.Bucket.NOT_COUNTED
                    AND (t.personalFinanceCategoryPrimary IS NULL
                         OR t.personalFinanceCategoryPrimary NOT IN :excludedCategories)
                    AND (t.personalFinanceCategoryDetailed IS NULL
                         OR t.personalFinanceCategoryDetailed NOT IN :excludedCategories))
              )
            """)
    List<UUID> findUsersWithTransactionsToJudge(@Param("excludedCategories") Collection<String> excludedCategories);
}
