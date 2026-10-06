package dev.matthewsawyer.finance_dashboard.service;

import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.repository.SpendingPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class SpendingPlanService {

    private final SpendingPlanRepository planRepository;

    public SpendingPlanService(SpendingPlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    /**
     * Lines and items load lazily, so the plan is turned into {@code view} inside the transaction
     * rather than handed back as an entity.
     */
    @Transactional(readOnly = true)
    public <T> Optional<T> find(UUID userId, Function<SpendingPlan, T> view) {
        return planRepository.findByUserId(userId).map(view);
    }

    /** Creates the user's plan on first save, otherwise replaces it wholesale. */
    @Transactional
    public <T> T save(
            UUID userId,
            String accountId,
            BigDecimal takeHome,
            BigDecimal grossPay,
            BigDecimal fixedCostBufferPercent,
            List<SpendingPlanLine> lines,
            Function<SpendingPlan, T> view
    ) {
        SpendingPlan plan = planRepository.findByUserId(userId)
                .orElseGet(() -> new SpendingPlan(userId));
        plan.replace(accountId, takeHome, grossPay, fixedCostBufferPercent, lines);
        return view.apply(planRepository.saveAndFlush(plan));
    }
}
