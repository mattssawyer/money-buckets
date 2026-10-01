-- How often the user says a payee is paid, when they've set it themselves; null leaves it to
-- Plaid or to the dates of the payee's charges.
ALTER TABLE recurring_answers ADD COLUMN frequency VARCHAR(32);
