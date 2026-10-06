-- Monthly gross pay, before taxes and deductions; only used to turn paycheck percents into
-- amounts. Null until entered.
ALTER TABLE spending_plans ADD COLUMN gross_pay NUMERIC(19, 4);

-- A paycheck contribution set as a percent of gross pay, like most 401(k) elections. The line's
-- amount is still saved, worked out from it; null for a line set in dollars.
ALTER TABLE spending_plan_lines ADD COLUMN percent_of_gross NUMERIC(5, 2);
