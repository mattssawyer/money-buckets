package dev.matthewsawyer.finance_dashboard.plaid;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

interface PlaidSyncJobRepository extends JpaRepository<PlaidSyncJob, String> {
    List<PlaidSyncJob> findAllByItemId(String itemId);
    void deleteAllByItemId(String itemId);
}
