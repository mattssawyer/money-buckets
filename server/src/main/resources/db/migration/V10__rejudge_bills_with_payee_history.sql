-- Jev now sees a payee's other charges when judging whether money out is a bill, which catches
-- bills whose name and category don't give them away, such as rent Plaid labels home improvement.
-- Clearing the old judgments has the startup backfill judge money out again with that history.
-- The user's answers about candidates are kept.
UPDATE transactions
SET recurring_probability = NULL, usual_frequency = NULL, recurring_judged_at = NULL
WHERE amount > 0 AND recurring_judged_at IS NOT NULL;
