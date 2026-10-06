-- Preserve the precision accepted by the editor and API, including percentages converted
-- from dollars. V13 may already be applied, so widen its column in a separate migration.
ALTER TABLE spending_plan_lines ALTER COLUMN percent_of_gross TYPE NUMERIC;
