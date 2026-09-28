package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlaidAccountRepository extends JpaRepository<PlaidAccount, String> {

    List<PlaidAccount> findAllByUserIdOrderByNameAscAccountIdAsc(UUID userId);

    List<PlaidAccount> findAllByUserIdAndDroppedOnIsNullOrderByNameAscAccountIdAsc(UUID userId);

    List<PlaidAccount> findAllByItemId(String itemId);

    boolean existsByAccountIdAndUserId(String accountId, UUID userId);

    Optional<PlaidAccount> findByAccountIdAndUserId(String accountId, UUID userId);

    List<PlaidAccount> findAllByUserIdAndTracksSpendingIsTrue(UUID userId);
}
