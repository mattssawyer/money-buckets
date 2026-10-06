-- Independent of transactions so sync and pending-to-posted replacement preserve user choices.
CREATE TABLE transaction_counting (
    transaction_id VARCHAR(255) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    excluded BOOLEAN NOT NULL,
    merchant_key VARCHAR(512),
    kind VARCHAR(32),
    future_from TIMESTAMP WITH TIME ZONE
);
CREATE INDEX transaction_counting_user ON transaction_counting(user_id);
