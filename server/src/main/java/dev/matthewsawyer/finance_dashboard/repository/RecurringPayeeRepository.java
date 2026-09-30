package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecurringPayeeRepository extends JpaRepository<RecurringPayee, UUID> {

    List<RecurringPayee> findAllByUserId(UUID userId);
}
