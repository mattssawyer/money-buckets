-- Why the user needs to reconnect an item through Link update mode; null while it's connected.
ALTER TABLE plaid_items ADD COLUMN reconnect VARCHAR(32);
