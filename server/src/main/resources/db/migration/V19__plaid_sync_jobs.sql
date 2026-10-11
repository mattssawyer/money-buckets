ALTER TABLE plaid_items ADD COLUMN transactions_enabled BOOLEAN;
ALTER TABLE plaid_items ADD COLUMN transaction_history_ready BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE plaid_items ADD COLUMN registered_webhook_url VARCHAR(255);

CREATE TABLE plaid_sync_jobs (
    id VARCHAR(300) PRIMARY KEY,
    item_id VARCHAR(255) NOT NULL REFERENCES plaid_items(item_id) ON DELETE CASCADE,
    scope VARCHAR(32) NOT NULL,
    requested BIGINT NOT NULL DEFAULT 0,
    completed BIGINT NOT NULL DEFAULT 0,
    last_success TIMESTAMP WITH TIME ZONE,
    last_attempt TIMESTAMP WITH TIME ZONE,
    next_attempt TIMESTAMP WITH TIME ZONE,
    failures INTEGER NOT NULL DEFAULT 0,
    trigger VARCHAR(32),
    UNIQUE (item_id, scope)
);
