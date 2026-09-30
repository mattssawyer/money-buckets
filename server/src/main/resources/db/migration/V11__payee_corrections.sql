-- The user's corrections to how a payee's spending is classified: the bucket its charges count
-- toward and the category they're shown under. A correction covers the payee's charges now and
-- later, and sorting never overrides it. Null means that part is left to Jev or Plaid.
CREATE TABLE payee_corrections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    merchant_key VARCHAR(512) NOT NULL,
    bucket VARCHAR(32),
    category VARCHAR(64),
    corrected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payee_corrections_unique UNIQUE (user_id, merchant_key)
);
