package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlaidRecurringStreamRepository extends JpaRepository<PlaidRecurringStream, String> {

    List<PlaidRecurringStream> findAllByUserId(UUID userId);

    List<PlaidRecurringStream> findAllByUserIdAndAccountIdIn(UUID userId, Collection<String> accountIds);

    void deleteAllByItemId(String itemId);
}
