-- The user's Plaid user, which Plaid needs for Link sessions that add several banks at once.
-- Null until the user first opens Link.
ALTER TABLE users ADD COLUMN plaid_user_id VARCHAR(64) UNIQUE;
