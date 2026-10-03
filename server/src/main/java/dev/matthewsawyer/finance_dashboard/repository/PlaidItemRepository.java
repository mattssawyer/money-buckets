package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlaidItemRepository extends JpaRepository<PlaidItem, String> {

    List<PlaidItem> findAllByRemovedOnIsNull();

    List<PlaidItem> findAllByUserIdOrderByItemIdAsc(UUID userId);

    List<PlaidItem> findAllByUserIdAndRemovedOnIsNullOrderByItemIdAsc(UUID userId);

    List<PlaidItem> findAllByUserIdAndInstitutionIdAndRemovedOnIsNull(UUID userId, String institutionId);

    Optional<PlaidItem> findByItemIdAndUserId(String itemId, UUID userId);

    Optional<PlaidItem> findByItemIdAndUserIdAndRemovedOnIsNull(String itemId, UUID userId);

    /**
     * Syncs write only their own column, so a sync holding an older copy of the item cannot
     * overwrite what another sync stored.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PlaidItem i SET i.transactionsCursor = :cursor WHERE i.itemId = :itemId")
    void updateTransactionsCursor(@Param("itemId") String itemId, @Param("cursor") String cursor);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PlaidItem i SET i.recurringSyncedAt = :syncedAt WHERE i.itemId = :itemId")
    void markRecurringSynced(@Param("itemId") String itemId, @Param("syncedAt") Instant syncedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE PlaidItem i
            SET i.institutionId = :institutionId, i.institutionName = :institutionName,
                i.investments = :investments, i.investmentsAvailable = :investmentsAvailable
            WHERE i.itemId = :itemId""")
    void updateDetails(
            @Param("itemId") String itemId,
            @Param("institutionId") String institutionId,
            @Param("institutionName") String institutionName,
            @Param("investments") boolean investments,
            @Param("investmentsAvailable") boolean investmentsAvailable);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PlaidItem i SET i.removedOn = :removedOn WHERE i.itemId = :itemId")
    void markRemoved(@Param("itemId") String itemId, @Param("removedOn") LocalDate removedOn);
}
