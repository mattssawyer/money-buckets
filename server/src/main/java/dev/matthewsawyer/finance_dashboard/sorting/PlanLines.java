package dev.matthewsawyer.finance_dashboard.sorting;

import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;

import java.util.List;

/**
 * The line names in each bucket of a user's spending plan: the part of the plan that tells
 * sorting where the user files things, like subscriptions under fixed costs.
 */
public record PlanLines(List<String> fixedCosts, List<String> investments, List<String> savings) {

    public static final PlanLines NONE = new PlanLines(List.of(), List.of(), List.of());

    /** The spreadsheet's lines a new plan starts with; the same as PLAN_LINES in the frontend's plan.ts. */
    public static final PlanLines DEFAULT = new PlanLines(
            List.of("Rent/mortgage", "Utilities", "Insurance", "Car payment", "Debt payments", "Groceries",
                    "Clothes", "Phone", "Internet", "Subscriptions"),
            List.of("401(k)", "Roth IRA", "Other investments"),
            List.of("Emergency fund", "Vacations", "Gifts", "House down payment"));

    public static PlanLines of(SpendingPlan plan) {
        return new PlanLines(
                names(plan, SpendingPlanBucket.FIXED_COSTS),
                names(plan, SpendingPlanBucket.INVESTMENTS),
                names(plan, SpendingPlanBucket.SAVINGS));
    }

    public boolean isEmpty() {
        return fixedCosts.isEmpty() && investments.isEmpty() && savings.isEmpty();
    }

    private static List<String> names(SpendingPlan plan, SpendingPlanBucket bucket) {
        return plan.getLines().stream()
                .filter(line -> line.getBucket() == bucket)
                .map(SpendingPlanLine::getName)
                .toList();
    }
}
