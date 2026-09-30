package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PayeeCorrection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayeeCorrectionRepository extends JpaRepository<PayeeCorrection, UUID> {

    List<PayeeCorrection> findAllByUserId(UUID userId);

    Optional<PayeeCorrection> findByUserIdAndMerchantKey(UUID userId, String merchantKey);

    /**
     * The payee's correction, locked until the transaction ends, so changes to one payee's
     * correction and its charges happen one at a time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM PayeeCorrection c WHERE c.userId = :userId AND c.merchantKey = :merchantKey")
    Optional<PayeeCorrection> lock(@Param("userId") UUID userId, @Param("merchantKey") String merchantKey);
}
