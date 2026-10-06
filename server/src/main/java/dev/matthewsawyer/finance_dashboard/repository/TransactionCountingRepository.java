package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.TransactionCounting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface TransactionCountingRepository extends JpaRepository<TransactionCounting, String> {
    List<TransactionCounting> findAllByUserId(UUID userId);
}
