-- When the user finished onboarding; null while they still need it. Users who had already linked
-- a bank before onboarding existed don't need it.
ALTER TABLE users ADD COLUMN onboarded_at TIMESTAMP WITH TIME ZONE;
UPDATE users SET onboarded_at = CURRENT_TIMESTAMP
    WHERE EXISTS (SELECT 1 FROM plaid_items WHERE plaid_items.user_id = users.id);
