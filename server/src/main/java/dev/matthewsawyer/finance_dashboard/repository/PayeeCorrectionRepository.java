package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PayeeCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayeeCorrectionRepository extends JpaRepository<PayeeCorrection, UUID> {

    List<PayeeCorrection> findAllByUserId(UUID userId);

    Optional<PayeeCorrection> findByUserIdAndMerchantKey(UUID userId, String merchantKey);
}
