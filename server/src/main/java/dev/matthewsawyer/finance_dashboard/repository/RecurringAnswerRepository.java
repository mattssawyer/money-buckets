package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.RecurringAnswer;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurringAnswerRepository extends JpaRepository<RecurringAnswer, UUID> {

    List<RecurringAnswer> findAllByUserId(UUID userId);

    Optional<RecurringAnswer> findByUserIdAndKindAndMerchantKey(UUID userId, RecurringKind kind, String merchantKey);
}
